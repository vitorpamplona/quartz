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

import com.vitorpamplona.quartz.utils.okhttp.NonPublicAddressException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException

/**
 * Fetches NIP-05 documents with OkHttp. Redirects are never followed (NIP-05 forbids it), and
 * the body is read only up to [maxBodyBytes]: a `nostr.json` is a text file anyone can serve, and
 * reading it whole would let a hostile domain stream gigabytes into memory. Every failure is a
 * [Nip05FetchException].
 *
 * To fetch only from public addresses, build the client with
 * [publicAddressesOnly][com.vitorpamplona.quartz.utils.okhttp.publicAddressesOnly].
 * Its refusals surface as [Nip05FetchException.Refused].
 */
class OkHttpNip05Fetcher
    @JvmOverloads
    constructor(
        val okHttpClient: (String) -> OkHttpClient,
        val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    ) : Nip05Fetcher {
        init {
            // The read asks for one byte past the cap.
            require(maxBodyBytes in 0 until Long.MAX_VALUE) { "maxBodyBytes out of range: $maxBodyBytes" }
        }

        override suspend fun fetch(url: String): String =
            withContext(Dispatchers.IO) {
                val request =
                    try {
                        Request.Builder().url(url).build()
                    } catch (e: IllegalArgumentException) {
                        throw Nip05FetchException.Refused("not an http(s) URL: $url", e)
                    }

                // Fetchers MUST ignore any HTTP redirects given by the /.well-known/nostr.json endpoint.
                val client = okHttpClient(url).newBuilder().followRedirects(false).build()

                try {
                    // Already on Dispatchers.IO, where the body's blocking reads belong.
                    client.newCall(request).executeAsync().use { response ->
                        if (!response.isSuccessful) throw Nip05FetchException.Status(response.code)
                        // OkHttp decompresses gzip transparently, so this caps the inflated size too.
                        val source = response.body.source()
                        if (source.request(maxBodyBytes + 1)) throw Nip05FetchException.TooLarge(maxBodyBytes)
                        source.buffer.readUtf8()
                    }
                } catch (e: NonPublicAddressException) {
                    throw Nip05FetchException.Refused(e.message ?: "non-public address", e)
                } catch (e: IOException) {
                    throw Nip05FetchException.Unreachable(e)
                }
            }

        companion object {
            /** Large enough for a busy domain's whole name list, small enough to hold in memory. */
            const val DEFAULT_MAX_BODY_BYTES = 2L * 1024 * 1024
        }
    }
