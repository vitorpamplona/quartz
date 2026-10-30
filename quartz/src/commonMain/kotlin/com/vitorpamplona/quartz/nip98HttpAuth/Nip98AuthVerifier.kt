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
package com.vitorpamplona.quartz.nip98HttpAuth

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

/**
 * Server-side counterpart to [HTTPAuthorizationEvent]. Verifies a
 * NIP-98 `Authorization: Nostr <base64-event>` header.
 *
 * NIP-98 reuses kind 27235 events with `u`, `method`, and (for bodies)
 * `payload` tags. Verification checks:
 *  1. Header is `Nostr <base64>`.
 *  2. Decoded body is a kind-27235 event with a valid Schnorr signature.
 *  3. The event's `created_at` is within ±[toleranceSeconds] of now.
 *  4. The `method` tag matches the HTTP method.
 *  5. The `u` tag matches the requested URL.
 *  6. If a body is present, the `payload` tag matches `sha256(body)` hex.
 *  7. The event id has not been accepted before (single use).
 *
 * [preverify] runs every check that needs no body (1–5) without recording
 * the token, so an endpoint can refuse before it reads a body; [verify]
 * with `acceptsAuthor` keeps the accepted authors' replay set out of
 * reach of anyone else's tokens.
 *
 * Returns the verified pubkey on success; a [Result.Malformed] /
 * [Result.Missing] otherwise (the caller turns these into 401/403).
 */
class Nip98AuthVerifier(
    private val now: () -> Long = { TimeUtils.now() },
    /** Allowed clock skew in seconds. NIP-98 says 60. */
    private val toleranceSeconds: Long = 60,
) {
    /**
     * Recently-accepted event ids → expiry epoch second, one map per trust
     * class: [acceptedIds] for tokens whose author the caller accepts,
     * [otherIds] for every other author. Each is bounded to
     * [MAX_REPLAY_ENTRIES] (insertion-order eviction); each entry expires
     * after `2 × toleranceSeconds` (twice the accepted window so a token
     * can't be reused by an attacker who buffers across the boundary).
     *
     * Two maps because a bounded FIFO can be rolled over: anyone can sign
     * a valid kind-27235 event, so with one map a stranger posting
     * [MAX_REPLAY_ENTRIES] fresh tokens would evict an admin's id and make
     * that admin's captured token replayable. Strangers' ids only ever
     * evict strangers' ids, whose requests the caller refuses anyway.
     *
     * Guarded by [seenLock] so the eviction sweep + insertion are
     * atomic. We use a coroutine [Mutex] so the type works in KMP
     * commonMain (no `synchronized` block).
     */
    private val acceptedIds: LinkedHashMap<String, Long> = LinkedHashMap()
    private val otherIds: LinkedHashMap<String, Long> = LinkedHashMap()

    private val seenLock = Mutex()

    /**
     * Verifies [authorizationHeader] for a request of [method] on [url] whose body is [body]
     * (null or empty: no `payload` tag is checked), and records the token so it is accepted once.
     *
     * Every author shares one replay set here, so a flood of valid tokens from anyone can evict a
     * recorded id. An endpoint that admits only some authors (an admin API) should call the
     * overload taking `acceptsAuthor`.
     */
    suspend fun verify(
        authorizationHeader: String?,
        method: String,
        url: String,
        body: ByteArray?,
    ): Result = verify(authorizationHeader, method, url, body, ACCEPT_EVERY_AUTHOR)

    /**
     * Verifies [authorizationHeader] for a request of [method] on [url] whose body is [body]
     * (null or empty: no `payload` tag is checked), and records the token so it is accepted once.
     *
     * [acceptsAuthor] says which authors this endpoint admits (for an admin API, the admin list).
     * It does not change the answer: a valid token from any author is [Result.Verified] with its
     * pubkey, and refusing a verified stranger (a 403) stays the caller's job. It decides where the
     * token's id is remembered: accepted authors' ids in a replay set no other author's token can
     * evict, everyone else's in a separate one, so a stranger signing fresh tokens cannot roll an
     * admin's used token out of memory and replay it. Both sets are single-use within their bound.
     */
    suspend fun verify(
        authorizationHeader: String?,
        method: String,
        url: String,
        body: ByteArray?,
        acceptsAuthor: (HexKey) -> Boolean,
    ): Result {
        val nowSec = now()
        val auth =
            when (val decoded = checkHeader(authorizationHeader, method, url, nowSec)) {
                is Checked.Refused -> return decoded.result
                is Checked.Token -> decoded.event
            }

        if (body != null && body.isNotEmpty()) {
            val expected = sha256(body).toHexKey()
            if (auth.payloadHash() != expected) {
                return Result.Malformed("payload hash mismatch")
            }
        }

        // Replay check — done LAST so we don't burn a one-shot id on a
        // request that would otherwise have failed signature/url/etc.
        val seen = if (acceptsAuthor(auth.pubKey)) acceptedIds else otherIds
        val expiry = nowSec + 2 * toleranceSeconds
        seenLock.withLock {
            // Evict expired entries while we hold the lock. Insertion
            // order (LinkedHashMap default) tracks expiry order
            // because every entry's expiry = now + 2·tolerance, so
            // the first non-expired entry guarantees no later entry
            // is expired either.
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value <= nowSec) it.remove() else break
            }
            if (seen.put(auth.id, expiry) != null) {
                return Result.Malformed("replay: this NIP-98 token has already been used")
            }
            // Cap entries: drop oldest by insertion order. Equivalent to
            // the JDK LinkedHashMap.removeEldestEntry hook we used before,
            // but works in KMP commonMain.
            while (seen.size > MAX_REPLAY_ENTRIES) {
                val eldest = seen.keys.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }

        return Result.Verified(auth.pubKey)
    }

    /**
     * Every check [verify] makes that needs no body, and nothing it remembers: the `Nostr <base64>`
     * scheme, a kind-27235 event, `created_at` within the tolerance, the `method` and `u` tags, and
     * the signature. The `payload` hash and the replay check are left to [verify], so this neither
     * spends the token nor proves it is fresh, and a [Result.Verified] here is only permission to
     * read the body: the request is authorized once [verify] accepts it with that body.
     *
     * Use it before reading a request body, to refuse a stranger or a stale, forged or misbound
     * token without buffering a byte. NIP-98 binds a token to the body's sha256, so without it an
     * endpoint has to read the whole body before it can refuse anyone. The cheap checks run first
     * and the Schnorr signature last.
     */
    fun preverify(
        authorizationHeader: String?,
        method: String,
        url: String,
    ): Result =
        when (val decoded = checkHeader(authorizationHeader, method, url, now())) {
            is Checked.Refused -> decoded.result
            is Checked.Token -> Result.Verified(decoded.event.pubKey)
        }

    private sealed interface Checked {
        class Token(
            val event: HTTPAuthorizationEvent,
        ) : Checked

        class Refused(
            val result: Result,
        ) : Checked
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun checkHeader(
        authorizationHeader: String?,
        method: String,
        url: String,
        nowSec: Long,
    ): Checked {
        if (authorizationHeader.isNullOrBlank()) return Checked.Refused(Result.Missing)
        if (!authorizationHeader.startsWith(SCHEME)) return refused("expected '$SCHEME <base64>' header")

        val token = authorizationHeader.substring(SCHEME.length).trim()
        val json =
            try {
                Base64.decode(token).decodeToString()
            } catch (_: IllegalArgumentException) {
                return refused("token is not valid base64")
            }

        val event =
            try {
                OptimizedJsonMapper.fromJson(json)
            } catch (_: Exception) {
                return refused("token does not decode to a Nostr event")
            }

        if (event.kind != HTTPAuthorizationEvent.KIND) {
            return refused("event kind ${event.kind} != ${HTTPAuthorizationEvent.KIND}")
        }

        val skew = abs(event.createdAt - nowSec)
        if (skew > toleranceSeconds) {
            return refused("created_at is ${skew}s away from now (max ${toleranceSeconds}s)")
        }

        // Re-wrap as the typed event so the tag accessors work.
        val auth =
            HTTPAuthorizationEvent(
                event.id,
                event.pubKey,
                event.createdAt,
                event.tags,
                event.content,
                event.sig,
            )

        if (!auth.method().equals(method, ignoreCase = true)) {
            return refused("method mismatch: expected $method, got ${auth.method()}")
        }
        if (auth.url() != url) {
            return refused("url mismatch: expected $url, got ${auth.url()}")
        }

        // Last: the only check that costs a Schnorr verification.
        if (!event.verify()) return refused("bad event signature or id")

        return Checked.Token(auth)
    }

    private fun refused(reason: String) = Checked.Refused(Result.Malformed(reason))

    sealed interface Result {
        data class Verified(
            val pubkey: HexKey,
        ) : Result

        object Missing : Result

        data class Malformed(
            val reason: String,
        ) : Result
    }

    companion object {
        const val SCHEME = "Nostr "

        /**
         * Cap on the in-memory replay-cache size. With a 60s tolerance
         * an attacker would need to push >MAX/120 verified requests per
         * second (one new id per ~120 ms) to evict legitimate entries.
         * 1024 is generous for an admin endpoint.
         */
        const val MAX_REPLAY_ENTRIES = 1024

        private val ACCEPT_EVERY_AUTHOR: (HexKey) -> Boolean = { true }
    }
}
