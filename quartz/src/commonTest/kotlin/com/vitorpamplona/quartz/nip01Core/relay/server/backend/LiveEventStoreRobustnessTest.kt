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
import com.vitorpamplona.quartz.nip01Core.relay.server.FanoutExecutor
import com.vitorpamplona.quartz.nip01Core.relay.server.FanoutExecutorTest
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RejectionReason
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The live store under ingests, executors and listeners that misbehave: exactly-once holds,
 * dedupe state is always released, and one failure costs nobody else their delivery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveEventStoreRobustnessTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    private val notes = listOf(Filter(kinds = listOf(1)))

    private object Ctx : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    private class ExecutorCtx(
        override val liveExecutor: FanoutExecutor,
    ) : RequestContext {
        override val connectionId: Long = 2
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    /** Holds each callback until [release], which answers it with [outcome]. */
    private class HeldIngest : EventIngest {
        val held = mutableListOf<(IEventStore.InsertOutcome) -> Unit>()

        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) {
            held.add(onComplete)
        }

        fun release(outcome: IEventStore.InsertOutcome = IEventStore.InsertOutcome.Accepted) {
            held.toList().forEach { it(outcome) }
            held.clear()
        }

        override fun hasBacklog() = held.isNotEmpty()

        override fun close() {}
    }

    /** What a cancelled `Channel.send` can do: hand the element over, then throw. */
    private class DeliveredThenCancelled : EventIngest {
        var held: ((IEventStore.InsertOutcome) -> Unit)? = null

        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) {
            held = onComplete
            throw CancellationException("publisher left while suspended on backpressure")
        }

        override fun hasBacklog() = held != null

        override fun close() {}
    }

    private object RefusingExecutor : FanoutExecutor {
        override fun execute(task: () -> Unit) = throw IllegalStateException("shutting down")
    }

    @Test
    fun aCancelledSubmitThatStillLandsIsDeliveredOnce() =
        runTest {
            val store = EventStore(null)
            val ingest = DeliveredThenCancelled()
            val live = LiveEventStore(store, ingest)
            val received = mutableListOf<String>()
            val sub = live.openSubscription(Ctx, notes) { e, _ -> received.add(e.id) }

            val note = event(1)
            try {
                live.submit(note) {}
            } catch (_: CancellationException) {
            }
            // The writer commits it anyway, the replay reads it, then the late fan-out runs.
            store.insert(note)
            sub.replayRaw({ received.add(it.id) }) {}
            ingest.held!!(IEventStore.InsertOutcome.Accepted)

            assertEquals(listOf(note.id), received)
            assertFalse(live.dedupesLive(sub))
            store.close()
        }

    @Test
    fun aRejectedResendDoesNotPinTheDedupeSet() = rejectedResend(null)

    @Test
    fun aRejectedResendDoesNotPinTheDedupeSetOnAnExecutor() = rejectedResend(FanoutExecutorTest.QueueExecutor())

    /**
     * A client resends a stored event: it is in flight while a REQ replays the stored copy,
     * then comes back a duplicate with no fan-out. The REQ must stop deduping all the same.
     */
    private fun rejectedResend(executor: FanoutExecutorTest.QueueExecutor?) =
        runTest {
            val store = EventStore(null)
            val ingest = HeldIngest()
            val live = LiveEventStore(store, ingest)
            val note = event(1)
            store.insert(note)
            live.submit(note) {}

            val received = mutableListOf<String>()
            val ctx = if (executor == null) Ctx else ExecutorCtx(executor)
            val sub = live.openSubscription(ctx, notes) { e, _ -> received.add(e.id) }
            sub.replayRaw({ received.add(it.id) }) {}
            executor?.drain()

            ingest.release(IEventStore.InsertOutcome.Rejected(RejectionReason.PREFIX_DUPLICATE + " have it"))
            executor?.drain()
            assertFalse(live.dedupesLive(sub))

            val next = event(2)
            live.submit(next) {}
            ingest.release()
            executor?.drain()
            assertEquals(listOf(note.id, next.id), received)
            store.close()
        }

    @Test
    fun aThrowingListenerCostsNobodyElseTheEvent() =
        runTest {
            val store = EventStore(null)
            val live = LiveEventStore(store, AcceptingIngest)
            val loop = FanoutExecutorTest.QueueExecutor()
            val received = mutableListOf<String>()

            listOf<RequestContext>(Ctx, ExecutorCtx(loop)).forEach { ctx ->
                live.openSubscription(ctx, notes) { _, _ -> throw IllegalStateException("bad sink") }.replayRaw({}) {}
                live.openSubscription(ctx, notes) { e, _ -> received.add("${ctx.connectionId}:${e.id}") }.replayRaw({}) {}
            }
            loop.drain()

            val note = event(1)
            live.submit(note) {}
            loop.drain()

            assertEquals(setOf("1:${note.id}", "2:${note.id}"), received.toSet())
            store.close()
        }

    @Test
    fun aRefusedSettleStillEndsTheDedupe() =
        runTest {
            val store = EventStore(null)
            val live = LiveEventStore(store, AcceptingIngest)
            val sub = live.openSubscription(ExecutorCtx(RefusingExecutor), notes) { _, _ -> }
            sub.replayRaw({}) {}
            assertFalse(live.dedupesLive(sub))
            store.close()
        }

    @Test
    fun insertReturnsOnceInlineSubscribersHaveTheEvent() =
        runTest {
            val store = EventStore(null)
            val ingest = HeldIngest()
            val live = LiveEventStore(store, ingest)
            val received = mutableListOf<String>()
            live.openSubscription(Ctx, notes) { e, _ -> received.add(e.id) }.replayRaw({}) {}

            val note = event(1)
            var receivedWhenInsertReturned: List<String>? = null
            launch(UnconfinedTestDispatcher(testScheduler)) {
                live.insert(note)
                receivedWhenInsertReturned = received.toList()
            }
            ingest.release()

            assertEquals(listOf(note.id), receivedWhenInsertReturned)
            store.close()
        }
}
