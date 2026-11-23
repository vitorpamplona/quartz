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
package com.vitorpamplona.quartz.nip11RelayInfo

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The OkHttp fetcher reads a bounded body and takes a document that starts with whitespace. */
class OkHttpNip11FetcherTest {
    private val relay = RelayUrlNormalizer.normalize("wss://nostr.example.com")

    private fun answering(body: String) =
        OkHttpNip11Fetcher {
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/nostr+json".toMediaType()))
                        .build()
                }.build()
        }

    @Test
    fun aDocumentWithLeadingWhitespaceIsRead() =
        runBlocking {
            assertEquals("pretty", answering("\n  {\"name\":\"pretty\"}\n").fetch(relay).name)
        }

    @Test
    fun anOversizedBodyIsRefused() {
        val huge = "{\"name\":\"" + "x".repeat((OkHttpNip11Fetcher.MAX_DOCUMENT_BYTES + 10).toInt()) + "\"}"
        assertFailsWith<Nip11FetchException> { runBlocking { answering(huge).fetch(relay) } }
    }
}
