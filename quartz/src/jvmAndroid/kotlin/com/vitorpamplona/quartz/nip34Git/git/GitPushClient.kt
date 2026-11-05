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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.ByteArrayOutputStream

/** The id git uses for "no object": the old value of a ref being created, the new one of a deletion. */
const val GIT_ZERO_OID = "0000000000000000000000000000000000000000"

/** One ref a push moves: from [oldOid] (what the pusher expects it to be now) to [newOid]. */
class GitRefUpdate(
    val ref: String,
    val oldOid: String,
    val newOid: String,
) {
    val isDelete: Boolean get() = newOid == GIT_ZERO_OID
}

/** What `git-receive-pack` advertised: its refs and its capabilities. */
class GitReceivePackAdvertisement(
    val refs: Map<String, String>,
    val capabilities: Set<String>,
)

/**
 * The server's report of a push. [unpackError] is set when it could not take the pack at all;
 * [refErrors] maps each refused ref to the reason it gave ("non-fast-forward", "stale info", or a
 * GRASP server's "state event mismatch"…).
 */
class GitPushReport(
    val unpackError: String?,
    val accepted: List<String>,
    val refErrors: Map<String, String>,
    /** What the server's hooks printed (sideband 2, git's `remote:` lines): often the real reason of an `ng`. */
    val remoteMessages: List<String> = emptyList(),
) {
    /** One line for a person: the unpack error, else each refused ref and why, then the hooks' words. */
    fun describe(): String =
        buildList {
            unpackError?.let { add(it) }
            refErrors.forEach { (ref, why) -> add("$ref: $why") }
            addAll(remoteMessages.filter { it.isNotBlank() })
        }.joinToString("; ")

    val isSuccess: Boolean get() = unpackError == null && refErrors.isEmpty()
}

/**
 * The wire format of a smart-HTTP push (`git-receive-pack`, protocol v0, the one every server
 * speaks for pushes), kept apart from the HTTP calls so it can be tested byte for byte.
 */
object GitReceivePack {
    /** Parses `GET info/refs?service=git-receive-pack`: `# service=…`, then `oid ref\0caps`, then `oid ref` lines. */
    fun parseAdvertisement(lines: List<PktLine>): GitReceivePackAdvertisement {
        val refs = LinkedHashMap<String, String>()
        var capabilities = emptySet<String>()
        var first = true
        for (line in lines) {
            if (line !is PktLine.Data) continue
            val text = line.text()
            if (text.startsWith("# service=")) continue
            // A server that refuses the push outright (no such repository, no permission) says so here.
            if (text.startsWith("ERR ")) throw GitHttpException(text.removePrefix("ERR ").trim())
            val nul = text.indexOf('\u0000')
            val refPart = if (nul >= 0) text.substring(0, nul) else text
            if (first && nul >= 0) {
                capabilities =
                    text
                        .substring(nul + 1)
                        .split(' ')
                        .filter { it.isNotBlank() }
                        .toSet()
            }
            first = false
            val space = refPart.indexOf(' ')
            if (space <= 0) continue
            val oid = refPart.substring(0, space)
            val name = refPart.substring(space + 1)
            // An empty repository advertises `0…0 capabilities^{}` only to carry its capabilities.
            if (name != "capabilities^{}") refs[name] = oid
        }
        return GitReceivePackAdvertisement(refs, capabilities)
    }

    /**
     * The `POST git-receive-pack` body: one command line per update (the first carrying the
     * capabilities we use), a flush, then the pack. A push that only deletes sends no pack.
     */
    fun request(
        updates: List<GitRefUpdate>,
        capabilities: List<String>,
        pack: ByteArray?,
    ): ByteArray {
        require(updates.isNotEmpty()) { "a push needs at least one ref update" }
        val out = ByteArrayOutputStream()
        updates.forEachIndexed { index, update ->
            val command = "${update.oldOid} ${update.newOid} ${update.ref}"
            val line = if (index == 0 && capabilities.isNotEmpty()) command + "\u0000" + capabilities.joinToString(" ") else command
            out.write(PktLineCodec.dataLine(line + "\n"))
        }
        out.write(PktLineCodec.FLUSH)
        if (pack != null && updates.any { !it.isDelete }) out.write(pack)
        return out.toByteArray()
    }

    /** The capabilities worth asking for, of the ones [advertised]: the report, and sideband to read errors. */
    fun chooseCapabilities(
        advertised: Set<String>,
        updates: Int,
        agent: String,
    ): List<String> =
        buildList {
            if ("report-status" in advertised) add("report-status")
            if ("side-band-64k" in advertised) add("side-band-64k")
            // All or nothing when several refs move, so a server never keeps half a merge.
            if (updates > 1 && "atomic" in advertised) add("atomic")
            if (advertised.any { it.startsWith("agent=") }) add("agent=$agent")
        }

    /**
     * Parses the response: with `side-band-64k` the report arrives in band 1 (progress in 2, a
     * fatal error in 3), itself pkt-lines of `unpack ok|<error>` then `ok <ref>` / `ng <ref> <why>`.
     */
    fun parseReport(
        body: ByteArray,
        sideband: Boolean,
    ): GitPushReport {
        var remote = emptyList<String>()
        val reportLines =
            if (sideband) {
                val report = ByteArrayOutputStream()
                val fatal = StringBuilder()
                val progress = StringBuilder()
                PktLineCodec.parse(body).forEach { line ->
                    if (line is PktLine.Data && line.payload.isNotEmpty()) {
                        when (line.payload[0].toInt() and 0xff) {
                            1 -> report.write(line.payload, 1, line.payload.size - 1)
                            2 -> progress.append(line.payload.decodeToString(1, line.payload.size))
                            3 -> fatal.append(line.payload.decodeToString(1, line.payload.size))
                        }
                    }
                }
                remote = progress.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
                if (fatal.isNotEmpty()) return GitPushReport(fatal.toString().trim(), emptyList(), emptyMap(), remote)
                PktLineCodec.parse(report.toByteArray())
            } else {
                PktLineCodec.parse(body)
            }

        var unpackError: String? = "no report from the server"
        val accepted = ArrayList<String>()
        val errors = LinkedHashMap<String, String>()
        reportLines.forEach { line ->
            if (line !is PktLine.Data) return@forEach
            val text = line.text()
            when {
                text == "unpack ok" -> unpackError = null
                // A GRASP server refuses a push that matches no signed state with an `ERR` line.
                text.startsWith("ERR ") -> return GitPushReport(text.removePrefix("ERR ").trim(), accepted, errors, remote)
                text.startsWith("unpack ") -> unpackError = text.removePrefix("unpack ")
                text.startsWith("ok ") -> accepted.add(text.removePrefix("ok "))
                text.startsWith("ng ") -> {
                    val rest = text.removePrefix("ng ")
                    val space = rest.indexOf(' ')
                    if (space > 0) errors[rest.substring(0, space)] = rest.substring(space + 1) else errors[rest] = "rejected"
                }
            }
        }
        return GitPushReport(unpackError, accepted, errors, remote)
    }
}

/**
 * Pushes to a git server over smart HTTP: reads its refs, then sends the ref updates and the
 * objects it lacks in one `git-receive-pack` request. A GRASP server decides from the repository's
 * signed state event (kind 30618); a private one also wants [authorization] (GRASP-08).
 */
class GitPushClient(
    private val okHttpClient: (String) -> OkHttpClient,
    private val agent: String = "quartz",
    /** The `Authorization` header for a private repository ([GitHttpAuth]), or null when it needs none. */
    private val authorization: (suspend (cloneUrl: String) -> String?)? = null,
) {
    suspend fun advertisement(cloneUrl: String): GitReceivePackAdvertisement =
        withContext(Dispatchers.IO) {
            val url = "${base(cloneUrl)}/info/refs?service=git-receive-pack"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "*/*")
                    .get()
                    .build()
            GitReceivePack.parseAdvertisement(PktLineCodec.parse(execute(cloneUrl, url, request)))
        }

    /**
     * Pushes [updates] with [objects] (whole objects; the caller sends what the server lacks).
     * Each update's old id must match the server's, so a branch someone moved meanwhile is
     * refused rather than overwritten.
     */
    suspend fun push(
        cloneUrl: String,
        updates: List<GitRefUpdate>,
        objects: Collection<GitObject>,
        advertisement: GitReceivePackAdvertisement? = null,
    ): GitPushReport =
        withContext(Dispatchers.IO) {
            val advertised = advertisement ?: advertisement(cloneUrl)
            val capabilities = GitReceivePack.chooseCapabilities(advertised.capabilities, updates.size, agent)
            val body = GitReceivePack.request(updates, capabilities, PackfileWriter.write(objects))
            val url = "${base(cloneUrl)}/git-receive-pack"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/x-git-receive-pack-result")
                    .post(body.toRequestBody(RECEIVE_PACK_REQUEST))
                    .build()
            GitReceivePack.parseReport(execute(cloneUrl, url, request), sideband = "side-band-64k" in capabilities)
        }

    private suspend fun execute(
        cloneUrl: String,
        url: String,
        request: Request,
    ): ByteArray =
        okHttpClient(url).newCall(authorized(cloneUrl, request)).executeAsync().use { response ->
            if (!response.isSuccessful) {
                val reason =
                    runCatching { response.body.string() }
                        .getOrNull()
                        ?.take(300)
                        ?.trim()
                        .orEmpty()
                throw GitHttpException("HTTP ${response.code} from $url" + if (reason.isNotEmpty()) ": $reason" else "", httpCode = response.code)
            }
            response.body.bytes()
        }

    private fun base(cloneUrl: String): String = cloneUrl.trim().trimEnd('/')

    /** [request] with the repository's `Authorization` ([authorization]), when it needs one. */
    private suspend fun authorized(
        cloneUrl: String,
        request: Request,
    ): Request {
        val header = authorization?.invoke(cloneUrl) ?: return request
        return request.newBuilder().header("Authorization", header).build()
    }

    companion object {
        private val RECEIVE_PACK_REQUEST = "application/x-git-receive-pack-request".toMediaType()
    }
}
