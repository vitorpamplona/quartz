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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.FanoutExecutorTest
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A REQ's live events never precede its EOSE: everything before EOSE is the stored answer
 * its `limit` bounds. Before this, a `limit:1` search on a relay ingesting a mirror got every
 * matching event accepted during its replay ahead of the one stored row it asked for.
 */
class LiveEventsAfterEoseTest {
    private val signer = NostrSignerSync()

    private object Ctx : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    private class ExecutorCtx(
        override val liveExecutor: FanoutExecutorTest.QueueExecutor,
    ) : RequestContext {
        override val connectionId: Long = 2
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    /** Holds every replay until [gate] opens, so the test can land live events first. */
    private class GatedStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        val replayStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            inner.query(filters, onEach)
        }

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            inner.rawQuery(filters, onEach)
        }
    }

    /** Accepts at once; a test that wants the event stored writes it to the store itself. */
    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    private fun note(text: String) = signer.sign<TextNoteEvent>(TextNoteEvent.build(text))

    private val notes = Filter(kinds = listOf(TextNoteEvent.KIND), limit = 1)

    @Test
    fun liveEventsAcceptedDuringTheReplayFollowItsEose() =
        runTest {
            val store = GatedStore(EventStore(null))
            val stored = note("stored")
            store.inner.insert(stored)
            val live = LiveEventStore(store, AcceptingIngest)
            val frames = mutableListOf<String>()

            val sub = live.openSubscription(Ctx, listOf(notes)) { event, _ -> frames.add(event.id) }
            val replay = launch { sub.replayRaw({ frames.add(it.id) }) { frames.add("EOSE") } }
            store.replayStarted.await()
            val arrived = (1..5).map { note("live $it") }
            arrived.forEach { live.submit(it) {} }
            assertEquals(emptyList(), frames)

            store.gate.complete(Unit)
            replay.join()
            assertEquals(listOf(stored.id, "EOSE") + arrived.map { it.id }, frames)

            val after = note("after")
            live.submit(after) {}
            assertEquals(after.id, frames.last())
            sub.cancel()
            store.close()
        }

    @Test
    fun anEventBothStoredAndHeldIsServedOnceAsStored() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val frames = mutableListOf<String>()

            val sub = live.openSubscription(Ctx, listOf(notes)) { event, _ -> frames.add("live:" + event.id) }
            val replay = launch { sub.replayRaw({ frames.add("stored:" + it.id) }) { frames.add("EOSE") } }
            store.replayStarted.await()
            val both = note("both")
            store.inner.insert(both)
            live.submit(both) {}

            store.gate.complete(Unit)
            replay.join()
            assertEquals(listOf("stored:" + both.id, "EOSE"), frames)
            sub.cancel()
            store.close()
        }

    @Test
    fun onAnExecutorHeldEventsGoOutOnItAfterEose() =
        runTest {
            val executor = FanoutExecutorTest.QueueExecutor()
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val frames = mutableListOf<String>()

            val sub = live.openSubscription(ExecutorCtx(executor), listOf(notes)) { event, _ -> frames.add(event.id) }
            val replay = launch { sub.replay({ frames.add(it.id) }) { frames.add("EOSE") } }
            store.replayStarted.await()
            val first = note("first")
            live.submit(first) {}
            executor.drain()
            assertEquals(emptyList(), frames)

            store.gate.complete(Unit)
            replay.join()
            assertEquals(listOf("EOSE"), frames)

            // A fan-out queued after EOSE but before the release still lands behind the held one.
            val second = note("second")
            live.submit(second) {}
            executor.drain()
            assertEquals(listOf("EOSE", first.id, second.id), frames)
            assertEquals(false, live.dedupesLive(sub))
            sub.cancel()
            store.close()
        }

    @Test
    fun pastTheCapHeldEventsAreForwardedAheadOfEoseAndNoneIsLost() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest, maxHeldBeforeEose = 2)
            val frames = mutableListOf<String>()

            val sub = live.openSubscription(Ctx, listOf(notes)) { event, _ -> frames.add(event.id) }
            val replay = launch { sub.replayRaw({ frames.add(it.id) }) { frames.add("EOSE") } }
            store.replayStarted.await()
            val arrived = (1..4).map { note("live $it") }
            arrived.take(2).forEach { live.submit(it) {} }
            assertEquals(emptyList(), frames)
            arrived.drop(2).forEach { live.submit(it) {} }
            assertEquals(arrived.map { it.id }, frames)

            store.gate.complete(Unit)
            replay.join()
            assertEquals(arrived.map { it.id } + "EOSE", frames)
            sub.cancel()
            store.close()
        }

    @Test
    fun aReqClosedBeforeEoseForwardsNothingItHeld() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val frames = mutableListOf<String>()

            val sub = live.openSubscription(Ctx, listOf(notes)) { event, _ -> frames.add(event.id) }
            val replay = launch { sub.replayRaw({ frames.add(it.id) }) { frames.add("EOSE") } }
            store.replayStarted.await()
            live.submit(note("held")) {}
            sub.cancel()
            replay.cancel()
            store.gate.complete(Unit)
            replay.join()

            assertEquals(emptyList(), frames)
            assertEquals(0, live.liveSubscriptionCount)
            store.close()
        }
}
