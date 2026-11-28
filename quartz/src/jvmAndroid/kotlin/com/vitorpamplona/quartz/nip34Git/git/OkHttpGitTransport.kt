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
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

/** [GitHttpTransport] over OkHttp. [okHttpClient] picks the client for each URL (Tor, proxies, onion rewrites). */
class OkHttpGitTransport(
    private val okHttpClient: (String) -> OkHttpClient,
) : GitHttpTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): GitHttpResponse =
        execute(
            url,
            Request
                .Builder()
                .url(url)
                .headers(headers.toHeaders())
                .get()
                .build(),
        )

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
    ): GitHttpResponse = execute(url, postRequest(url, headers, contentType, body))

    override suspend fun <T> postStreaming(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
        read: (status: Int, body: GitByteSource) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            okHttpClient(url).newCall(postRequest(url, headers, contentType, body)).executeAsync().use { response ->
                read(response.code, response.body.byteStream().asGitByteSource())
            }
        }

    private fun postRequest(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
    ): Request =
        Request
            .Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()

    private suspend fun execute(
        url: String,
        request: Request,
    ): GitHttpResponse =
        withContext(Dispatchers.IO) {
            okHttpClient(url).newCall(request).executeAsync().use { response ->
                GitHttpResponse(response.code, response.body.bytes())
            }
        }
}
