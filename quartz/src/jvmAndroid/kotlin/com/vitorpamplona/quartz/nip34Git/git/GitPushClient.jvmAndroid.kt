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

actual class GitPushClient actual constructor(
    http: GitHttpTransport,
    agent: String,
    authorization: GitHttpAuth?,
) {
    private val client = GitReceivePackClient(http, agent, authorization)

    @Deprecated(
        "Use GitPushClient(OkHttpGitTransport(okHttpClient), agent) instead. Removed in 3.0.0.",
        ReplaceWith("GitPushClient(OkHttpGitTransport(okHttpClient), agent)"),
    )
    constructor(okHttpClient: (String) -> OkHttpClient, agent: String = "quartz") : this(OkHttpGitTransport(okHttpClient), agent, null)

    @Deprecated(
        "Use GitPushClient(OkHttpGitTransport(okHttpClient), agent, authorization) instead. Removed in 3.0.0.",
        ReplaceWith("GitPushClient(OkHttpGitTransport(okHttpClient), agent, authorization)"),
    )
    constructor(okHttpClient: (String) -> OkHttpClient, agent: String, authorization: GitHttpAuth?) : this(OkHttpGitTransport(okHttpClient), agent, authorization)

    actual suspend fun advertisement(cloneUrl: String): GitReceivePackAdvertisement = client.advertisement(cloneUrl)

    actual suspend fun push(
        cloneUrl: String,
        updates: List<GitRefUpdate>,
        objects: Collection<GitObject>,
        advertisement: GitReceivePackAdvertisement?,
    ): GitPushReport = client.push(cloneUrl, updates, objects, advertisement)

    /** Empty: kept so the JVM class still has the `Companion` it had before it moved to common code. */
    @Deprecated("Holds nothing. Removed in 3.0.0.")
    companion object
}
