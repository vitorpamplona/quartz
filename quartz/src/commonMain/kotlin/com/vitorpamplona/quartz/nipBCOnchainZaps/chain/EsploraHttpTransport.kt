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
package com.vitorpamplona.quartz.nipBCOnchainZaps.chain

/**
 * The HTTP an [EsploraClient] needs: a GET (asking for JSON or plain text) and a POST of a text
 * body, each returning the status and the body as text. Each platform supplies one (OkHttp on
 * Android and the JVM, [OkHttpEsploraTransport]). Implementations pick the client for the URL
 * themselves (Tor, proxies), run off the main thread, and throw on a transport failure; an HTTP
 * error status is an [EsploraHttpResponse], not an exception.
 */
interface EsploraHttpTransport {
    /** A GET with `Accept: application/json`. */
    suspend fun getJson(url: String): EsploraHttpResponse

    /** A GET with no `Accept` header, for the endpoints that answer plain text. */
    suspend fun get(url: String): EsploraHttpResponse

    /** A POST of [body] as `text/plain`. */
    suspend fun postText(
        url: String,
        body: String,
    ): EsploraHttpResponse
}

/** One Esplora response: the status, its reason phrase (may be empty) and the body. */
class EsploraHttpResponse(
    val status: Int,
    val message: String,
    val body: String,
) {
    val isSuccessful: Boolean get() = status in 200..299
}
