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
package com.vitorpamplona.quartz.nip05DnsIdentifiers

import com.vitorpamplona.quartz.utils.okhttp.publicAddressesOnly
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Answers come from a stub interceptor: no socket is opened. */
class OkHttpNip05FetcherTest {
    private val url = "https://domain.example/.well-known/nostr.json?name=bob"

    private fun answering(
        code: Int,
        body: String = "",
    ) = Interceptor { chain ->
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody())
            .build()
    }

    private fun fetcher(
        interceptor: Interceptor,
        maxBodyBytes: Long = OkHttpNip05Fetcher.DEFAULT_MAX_BODY_BYTES,
    ) = OkHttpNip05Fetcher({ OkHttpClient.Builder().addInterceptor(interceptor).build() }, maxBodyBytes)

    @Test
    fun aDocument() =
        runTest {
            assertEquals("""{"names":{}}""", fetcher(answering(200, """{"names":{}}""")).fetch(url))
        }

    @Test
    fun aStatusSaysWhichOne() =
        runTest {
            for (code in listOf(404, 410, 403, 301, 503)) {
                val e = assertFailsWith<Nip05FetchException.Status> { fetcher(answering(code)).fetch(url) }
                assertEquals(code, e.code)
            }
        }

    @Test
    fun aDocumentOverTheCapIsNotRead() =
        runTest {
            assertEquals("x".repeat(100), fetcher(answering(200, "x".repeat(100)), maxBodyBytes = 100).fetch(url))
            val e = assertFailsWith<Nip05FetchException.TooLarge> { fetcher(answering(200, "x".repeat(101)), maxBodyBytes = 100).fetch(url) }
            assertEquals(100, e.limitBytes)
        }

    @Test
    fun noAnswerIsUnreachable() =
        runTest {
            val e = assertFailsWith<Nip05FetchException.Unreachable> { fetcher(Interceptor { throw IOException("timeout") }).fetch(url) }
            // Coroutine stack-trace recovery may wrap it in a copy of itself; the IOException is below.
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is IOException })
        }

    @Test
    fun aNonPublicHostIsRefused() =
        runTest {
            val guarded = OkHttpNip05Fetcher({ OkHttpClient.Builder().publicAddressesOnly(direct = true).build() })
            assertFailsWith<Nip05FetchException.Refused> { guarded.fetch("https://127.0.0.1/.well-known/nostr.json?name=bob") }
        }

    @Test
    fun aCapThatCannotBeReadIsRejectedUpFront() {
        assertFailsWith<IllegalArgumentException> { OkHttpNip05Fetcher({ OkHttpClient() }, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { OkHttpNip05Fetcher({ OkHttpClient() }, -1) }
    }

    @Test
    fun theFailuresAreStillIllegalStateExceptions() =
        runTest {
            // What a failed fetch threw before, so existing catch blocks keep working.
            assertFailsWith<IllegalStateException> { fetcher(answering(404)).fetch(url) }
        }

    @Test
    fun nip05ClientLoadsADocumentWithExtraFields() =
        runTest {
            val client = Nip05Client(fetcher(answering(200, """{"names":{"bob":"abc"},"nip46":{}}""")))
            assertEquals(mapOf("bob" to "abc"), client.load(Nip05Id.parse("bob@domain.example")!!).names)
        }
}
