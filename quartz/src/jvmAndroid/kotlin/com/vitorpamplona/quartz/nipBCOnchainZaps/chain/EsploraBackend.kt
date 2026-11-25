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

import okhttp3.OkHttpClient

/**
 * An [EsploraClient] over one OkHttp client, kept so code compiled against it still links.
 *
 * @param baseUrl Function returning the base URL with trailing slash stripped
 *                (e.g. "https://mempool.space/api").
 * @param client Shared OkHttp client.
 */
@Deprecated(
    "Use EsploraClient with an OkHttpEsploraTransport instead. Removed in 3.0.0.",
    ReplaceWith("EsploraClient(baseUrl, OkHttpEsploraTransport { client })"),
)
class EsploraBackend(
    baseUrl: () -> String,
    client: OkHttpClient,
) : OnchainBackend by EsploraClient(baseUrl, OkHttpEsploraTransport { client })
