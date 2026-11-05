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

import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip98HttpAuth.HTTPAuthorizationEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `Authorization` header a GRASP server asks for on a private repository's git endpoints
 * (GRASP-08): `Nostr <base64 of a kind-27235 event>` with `["u", <the repository URL>]` and
 * `["method", "GET"]` (GET for every request, fetch and push alike, and no payload hash). One event
 * serves every endpoint of the repository and is reused for [reuseSeconds], inside the server's
 * ±60 s window, so a fetch does not ask the signer once per request.
 */
open class GitHttpAuth(
    private val signer: NostrSigner,
    private val reuseSeconds: Long = 55,
) {
    private val lock = Mutex()
    private val cache = HashMap<String, Pair<Long, String>>()

    /** The header for [cloneUrl], or null for a repository that needs none (an override can tell them apart). */
    open suspend fun header(cloneUrl: String): String? {
        val repository = repositoryUrl(cloneUrl)
        // Signed under the lock, so requests that arrive together (a fetch's several endpoints)
        // ask the signer once, not once each: a remote signer would prompt for every one.
        return lock.withLock {
            val now = TimeUtils.now()
            cache[repository]?.let { (signedAt, header) -> if (now - signedAt < reuseSeconds) return@withLock header }
            cache.entries.removeAll { now - it.value.first >= reuseSeconds }
            signer.sign(HTTPAuthorizationEvent.build(repository, "GET")).toAuthToken().also { cache[repository] = now to it }
        }
    }

    companion object {
        /** The repository URL as GRASP-08 signs it: no query, fragment, user info or trailing slash. */
        fun repositoryUrl(cloneUrl: String): String {
            val trimmed = cloneUrl.trim().substringBefore('#').substringBefore('?')
            val scheme = trimmed.substringBefore("://", "")
            val rest = if (scheme.isEmpty()) trimmed else trimmed.substringAfter("://")
            val authority = rest.substringBefore('/').substringAfterLast('@')
            val path = rest.substringAfter('/', "")
            return ((if (scheme.isEmpty()) "" else "$scheme://") + authority + (if (path.isEmpty()) "" else "/$path")).trimEnd('/')
        }
    }
}
