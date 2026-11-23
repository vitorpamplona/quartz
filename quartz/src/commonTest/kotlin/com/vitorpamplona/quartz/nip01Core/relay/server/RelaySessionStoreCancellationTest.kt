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
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store's own CancellationException (a timeout it put on its read) is a failure of the read,
 * not a cancelled request: the client gets `CLOSED error:` instead of waiting forever. Only the
 * request's own cancellation (CLOSE, a replaced id, a disconnect) ends it without an answer.
 */
class RelaySessionStoreCancellationTest {
    /** A read that runs into its own timeout. */
    private suspend fun timesOut() =
        withTimeout(10) {
            delay(1000)
        }

    /** A plain backend: the REQ parks inside [query]. */
    private object TimingOutBackend : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            withTimeout(10) { delay(1000) }
            onEose()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int =
            withTimeout(10) {
                delay(1000)
                1
            }
    }

    /** A store whose every read runs into its own timeout, behind a [LiveEventStore]. */
    private class TimingOutStore(
        inner: IEventStore,
        val timesOut: suspend () -> Unit,
    ) : IEventStore by inner {
        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) = timesOut()

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) = timesOut()
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

    /** Screens every outgoing event, so a REQ takes the materialized path. */
    private object Screening : PassThroughPolicy() {
        override val filtersOutgoingEvents = true
    }

    private class Messages : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    private fun sessionTest(
        backend: (TestScope) -> SessionBackend,
        policy: IRelayPolicy = EmptyPolicy,
        body: suspend TestScope.(RelaySession, Messages) -> Unit,
    ): TestResult =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val sink = Messages()
            try {
                body(RelaySession(backend(this), policy, scope, sink, onClose = {}), sink)
            } finally {
                scope.cancel()
            }
        }

    private fun Messages.closed() = messages.filterIsInstance<ClosedMessage>().map { it.subId to it.message.substringBefore(':') }

    @Test
    fun aParkedReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest({ TimingOutBackend }) { session, sink ->
            session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), sink.closed())
            assertTrue(sink.messages.none { it is EoseMessage })
        }

    @Test
    fun aLiveReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest({ LiveEventStore(TimingOutStore(EventStore(null), ::timesOut), AcceptingIngest) }) { session, sink ->
            session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), sink.closed())
            assertTrue(sink.messages.none { it is EoseMessage })
        }

    @Test
    fun aScreenedLiveReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest({ LiveEventStore(TimingOutStore(EventStore(null), ::timesOut), AcceptingIngest) }, Screening) { session, sink ->
            session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), sink.closed())
        }

    @Test
    fun aCountWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest({ TimingOutBackend }) { session, sink ->
            session.receive("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to "error"), sink.closed())
        }

    @Test
    fun aClosedReqIsNotAnsweredWhenItsStoreTimesOutLater() =
        sessionTest({ TimingOutBackend }) { session, sink ->
            session.receive("""["REQ","s",{"kinds":[1]}]""")
            session.receive("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()

            assertTrue(sink.messages.isEmpty(), "got ${sink.messages.map { it.toJson() }}")
        }
}
