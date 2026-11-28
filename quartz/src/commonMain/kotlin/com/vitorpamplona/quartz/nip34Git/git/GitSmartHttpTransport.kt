/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.nip34Git.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** A ref returned by `ls-refs`: an oid, its full name, and (for symrefs) its target. */
class GitRef(
    val oid: String,
    val name: String,
    val symrefTarget: String? = null,
)

/** Parsed protocol-v2 server capabilities. */
class GitCapabilities(
    val agent: String?,
    val objectFormat: String,
    val supportsLsRefs: Boolean,
    val fetchFeatures: Set<String>,
) {
    /** Some servers advertise `fetch` with no feature list; track that we saw the command. */
    var rawHadFetch: Boolean = false

    val supportsFetch: Boolean get() = fetchFeatures.isNotEmpty() || rawHadFetch
    val supportsFilter: Boolean get() = fetchFeatures.contains("filter")
    val supportsShallow: Boolean get() = fetchFeatures.contains("shallow")
}

/**
 * Low-level git smart-HTTP **protocol v2** transport (`git-upload-pack` only;
 * we never write). The three exchanges are `info/refs` (capabilities),
 * `ls-refs`, and `fetch`. Wire encoding/decoding lives in [GitUploadPack].
 *
 * [http] makes the requests (the app's OkHttp or Ktor, so every request inherits its proxy / Tor
 * routing); [authorization] signs them for a private repository ([GitHttpAuth]), or is null when
 * none needs it. On JVM and Android the class also keeps its older OkHttp constructors.
 */
expect class GitSmartHttpTransport(
    http: GitHttpTransport,
    authorization: GitHttpAuth? = null,
) {
    suspend fun fetchCapabilities(cloneUrl: String): GitCapabilities

    suspend fun lsRefs(
        cloneUrl: String,
        caps: GitCapabilities,
    ): List<GitRef>

    /**
     * Runs a `fetch` and returns the raw packfile bytes (sideband demuxed).
     *
     * @param wants object ids to request.
     * @param deepen shallow depth (1 = tip only). Null for a full fetch.
     * @param filter a partial-clone filter spec such as `blob:none` (omit file
     *   contents) or `tree:0` (omit trees and blobs). Applied only when the server
     *   advertises `filter`.
     */
    suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray

    /**
     * A `fetch` of [wants] that sends [haves], so the pack holds only what the server's history has
     * beyond them, and no thin-pack deltas against objects it left out.
     */
    suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        haves: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray

    /**
     * A `fetch` like [fetchPack] whose pack is handed to [read] as a [GitByteSource] while the
     * response arrives, instead of as one array: [read] runs off the main thread with the
     * connection open, and the response is closed when it returns. Pair it with [GitPackReader]
     * so a pack is parsed object by object and its largest objects can be passed over without
     * being held.
     */
    suspend fun <T> fetchPackSource(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
        read: (GitByteSource) -> T,
    ): T
}

/** What every [GitSmartHttpTransport] does, over its [http]. */
internal class GitSmartHttp(
    private val http: GitHttpTransport,
    /** The `Authorization` header source for a private repository ([GitHttpAuth]), or null when none needs one. */
    private val authorization: GitHttpAuth?,
) {
    suspend fun fetchCapabilities(cloneUrl: String): GitCapabilities =
        withContext(Dispatchers.IO) {
            val url = "${base(cloneUrl)}/info/refs?service=git-upload-pack"
            val headers = linkedMapOf("Git-Protocol" to "version=2", "Accept" to "*/*")
            GitUploadPack.parseCapabilities(PktLines.parse(successful(url, http.get(url, authorized(cloneUrl, headers)))))
        }

    suspend fun lsRefs(
        cloneUrl: String,
        caps: GitCapabilities,
    ): List<GitRef> =
        withContext(Dispatchers.IO) {
            val body = GitUploadPack.lsRefsRequest(caps.objectFormat)
            GitUploadPack.parseRefs(PktLines.parse(postUploadPack(cloneUrl, body)))
        }

    suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(wants.isNotEmpty()) { "fetch requires at least one want" }
            val body =
                GitUploadPack.fetchRequest(
                    objectFormat = caps.objectFormat,
                    wants = wants,
                    deepen = if (caps.supportsShallow) deepen else null,
                    filter = filter?.takeIf { caps.supportsFilter },
                )
            GitUploadPack.extractPack(PktLines.parse(postUploadPack(cloneUrl, body)))
        }

    suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        haves: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(wants.isNotEmpty()) { "fetch requires at least one want" }
            val body =
                GitUploadPack.fetchRequest(
                    objectFormat = caps.objectFormat,
                    wants = wants,
                    haves = haves,
                    deepen = if (caps.supportsShallow) deepen else null,
                    filter = filter?.takeIf { caps.supportsFilter },
                )
            GitUploadPack.extractPack(PktLines.parse(postUploadPack(cloneUrl, body)))
        }

    suspend fun <T> fetchPackSource(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
        read: (GitByteSource) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            require(wants.isNotEmpty()) { "fetch requires at least one want" }
            val body =
                GitUploadPack.fetchRequest(
                    objectFormat = caps.objectFormat,
                    wants = wants,
                    deepen = if (caps.supportsShallow) deepen else null,
                    filter = filter?.takeIf { caps.supportsFilter },
                )
            val url = "${base(cloneUrl)}/git-upload-pack"
            http.postStreaming(url, authorized(cloneUrl, uploadPackHeaders()), GIT_UPLOAD_PACK_REQUEST, body) { status, response ->
                if (status !in 200..299) {
                    throw GitHttpException("HTTP $status from $url", httpCode = status)
                }
                read(GitUploadPack.packSource(response))
            }
        }

    private suspend fun postUploadPack(
        cloneUrl: String,
        body: ByteArray,
    ): ByteArray {
        val url = "${base(cloneUrl)}/git-upload-pack"
        return successful(url, http.post(url, authorized(cloneUrl, uploadPackHeaders()), GIT_UPLOAD_PACK_REQUEST, body))
    }

    private fun uploadPackHeaders(): LinkedHashMap<String, String> = linkedMapOf("Git-Protocol" to "version=2", "Accept" to "application/x-git-upload-pack-result")

    private fun successful(
        url: String,
        response: GitHttpResponse,
    ): ByteArray {
        if (!response.isSuccessful) {
            throw GitHttpException("HTTP ${response.status} from $url", httpCode = response.status)
        }
        return response.body
    }

    private fun base(cloneUrl: String): String = cloneUrl.trim().trimEnd('/')

    /** [headers] with the repository's `Authorization` ([authorization]), when it needs one. */
    private suspend fun authorized(
        cloneUrl: String,
        headers: LinkedHashMap<String, String>,
    ): Map<String, String> {
        val header = authorization?.header(cloneUrl) ?: return headers
        headers["Authorization"] = header
        return headers
    }

    companion object {
        const val GIT_UPLOAD_PACK_REQUEST = "application/x-git-upload-pack-request"
    }
}

/**
 * A git smart-HTTP failure. [httpCode] is the status of a failed request; [noBranches] marks a
 * repository that answered but holds no branch yet (nothing pushed).
 */
class GitHttpException(
    message: String,
    val httpCode: Int? = null,
    val noBranches: Boolean = false,
) : Exception(message)
