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

import androidx.collection.LruCache
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * TTL cache around any [Nip11Fetcher]. NIP-11 documents change rarely, so a
 * successful fetch is served from memory for [ttlSeconds]; a FAILED fetch is
 * also remembered — for the shorter [errorTtlSeconds] — so a mass census does
 * not hammer a host that just refused, while still retrying it soon.
 *
 * Concurrent fetches of a relay that is not cached share one network fetch: a
 * census that asks for the same relay from many places at once (every feed that
 * shows it) used to send one request per caller. A caller cancelled while it waits
 * stops waiting; one cancelled while it leads the fetch hands it to the next caller.
 */
class CachedNip11Fetcher(
    private val delegate: Nip11Fetcher,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val errorTtlSeconds: Long = DEFAULT_ERROR_TTL_SECONDS,
    maxEntries: Int = 1000,
    private val now: () -> Long = { TimeUtils.now() },
) : Nip11Fetcher {
    private sealed interface Cached {
        val at: Long
    }

    private class Hit(
        val info: Nip11RelayInformation,
        override val at: Long,
    ) : Cached

    private class Miss(
        val message: String?,
        override val at: Long,
    ) : Cached

    private val cache = LruCache<NormalizedRelayUrl, Cached>(maxEntries)

    /** Fetches in flight, per relay; the others asking for one wait on it. Under [lock]. */
    private val inFlight = HashMap<NormalizedRelayUrl, CompletableDeferred<Result<Nip11RelayInformation>>>()
    private val lock = PlatformLock()

    /** The cached document if present and fresh; null otherwise. Never touches the network. */
    fun cachedOrNull(relay: NormalizedRelayUrl): Nip11RelayInformation? {
        val hit = cache[relay] as? Hit ?: return null
        return if (now() - hit.at < ttlSeconds) hit.info else null
    }

    /** Drops the cache entry (success or failure) so the next [fetch] is fresh. */
    fun invalidate(relay: NormalizedRelayUrl) {
        cache.remove(relay)
    }

    override suspend fun fetch(relay: NormalizedRelayUrl): Nip11RelayInformation {
        when (val cached = cache[relay]) {
            is Hit -> if (now() - cached.at < ttlSeconds) return cached.info
            is Miss ->
                if (now() - cached.at < errorTtlSeconds) {
                    throw Nip11FetchException(cached.message ?: "cached NIP-11 failure for ${relay.url}")
                }
            null -> {}
        }
        while (true) {
            var leads = false
            val shared =
                lock.withLock {
                    inFlight[relay] ?: CompletableDeferred<Result<Nip11RelayInformation>>().also {
                        inFlight[relay] = it
                        leads = true
                    }
                }
            if (!leads) {
                // A leader that was cancelled completes with no result: ask again, maybe leading.
                val result = shared.await()
                result.getOrNull()?.let { return it }
                val error = result.exceptionOrNull()
                if (error == null || error is CancellationException) continue
                throw error
            }
            try {
                val info = delegate.fetch(relay)
                cache.put(relay, Hit(info, now()))
                shared.complete(Result.success(info))
                return info
            } catch (e: Exception) {
                if (e is CancellationException) {
                    shared.complete(Result.failure(e))
                    throw e
                }
                cache.put(relay, Miss(e.message, now()))
                shared.complete(Result.failure(e))
                throw e
            } finally {
                lock.withLock { if (inFlight[relay] === shared) inFlight.remove(relay) }
            }
        }
    }

    companion object {
        /** Documents are near-static: trust a success for a day. */
        const val DEFAULT_TTL_SECONDS = 24L * 60 * 60

        /** Failures are often transient: retry after five minutes. */
        const val DEFAULT_ERROR_TTL_SECONDS = 5L * 60
    }
}
