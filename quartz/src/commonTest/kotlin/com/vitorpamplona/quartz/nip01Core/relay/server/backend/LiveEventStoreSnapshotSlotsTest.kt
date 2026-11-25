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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.StoreQueryContext
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import com.vitorpamplona.quartz.nip77Negentropy.NegentropyServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The NEG-OPEN snapshot cache under a relay's real traffic: a steady write stream, routers
 * reconciling many windows, many callers, opens that arrive together. It used to hold one
 * snapshot, dropped by every accepted write and evicted by any other open, and built one per
 * concurrent open.
 *
 * A snapshot built by the store is a new [IStorage]; [Walks] counts the distinct ones handed out,
 * which is how many times the store walked its ids.
 */
class LiveEventStoreSnapshotSlotsTest {
    private val signer = NostrSignerSync()
    private val other = NostrSignerSync()

    private class As(
        user: HexKey,
    ) : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = setOf(user)
    }

    /** Writes each event to [store] and reports what the store decided, as the relay's ingest does. */
    private class StoreIngest(
        private val store: IEventStore,
    ) : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(store.batchInsert(listOf(event)).single())

        override fun hasBacklog() = false

        override fun close() {}
    }

    /** Distinct storages handed out, by identity: each is one walk of the store. */
    private class Walks {
        private val seen = ArrayList<IStorage>()

        fun count(storage: IStorage?): IStorage? {
            if (storage != null && seen.none { it === storage }) seen.add(storage)
            return storage
        }

        val walks get() = seen.size
    }

    /**
     * Any store that is not Quartz's SQLite one. Counts its walks, records the caller each ran
     * as, and can hold a walk at [gate] after reading its ids, or fail it.
     */
    private class Slow(
        private val inner: IEventStore,
    ) : IEventStore by inner {
        var walks = 0
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null

        /** The gate of the walk at each index, in place of [gate]. */
        val gates = ArrayList<CompletableDeferred<Unit>>()
        var fail: Exception? = null
        val callers = ArrayList<HexKey?>()

        override suspend fun sealedNegentropyStorage(
            filters: List<Filter>,
            maxEntries: Int,
        ): IStorage? {
            val myGate = gates.getOrNull(walks) ?: gate
            walks++
            callers.add(currentCoroutineContext()[StoreQueryContext]?.observer)
            val read = inner.sealedNegentropyStorage(filters, maxEntries)
            try {
                myGate?.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            fail?.let { throw it }
            return read
        }
    }

    private val kind1 = listOf(Filter(kinds = listOf(1)))

    private fun note(
        text: String,
        at: Long,
        by: NostrSignerSync = signer,
    ): Event = by.sign(TextNoteEvent.build(text, createdAt = at))

    private fun reaction(at: Long): Event = signer.sign<Event>(at, 7, emptyArray(), "+")

    // ------------------------------------------------------------------
    // Slots: the audit's three scenarios
    // ------------------------------------------------------------------

    @Test
    fun twoIdenticalOpensWithNoWriteBetweenWalkOnce() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            val live = LiveEventStore(store, StoreIngest(store))
            val walks = Walks()

            walks.count(live.sealedNegentropyStorage(kind1, 100))
            walks.count(live.sealedNegentropyStorage(kind1, 100))
            assertEquals(1, walks.walks)
            store.close()
        }

    @Test
    fun aWriteNoCachedFilterMatchesKeepsTheSnapshot() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            val live = LiveEventStore(store, StoreIngest(store))
            val walks = Walks()

            walks.count(live.sealedNegentropyStorage(kind1, 100))
            live.insert(reaction(1_001))
            walks.count(live.sealedNegentropyStorage(kind1, 100))
            assertEquals(1, walks.walks, "a reaction is not in a kind-1 snapshot, so it does not drop it")
            store.close()
        }

    @Test
    fun aWriteACachedFilterMatchesRebuildsItWithTheWrite() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            val live = LiveEventStore(store, StoreIngest(store))

            assertEquals(1, live.sealedNegentropyStorage(kind1, 100)!!.size())
            live.insert(note("b", 1_001))
            assertEquals(2, live.sealedNegentropyStorage(kind1, 100)!!.size(), "the next open sees the accepted note")
            store.close()
        }

    @Test
    fun windowedOpensKeepTheirOwnSlots() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            store.insert(note("b", 2_000))
            val live = LiveEventStore(store, StoreIngest(store))
            val early = listOf(Filter(kinds = listOf(1), until = 1_500))
            val late = listOf(Filter(kinds = listOf(1), since = 1_501))
            val walks = Walks()

            val first = walks.count(live.sealedNegentropyStorage(early, 100))
            walks.count(live.sealedNegentropyStorage(late, 100))
            assertSame(first, walks.count(live.sealedNegentropyStorage(early, 100)), "another window does not evict this one")
            assertEquals(2, walks.walks)

            live.insert(note("c", 3_000))
            assertSame(first, live.sealedNegentropyStorage(early, 100), "a note after the early window leaves it cached")
            assertEquals(2, live.sealedNegentropyStorage(late, 100)!!.size(), "and the late window sees it")
            store.close()
        }

    @Test
    fun anotherCallersOpenDoesNotEvictAnObserverRelativeSnapshot() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner)
            val live = LiveEventStore(store, StoreIngest(store))
            val alice = As("a".repeat(64))
            val bob = As("b".repeat(64))

            val forAlice = live.sealedNegentropyStorage(alice, kind1, 100)
            live.sealedNegentropyStorage(bob, kind1, 100)
            assertSame(forAlice, live.sealedNegentropyStorage(alice, kind1, 100), "bob's open has its own slot")
            assertEquals(2, store.walks)
            inner.close()
        }

    // ------------------------------------------------------------------
    // Invalidation: every way an accepted write changes a set it does not match
    // ------------------------------------------------------------------

    @Test
    fun aNewerReplaceableOutsideTheWindowDropsTheOneInside() =
        runTest {
            val store = EventStore(null)
            store.insert(signer.sign<Event>(1_000, 0, emptyArray(), "{}"))
            val live = LiveEventStore(store, StoreIngest(store))
            val window = listOf(Filter(kinds = listOf(0), until = 1_500))

            assertEquals(1, live.sealedNegentropyStorage(window, 100)!!.size())
            live.insert(signer.sign<Event>(2_000, 0, emptyArray(), "{\"name\":\"new\"}"))
            assertEquals(0, live.sealedNegentropyStorage(window, 100)!!.size(), "the old version was superseded")
            store.close()
        }

    @Test
    fun aNewerAddressableOutsideTheWindowDropsTheOneInside() =
        runTest {
            val store = EventStore(null)
            store.insert(signer.sign<Event>(1_000, 30_023, arrayOf(arrayOf("d", "x")), "v1"))
            val live = LiveEventStore(store, StoreIngest(store))
            val window = listOf(Filter(kinds = listOf(30_023), until = 1_500))

            assertEquals(1, live.sealedNegentropyStorage(window, 100)!!.size())
            live.insert(signer.sign<Event>(2_000, 30_023, arrayOf(arrayOf("d", "x")), "v2"))
            assertEquals(0, live.sealedNegentropyStorage(window, 100)!!.size(), "the old version was superseded")
            store.close()
        }

    @Test
    fun aDeletionDropsWhatItDeleted() =
        runTest {
            val store = EventStore(null)
            val target = note("a", 1_000)
            store.insert(target)
            val live = LiveEventStore(store, StoreIngest(store))

            assertEquals(1, live.sealedNegentropyStorage(kind1, 100)!!.size())
            live.insert(signer.sign(DeletionRequestEvent.build(listOf(target))))
            assertEquals(0, live.sealedNegentropyStorage(kind1, 100)!!.size(), "the deleted note is gone")
            store.close()
        }

    @Test
    fun aDeletionByTheRecipientDropsTheGiftWrap() =
        runTest {
            val store = EventStore(null)
            val wrap = GiftWrapEvent.create(note("a", 1_000), signer.pubKey)
            store.insert(wrap)
            val live = LiveEventStore(store, StoreIngest(store))
            val wraps = listOf(Filter(kinds = listOf(GiftWrapEvent.KIND), tags = mapOf("p" to listOf(signer.pubKey))))

            assertEquals(1, live.sealedNegentropyStorage(wraps, 100)!!.size())
            live.insert(signer.sign(DeletionRequestEvent.build(listOf(wrap))))
            assertEquals(0, live.sealedNegentropyStorage(wraps, 100)!!.size(), "the recipient deleted the wrap")
            store.close()
        }

    @Test
    fun aVanishDropsTheAuthorsEventsAndTheirGiftWraps() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            store.insert(note("b", 1_000, other))
            val wrap = GiftWrapEvent.create(note("c", 1_000, other), signer.pubKey)
            store.insert(wrap)
            val live = LiveEventStore(store, StoreIngest(store))
            val wraps = listOf(Filter(kinds = listOf(GiftWrapEvent.KIND)))

            assertEquals(2, live.sealedNegentropyStorage(kind1, 100)!!.size())
            assertEquals(1, live.sealedNegentropyStorage(wraps, 100)!!.size())
            live.insert(signer.sign(RequestToVanishEvent.build("wss://quartz.local".normalizeRelayUrl())))
            assertEquals(1, live.sealedNegentropyStorage(kind1, 100)!!.size(), "the vanished author's note is gone")
            assertEquals(0, live.sealedNegentropyStorage(wraps, 100)!!.size(), "and so is the wrap addressed to them")
            store.close()
        }

    @Test
    fun aStoreThatMayGateDropsEverySnapshotOnAnyWrite() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner)
            val live = LiveEventStore(store, StoreIngest(store))

            val first = live.sealedNegentropyStorage(kind1, 100)
            live.insert(reaction(1_001))
            assertNotSame(first, live.sealedNegentropyStorage(kind1, 100), "a write may change what a gated store answers")
            inner.close()
        }

    @Test
    fun aStoreThatSaysWhichWritesFeedItsGateKeepsSnapshotsAcrossTheOthers() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner)
            val live = LiveEventStore(store, StoreIngest(store), 1_000, writeIsFilterLocal = { it.kind != 3 })

            val first = live.sealedNegentropyStorage(kind1, 100)
            live.insert(reaction(1_001))
            assertSame(first, live.sealedNegentropyStorage(kind1, 100), "a reaction feeds no gate here")
            live.insert(signer.sign<Event>(1_002, 3, arrayOf(arrayOf("p", other.pubKey)), ""))
            assertNotSame(first, live.sealedNegentropyStorage(kind1, 100), "a follow list may change who is trusted")
            inner.close()
        }

    @Test
    fun aThrowingWriteScopeDropsEverySnapshot() =
        runTest {
            val store = EventStore(null)
            store.insert(note("a", 1_000))
            val live = LiveEventStore(store, StoreIngest(store), 1_000, writeIsFilterLocal = { throw IllegalStateException("boom") })

            val first = live.sealedNegentropyStorage(kind1, 100)
            live.insert(reaction(1_001))
            assertNotSame(first, live.sealedNegentropyStorage(kind1, 100))
            store.close()
        }

    @Test
    fun aSearchSnapshotIsDroppedByAnyWrite() =
        runTest {
            val store = EventStore(null)
            store.insert(note("hello", 1_000))
            val live = LiveEventStore(store, StoreIngest(store))
            val search = listOf(Filter(kinds = listOf(1), search = "hello"))

            val first = live.sealedNegentropyStorage(search, 100)
            live.insert(reaction(1_001))
            assertNotSame(first, live.sealedNegentropyStorage(search, 100), "what a text engine matches is its own call")
            store.close()
        }

    // ------------------------------------------------------------------
    // Single flight
    // ------------------------------------------------------------------

    @Test
    fun concurrentIdenticalOpensShareOneWalk() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner).apply { gate = CompletableDeferred() }
            val live = LiveEventStore(store, StoreIngest(store))

            val a = async { live.sealedNegentropyStorage(kind1, 100) }
            val b = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            assertEquals(1, store.walks, "the second open waits for the first one's walk")
            store.gate!!.complete(Unit)
            assertSame(a.await(), b.await())
            inner.close()
        }

    @Test
    fun aFailedWalkFailsEveryWaiterAndIsNotCached() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner).apply { gate = CompletableDeferred() }
            val live = LiveEventStore(store, StoreIngest(store))
            store.fail = IllegalStateException("remote store unreachable")

            val a = async { runCatching { live.sealedNegentropyStorage(kind1, 100) } }
            val b = async { runCatching { live.sealedNegentropyStorage(kind1, 100) } }
            runCurrent()
            store.gate!!.complete(Unit)
            assertEquals("remote store unreachable", a.await().exceptionOrNull()?.message)
            assertEquals("remote store unreachable", b.await().exceptionOrNull()?.message)
            assertEquals(1, store.walks)

            store.fail = null
            assertNotNull(live.sealedNegentropyStorage(kind1, 100))
            assertEquals(2, store.walks, "the failure was not cached")
            inner.close()
        }

    @Test
    fun oneWaiterCancellingDoesNotCancelTheWalkForTheOthers() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner).apply { gate = CompletableDeferred() }
            val live = LiveEventStore(store, StoreIngest(store))

            val a = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            val b = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            a.cancel()
            runCurrent()
            assertEquals(0, store.cancelled, "b still waits on the walk a started")
            store.gate!!.complete(Unit)
            assertEquals(1, b.await()!!.size())
            assertEquals(1, store.walks)
            assertFailsWith<CancellationException> { a.await() }
            inner.close()
        }

    @Test
    fun theWalkStopsOnceEveryWaiterIsGone() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner).apply { gate = CompletableDeferred() }
            val live = LiveEventStore(store, StoreIngest(store))

            val a = async { live.sealedNegentropyStorage(kind1, 100) }
            val b = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            a.cancel()
            b.cancel()
            runCurrent()
            assertEquals(1, store.cancelled, "nobody waits for it any more")

            store.gate = null
            assertEquals(1, live.sealedNegentropyStorage(kind1, 100)!!.size(), "and the next open walks afresh")
            inner.close()
        }

    @Test
    fun anOpenAfterAMatchingWriteDoesNotJoinAWalkThatStartedBeforeIt() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner).apply { gate = CompletableDeferred() }
            val live = LiveEventStore(store, StoreIngest(store))

            val before = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            live.insert(note("b", 1_001))
            val after = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            store.gate!!.complete(Unit)

            assertEquals(1, before.await()!!.size(), "the walk read its ids before the write")
            assertEquals(2, after.await()!!.size(), "an open after the write's OK sees it")
            assertEquals(2, live.sealedNegentropyStorage(kind1, 100)!!.size(), "and the stale walk was not cached")
            inner.close()
        }

    @Test
    fun aStaleWalkFinishingLastDoesNotReplaceTheFreshSnapshot() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner)
            store.gates.add(CompletableDeferred())
            store.gates.add(CompletableDeferred())
            val live = LiveEventStore(store, StoreIngest(store))

            val before = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            live.insert(note("b", 1_001))
            val after = async { live.sealedNegentropyStorage(kind1, 100) }
            runCurrent()
            store.gates[1].complete(Unit)
            val fresh = after.await()
            store.gates[0].complete(Unit)
            assertEquals(1, before.await()!!.size())

            assertSame(fresh, live.sealedNegentropyStorage(kind1, 100))
            assertEquals(2, store.walks)
            inner.close()
        }

    // ------------------------------------------------------------------
    // Who asks
    // ------------------------------------------------------------------

    /** Unauthenticated on its first read, authenticated as alice from then on: an AUTH landing mid-open. */
    private class AuthLandsMidOpen : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        private var reads = 0
        override val authenticatedUsers: Set<HexKey>
            get() = if (reads++ == 0) emptySet() else setOf("a".repeat(64))
    }

    private object Anonymous : RequestContext {
        override val connectionId: Long = 2
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    @Test
    fun theSnapshotIsBuiltAsTheCallerItIsCachedFor() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val store = Slow(inner)
            val live = LiveEventStore(store, StoreIngest(store))

            live.sealedNegentropyStorage(AuthLandsMidOpen(), kind1, 100)
            live.sealedNegentropyStorage(Anonymous, kind1, 100)
            assertTrue(store.callers.all { it == null }, "an anonymous slot never holds a snapshot built as alice: ${store.callers}")
            inner.close()
        }

    /** A store whose full-set index answers ungated, as a gated store's must not. */
    private class UngatedFullSet(
        inner: IEventStore,
        val full: IStorage,
    ) : IEventStore by inner {
        override suspend fun liveNegentropySnapshot(maxEntries: Int): IStorage = full
    }

    @Test
    fun theFullSetFastPathIsNotHandedToALensedCaller() =
        runTest {
            val inner = EventStore(null)
            inner.insert(note("a", 1_000))
            val full = NegentropyServerSession.sealVector(emptyList())
            val live = LiveEventStore(UngatedFullSet(inner, full), StoreIngest(inner))
            val everything = listOf(Filter())

            assertSame(full, live.sealedNegentropyStorage(Anonymous, everything, 100), "an unauthenticated caller may take it")
            assertNotSame(full, live.sealedNegentropyStorage(As("a".repeat(64)), everything, 100), "alice's set may be gated")
            inner.close()
        }
}
