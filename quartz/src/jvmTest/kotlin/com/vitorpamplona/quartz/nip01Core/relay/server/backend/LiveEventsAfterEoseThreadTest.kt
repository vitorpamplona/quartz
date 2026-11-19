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
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On a connection without a fan-out executor, the replay's own thread drains what was held while
 * the ingest's thread keeps offering: what arrives mid-drain must queue behind it, never overtake
 * it, never repeat, and never land before EOSE.
 */
class LiveEventsAfterEoseThreadTest {
    private object Ctx : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    /** Blocks the replay until [gate] opens. */
    private class GatedStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        val replayStarted = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            replayStarted.countDown()
            gate.await()
            inner.rawQuery(filters, onEach)
        }
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

    @Test
    fun offersDuringTheDrainQueueBehindIt() {
        val signer = NostrSignerSync()
        repeat(ROUNDS) { round ->
            val events = (1..PER_ROUND).map { signer.sign<TextNoteEvent>(TextNoteEvent.build("round $round note $it")) }
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val frames = Collections.synchronizedList(ArrayList<String>())

            val sub =
                live.openSubscription(Ctx, listOf(Filter(kinds = listOf(TextNoteEvent.KIND)))) { event, _ ->
                    // A listener slow enough that the drain is still running when the next offers land.
                    repeat(SPIN) { Thread.onSpinWait() }
                    frames.add(event.id)
                }
            val replay = thread { runBlocking { sub.replayRaw({ frames.add(it.id) }) { frames.add("EOSE") } } }
            assertTrue(store.replayStarted.await(10, TimeUnit.SECONDS))

            val halfway = CountDownLatch(1)
            val ingest =
                thread {
                    runBlocking {
                        events.forEachIndexed { i, event ->
                            if (i == PER_ROUND / 2) halfway.countDown()
                            live.submit(event) {}
                        }
                    }
                }
            assertTrue(halfway.await(10, TimeUnit.SECONDS))
            store.gate.countDown()
            replay.join(10_000)
            ingest.join(10_000)

            val seen = synchronized(frames) { frames.toList() }
            assertEquals("EOSE", seen.first(), "round $round: a live event went out before EOSE")
            assertEquals(events.map { it.id }, seen.drop(1), "round $round: live events reordered, repeated or lost")
            sub.cancel()
            store.close()
        }
    }

    private companion object {
        const val ROUNDS = 10
        const val PER_ROUND = 300
        const val SPIN = 2_000
    }
}
