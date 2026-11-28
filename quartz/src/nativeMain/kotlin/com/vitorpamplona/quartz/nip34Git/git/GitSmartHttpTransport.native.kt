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

actual class GitSmartHttpTransport actual constructor(
    http: GitHttpTransport,
    authorization: GitHttpAuth?,
) {
    private val smart = GitSmartHttp(http, authorization)

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
}
