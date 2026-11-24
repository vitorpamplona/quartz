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

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachedNip11FetcherTest {
    private val relay = RelayUrlNormalizer.normalize("wss://nostr.example.com")

    /** Counts network hits; serves [doc] or throws when [failing]. */
    private class FakeFetcher : Nip11Fetcher {
        var calls = 0
        var failing = false
        var doc = Nip11RelayInformation(name = "v1")

        override suspend fun fetch(relay: NormalizedRelayUrl): Nip11RelayInformation {
            calls++
            if (failing) throw Nip11FetchException("boom")
            return doc
        }
    }

    private fun cached(
        delegate: FakeFetcher,
        clock: () -> Long,
    ) = CachedNip11Fetcher(delegate, ttlSeconds = 100, errorTtlSeconds = 10, now = clock)

    @Test
    fun freshSuccessIsServedFromCache() =
        runBlocking {
            val net = FakeFetcher()
            var now = 0L
            val fetcher = cached(net) { now }

            assertEquals("v1", fetcher.fetch(relay).name)
            now = 99
            assertEquals("v1", fetcher.fetch(relay).name)
            assertEquals(1, net.calls, "second fetch inside the TTL must not touch the network")
        }

    @Test
    fun successExpiresAfterTtl() =
        runBlocking {
            val net = FakeFetcher()
            var now = 0L
            val fetcher = cached(net) { now }

            fetcher.fetch(relay)
            net.doc = Nip11RelayInformation(name = "v2")
            now = 100
            assertEquals("v2", fetcher.fetch(relay).name)
            assertEquals(2, net.calls)
        }

    @Test
    fun failureIsCachedForItsOwnShorterTtl() =
        runBlocking {
            val net = FakeFetcher().apply { failing = true }
            var now = 0L
            val fetcher = cached(net) { now }

            assertFailsWith<Nip11FetchException> { fetcher.fetch(relay) }
            now = 9
            assertFailsWith<Nip11FetchException> { fetcher.fetch(relay) }
            assertEquals(1, net.calls, "a fresh failure must be served from cache, not re-fetched")

            now = 10
            net.failing = false
            assertEquals("v1", fetcher.fetch(relay).name)
            assertEquals(2, net.calls, "an expired failure must be retried")
        }

    @Test
    fun invalidateForcesAFreshFetch() =
        runBlocking {
            val net = FakeFetcher()
            val fetcher = cached(net) { 0 }

            fetcher.fetch(relay)
            fetcher.invalidate(relay)
            fetcher.fetch(relay)
            assertEquals(2, net.calls)
        }

    @Test
    fun cachedOrNullNeverTouchesTheNetwork() =
        runBlocking {
            val net = FakeFetcher()
            var now = 0L
            val fetcher = cached(net) { now }

            assertNull(fetcher.cachedOrNull(relay))
            assertEquals(0, net.calls)

            fetcher.fetch(relay)
            assertEquals("v1", fetcher.cachedOrNull(relay)?.name)
            now = 100
            assertNull(fetcher.cachedOrNull(relay), "a stale hit must not be served")
            assertEquals(1, net.calls)
        }

    @Test
    fun concurrentFetchesOfOneRelayShareOneNetworkFetch() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val slow =
                object : Nip11Fetcher {
                    override suspend fun fetch(relay: NormalizedRelayUrl): Nip11RelayInformation {
                        calls++
                        gate.await()
                        return Nip11RelayInformation(name = "shared")
                    }
                }
            val fetcher = CachedNip11Fetcher(slow)
            val waiting = List(5) { async(start = CoroutineStart.UNDISPATCHED) { fetcher.fetch(relay) } }
            gate.complete(Unit)
            assertEquals(List(5) { "shared" }, waiting.awaitAll().map { it.name })
            assertEquals(1, calls, "one network fetch for five callers")
        }

    private class HostileDocumentError : Error("nesting too deep")

    @Test
    fun aLeaderThatFailsWithAnErrorDoesNotHangItsWaiters() =
        runBlocking {
            // An Error, not an Exception: a stack overflow on a hostile document's nesting.
            val gate = CompletableDeferred<Unit>()
            val hostile =
                object : Nip11Fetcher {
                    override suspend fun fetch(relay: NormalizedRelayUrl): Nip11RelayInformation {
                        gate.await()
                        throw HostileDocumentError()
                    }
                }
            val fetcher = CachedNip11Fetcher(hostile)
            supervisorScope {
                val leader = async(start = CoroutineStart.UNDISPATCHED) { fetcher.fetch(relay) }
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { fetcher.fetch(relay) }
                gate.complete(Unit)
                assertFailsWith<HostileDocumentError>("the leader rethrows the Error") { leader.await() }
                val outcome = withTimeoutOrNull(5_000) { runCatching { waiter.await() } }
                assertNotNull(outcome, "the waiter is not left hanging")
                assertTrue(outcome.isFailure, "the waiter fails with the leader")
            }
        }
}
