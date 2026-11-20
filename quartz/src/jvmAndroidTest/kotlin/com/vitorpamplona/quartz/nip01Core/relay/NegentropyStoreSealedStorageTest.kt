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

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.negentropy.storage.PrefixSumStorageVector
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.negentropyReconcileIds
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.testing.RelayClientTest
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.TestRelay
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A relay answers NEG-OPEN with the storage its store builds, so a store that streams its ids is used as one. */
class NegentropyStoreSealedStorageTest : RelayClientTest() {
    private fun events(seeds: IntProgression): List<Event> = seeds.map { SyntheticEvents.fakeEvent(idSeed = it, kind = 1, createdAt = it.toLong()) }

    /** Builds the storage itself, as a streaming store would, and counts which path the relay took. */
    private class StreamingStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        val built = AtomicInteger()
        val listed = AtomicInteger()

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
            onProgress: ((collected: Int) -> Unit)?,
        ): List<IdAndTime> {
            listed.incrementAndGet()
            return inner.snapshotIdsForNegentropy(filters, maxEntries, onProgress)
        }

        override suspend fun sealedNegentropyStorage(
            filters: List<Filter>,
            maxEntries: Int,
        ): IStorage? {
            built.incrementAndGet()
            val storage = PrefixSumStorageVector()
            var n = 0
            inner.query<Event>(filters) { event ->
                storage.insert(event.createdAt, event.id)
                n++
            }
            return if (n > maxEntries) null else storage.also { it.seal() }
        }
    }

    @Test
    fun theRelayReconcilesAgainstTheStorageItsStoreBuilds() =
        runBlocking {
            val store = StreamingStore(EventStore(dbName = null, relay = defaultRelayUrl, indexStrategy = TestRelay.RelayIndexing))
            val relay = hub.register(TestRelay(defaultRelayUrl, store = store))
            val shared = events(1..300)
            val onRelay = events(1001..1040)
            val local = events(2001..2025)
            relay.preload(shared + onRelay)

            val diff =
                withTimeout(30_000) {
                    client.negentropyReconcileIds(
                        relay = defaultRelayUrl,
                        filter = Filter(kinds = listOf(1)),
                        localEntries = (shared + local).map { IdAndTime(it.createdAt, it.id) },
                    )
                }

            assertEquals(onRelay.map { it.id }.toSet(), diff.needIds.toSet())
            assertEquals(local.map { it.id }.toSet(), diff.haveIds.toSet())
            assertTrue(store.built.get() >= 1, "the relay asked the store for the sealed storage")
            assertEquals(0, store.listed.get(), "and never for the id list")
        }

    @Test
    fun theDefaultSealsTheListAndRefusesPastTheCap() =
        runBlocking {
            val store = EventStore(dbName = null, relay = defaultRelayUrl, indexStrategy = TestRelay.RelayIndexing)
            events(1..50).forEach { store.insert(it) }

            assertEquals(50, store.sealedNegentropyStorage(listOf(Filter(kinds = listOf(1))), maxEntries = 50)!!.size())
            assertNull(store.sealedNegentropyStorage(listOf(Filter(kinds = listOf(1))), maxEntries = 49))
        }

    /** A store on the interface's default snapshot, as a store that overrides neither method is. */
    private class DefaultSnapshotStore(
        inner: IEventStore,
    ) : IEventStore by inner {
        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
            onProgress: ((collected: Int) -> Unit)?,
        ): List<IdAndTime> = super<IEventStore>.snapshotIdsForNegentropy(filters, maxEntries, onProgress)

        override suspend fun sealedNegentropyStorage(
            filters: List<Filter>,
            maxEntries: Int,
        ): IStorage? = super<IEventStore>.sealedNegentropyStorage(filters, maxEntries)
    }

    @Test
    fun theDefaultsTakeACapAtIntMaxValueAsNoCap() =
        runBlocking {
            val sqlite = EventStore(dbName = null, relay = defaultRelayUrl, indexStrategy = TestRelay.RelayIndexing)
            events(1..50).forEach { sqlite.insert(it) }
            val store = DefaultSnapshotStore(sqlite)

            assertEquals(50, store.snapshotIdsForNegentropy(listOf(Filter(kinds = listOf(1))), Int.MAX_VALUE).size)
            assertEquals(50, store.sealedNegentropyStorage(listOf(Filter(kinds = listOf(1))), Int.MAX_VALUE)!!.size())
            assertEquals(11, store.snapshotIdsForNegentropy(listOf(Filter(kinds = listOf(1))), 10).size)
        }
}
