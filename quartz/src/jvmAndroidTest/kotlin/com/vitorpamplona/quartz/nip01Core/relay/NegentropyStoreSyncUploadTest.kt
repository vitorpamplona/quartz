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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.NegentropyStoreSync
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.testing.RelayClientTest
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.TestRelay
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The upstream-push options on [NegentropyStoreSync.Config]: a windowed, deadline-bounded upload,
 * uploads deferred until the NIP-77 session closes, and one id snapshot shared across relays.
 */
class NegentropyStoreSyncUploadTest : RelayClientTest() {
    private val kind1 = Filter(kinds = listOf(1))

    /** A relay store that takes every EVENT and never finishes writing it: no OK ever comes back. */
    private class NeverOkStore(
        private val inner: IEventStore,
    ) : IEventStore by inner {
        override suspend fun insert(event: Event): Unit = awaitCancellation()

        override suspend fun batchInsert(events: List<Event>): List<IEventStore.InsertOutcome> = awaitCancellation()
    }

    /** Counts the id snapshots taken of the local store. */
    private class CountingStore(
        private val inner: IEventStore,
    ) : IEventStore by inner {
        val snapshots = AtomicInteger()

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
            onProgress: ((Int) -> Unit)?,
        ): List<IdAndTime> {
            snapshots.incrementAndGet()
            return inner.snapshotIdsForNegentropy(filters, maxEntries, onProgress)
        }
    }

    private suspend fun localStore(count: Int): EventStore = EventStore(null).also { store -> SyntheticEvents.batch(count, kind = 1).forEach { store.insert(it) } }

    @Test
    fun aDeadlineAndAWindowEndAPushToARelayThatNeverOks() =
        runBlocking {
            val url = RelayUrlNormalizer.normalize("ws://never-ok/")
            hub.register(TestRelay(url, store = NeverOkStore(EventStore(dbName = null, relay = url))))
            val local = localStore(30)
            val sync =
                NegentropyStoreSync(
                    client,
                    local,
                    NegentropyStoreSync.Config(
                        down = false,
                        up = true,
                        syncDeletions = false,
                        pageFallback = false,
                        idleTimeoutMs = 1_000,
                        uploadWindow = 8,
                        uploadDeadlineMs = 3_000,
                    ),
                )

            val started = TimeSource.Monotonic.markNow()
            val result = withTimeout(20_000) { sync.syncGroup(url, kind1) }

            // One-at-a-time with the default 15 s OK wait would have taken 30 × 15 s.
            assertTrue(started.elapsedNow().inWholeSeconds < 10, "took ${started.elapsedNow()}")
            assertNull(result.error)
            assertEquals(30, result.have)
            assertEquals(0, result.uploaded)
            assertEquals(30, result.uploadsLeft)
        }

    @Test
    fun uploadsDeferredPastTheSessionAllArrive() =
        runBlocking {
            val local = localStore(40)
            val sync =
                NegentropyStoreSync(
                    client,
                    local,
                    NegentropyStoreSync.Config(down = false, up = true, syncDeletions = false, idChunk = 7, uploadAfterReconcile = true, uploadWindow = 16),
                )

            val result = withTimeout(20_000) { sync.syncGroup(defaultRelayUrl, kind1) }

            assertNull(result.error)
            assertEquals(40, result.uploaded)
            assertEquals(0, result.uploadsLeft)
            assertEquals(40, defaultRelay.store.count(kind1))
        }

    @Test
    fun relaysSyncingOneFilterShareOneSnapshot() =
        runBlocking {
            val relays = listOf("ws://relay-a/", "ws://relay-b/", "ws://relay-c/").map(RelayUrlNormalizer::normalize)
            relays.forEachIndexed { i, url -> hub.getOrCreate(url).preload(SyntheticEvents.fakeEvent(idSeed = 100 + i)) }

            suspend fun snapshotsTaken(share: Boolean): Int {
                val local = CountingStore(localStore(5))
                val config = NegentropyStoreSync.Config(syncDeletions = false, shareSnapshots = share)
                val results = withTimeout(20_000) { NegentropyStoreSync(client, local, config).sync(relays.associateWith { listOf(kind1) }) }
                assertTrue(results.all { it.error == null && it.need == 1 }, results.joinToString { "${it.relay.url}: ${it.error} ${it.need}" })
                return local.snapshots.get()
            }

            assertEquals(3, snapshotsTaken(share = false))
            assertEquals(1, snapshotsTaken(share = true))
        }
}
