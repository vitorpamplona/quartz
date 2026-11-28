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

import okhttp3.OkHttpClient
import java.io.InputStream

actual class GitSmartHttpTransport actual constructor(
    http: GitHttpTransport,
    authorization: GitHttpAuth?,
) {
    private val smart = GitSmartHttp(http, authorization)

    /**
     * [okHttpClient] is the shared per-URL client provider, so every request inherits
     * the app's proxy / Tor routing and onion-rewrite interceptors.
     */
    @Deprecated(
        "Use GitSmartHttpTransport(OkHttpGitTransport(okHttpClient)) instead. Removed in 3.0.0.",
        ReplaceWith("GitSmartHttpTransport(OkHttpGitTransport(okHttpClient))"),
    )
    constructor(okHttpClient: (String) -> OkHttpClient) : this(OkHttpGitTransport(okHttpClient), null)

    @Deprecated(
        "Use GitSmartHttpTransport(OkHttpGitTransport(okHttpClient), authorization) instead. Removed in 3.0.0.",
        ReplaceWith("GitSmartHttpTransport(OkHttpGitTransport(okHttpClient), authorization)"),
    )
    constructor(okHttpClient: (String) -> OkHttpClient, authorization: GitHttpAuth?) : this(OkHttpGitTransport(okHttpClient), authorization)

    actual suspend fun fetchCapabilities(cloneUrl: String): GitCapabilities = smart.fetchCapabilities(cloneUrl)

    actual suspend fun lsRefs(
        cloneUrl: String,
        caps: GitCapabilities,
    ): List<GitRef> = smart.lsRefs(cloneUrl, caps)

    actual suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray = smart.fetchPack(cloneUrl, caps, wants, deepen, filter)

    actual suspend fun fetchPack(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        haves: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray = smart.fetchPack(cloneUrl, caps, wants, haves, deepen, filter)

    actual suspend fun <T> fetchPackSource(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
        read: (GitByteSource) -> T,
    ): T = smart.fetchPackSource(cloneUrl, caps, wants, deepen, filter, read)

    /**
     * A `fetch` like [fetchPack] whose pack is handed to [read] as a stream while the response
     * arrives, instead of as one array: [read] runs on the IO dispatcher with the connection
     * open, and the response is closed when it returns. Pair it with [GitPackReader] so a pack
     * is parsed object by object and its largest objects can be passed over without being held.
     */
    @Deprecated(
        "Use fetchPackSource, which hands out a GitByteSource, instead. Removed in 3.0.0.",
        ReplaceWith("fetchPackSource(cloneUrl, caps, wants, deepen, filter, read)"),
    )
    suspend fun <T> fetchPackStream(
        cloneUrl: String,
        caps: GitCapabilities,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
        read: (InputStream) -> T,
    ): T = smart.fetchPackSource(cloneUrl, caps, wants, deepen, filter) { read(it.asInputStream()) }

    /** Empty: kept so the JVM class still has the `Companion` it had before it moved to common code. */
    @Deprecated("Holds nothing. Removed in 3.0.0.")
    companion object
}
