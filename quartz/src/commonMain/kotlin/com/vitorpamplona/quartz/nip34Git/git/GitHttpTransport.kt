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

/**
 * The HTTP the git clients ([GitSmartHttpTransport], [GitPushClient], [GitHttpClient]) need: a GET,
 * a POST of a binary body, and a POST whose response is read as it arrives. Each platform supplies
 * one (OkHttp on Android and the JVM, [OkHttpGitTransport]; Ktor on iOS). Implementations pick the
 * client for the URL themselves (Tor, proxies), send [headers] as given (the git clients set
 * `Git-Protocol`, `Accept` and, for a private repository, `Authorization`), and throw on a
 * transport failure; an HTTP error status is a [GitHttpResponse], not an exception.
 */
interface GitHttpTransport {
    /** A GET of [url] with [headers]. */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GitHttpResponse

    /** A POST of [body] as [contentType] (a `Content-Type` such as `application/x-git-upload-pack-request`). */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
    ): GitHttpResponse

    /**
     * A POST like [post] whose response is handed to [read] while it arrives: its status, and its
     * body as a [GitByteSource] that blocks until more bytes come. [read] runs with the response
     * open, off the main thread, and the response is closed when it returns. A client that can't
     * stream may read the whole body and pass [GitByteSource.of] it; a search then holds a batch of
     * files at once instead of one.
     */
    suspend fun <T> postStreaming(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
        read: (status: Int, body: GitByteSource) -> T,
    ): T
}

/** One git HTTP response: its status and whole body. */
class GitHttpResponse(
    val status: Int,
    val body: ByteArray,
) {
    val isSuccessful: Boolean get() = status in 200..299
}
