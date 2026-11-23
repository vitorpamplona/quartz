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
package com.vitorpamplona.quartz.nip01Core.relay.server

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A REQ whose backend fails is answered with one `CLOSED error:` and gives its subscription
 * slot back, on both REQ paths, so a store outage does not use up `max_subscriptions` for the
 * rest of the connection; and a failure that lands after the sub id was reused stays silent
 * and leaves the new subscription alone.
 */
class FailedReplayTest {
    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}

        fun closed() = messages.filterIsInstance<ClosedMessage>()

        fun closedReasons() = closed().map { "${it.subId}: ${it.message}" }
    }

    /**
     * Throws "store down" while [down] is set. With [hold] set, the next replay waits until it
     * is cancelled and then fails with an ordinary exception, as a network client that reports
     * its aborted call as an I/O error does.
     */
    private class FlakyStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        var down = false
        var hold = false

        private suspend fun gate() {
            if (hold) {
                hold = false
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    throw IllegalStateException("store down")
                }
            }
            if (down) throw IllegalStateException("store down")
        }

        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) {
            gate()
            inner.query(filters, onEach)
        }

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            gate()
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

    /** A backend on the parked-coroutine REQ path (not a LiveSubscriptionBackend). */
    private class ParkedBackend(
        val store: FlakyStore,
    ) : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            store.query<Event>(filters, onEach)
            onEose()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = 0
    }

    private fun TestScope.session(
        backend: SessionBackend,
        sink: Frames,
    ) = RelaySession(
        backend,
        LimitsPolicy(RelayLimits(maxSubscriptions = 2)),
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        sink,
        onClose = {},
    )

    private suspend fun TestScope.outageThenRecovery(
        store: FlakyStore,
        backend: SessionBackend,
    ) {
        val sink = Frames()
        val session = session(backend, sink)

        store.down = true
        repeat(3) { n ->
            session.receive("""["REQ","fail$n",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
        }
        val failures = sink.closed()
        assertEquals(3, failures.size, "each failed REQ gets one CLOSED: ${sink.closedReasons()}")
        assertTrue(failures.all { it.message.startsWith("error:") && it.message.contains("store down") }, "${sink.closedReasons()}")

        store.down = false
        sink.messages.clear()
        session.receive("""["REQ","after",{"kinds":[1]}]""")
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), sink.closedReasons(), "the failed REQs still held their slots")
        assertEquals(listOf("after"), sink.messages.filterIsInstance<EoseMessage>().map { it.subId })
    }

    @Test
    fun failedReplaysReleaseTheirSlotsOnALiveBackend() =
        runTest {
            val store = FlakyStore(EventStore(null))
            outageThenRecovery(store, LiveEventStore(store, AcceptingIngest))
        }

    @Test
    fun failedReplaysReleaseTheirSlotsOnAParkedBackend() =
        runTest {
            val store = FlakyStore(EventStore(null))
            outageThenRecovery(store, ParkedBackend(store))
        }

    private suspend fun TestScope.lateFailureAfterReuse(
        store: FlakyStore,
        backend: SessionBackend,
    ) {
        val sink = Frames()
        val session = session(backend, sink)

        store.hold = true
        session.receive("""["REQ","s",{"kinds":[1]}]""")
        testScheduler.advanceUntilIdle()
        // The client replaces "s"; the new REQ answers normally.
        session.receive("""["REQ","s",{"kinds":[1]}]""")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("s"), sink.messages.filterIsInstance<EoseMessage>().map { it.subId })
        // The replaced REQ's backend failed on its cancellation: that is not the new REQ's CLOSED.
        assertEquals(emptyList(), sink.closedReasons(), "the replaced REQ's failure reached the client as the new one's CLOSED")

        // The new "s" is still open: closing it is silent, not "no such subscription".
        session.receive("""["CLOSE","s"]""")
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), sink.closedReasons())
    }

    @Test
    fun aLateFailureLeavesTheReusedSubIdAloneOnALiveBackend() =
        runTest {
            val store = FlakyStore(EventStore(null))
            lateFailureAfterReuse(store, LiveEventStore(store, AcceptingIngest))
        }

    @Test
    fun aLateFailureLeavesTheReusedSubIdAloneOnAParkedBackend() =
        runTest {
            val store = FlakyStore(EventStore(null))
            lateFailureAfterReuse(store, ParkedBackend(store))
        }
}
