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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ending a session while a frame is being handled, and a sink that ends its own session while
 * it takes a frame: neither leaves work running after the end, nor deadlocks.
 */
class RelaySessionCloseRaceTest {
    /** Counts wait for the test; REQs replay nothing and end. */
    private class Backend : SessionBackend {
        val counts = mutableListOf<CompletableDeferred<Int>>()

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            val answer = CompletableDeferred<Int>()
            counts.add(answer)
            return answer.await()
        }
    }

    private class Messages : SessionSink {
        val messages = mutableListOf<Message>()
        var onMessage: (Message) -> Unit = {}

        override fun message(message: Message) {
            messages.add(message)
            onMessage(message)
        }

        override fun raw(json: String) {}
    }

    /**
     * Ends the session from inside the policy: the reader has started on the frame, so the
     * session closes after the frame was read but before its work registers, as a disconnect on
     * another thread can.
     */
    private class ClosesWhileAccepting : PassThroughPolicy() {
        var session: RelaySession? = null

        override fun accept(cmd: CountCmd): PolicyResult<CountCmd> {
            session?.close()
            return PolicyResult.Accepted(cmd)
        }

        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> {
            session?.close()
            return PolicyResult.Accepted(cmd)
        }
    }

    @Test
    fun aCountRegisteringAsTheSessionClosesNeverAnswers() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val backend = Backend()
            val sink = Messages()
            val policy = ClosesWhileAccepting()
            val session = RelaySession(backend, policy, scope, sink, onClose = {})
            policy.session = session
            try {
                session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                backend.counts.forEach { it.complete(1) }
                testScheduler.advanceUntilIdle()

                assertTrue(sink.messages.none { it is CountMessage }, "got ${sink.messages.map { it.toJson() }}")
                assertEquals(0, scope.coroutineContext[Job]!!.children.count())
            } finally {
                scope.cancel()
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
    fun aLiveReqRegisteringAsTheSessionClosesLeavesNothingRegistered() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val live = LiveEventStore(EventStore(null), AcceptingIngest)
            val sink = Messages()
            val policy = ClosesWhileAccepting()
            val session = RelaySession(live, policy, scope, sink, onClose = {})
            policy.session = session
            try {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertEquals(0, live.liveSubscriptionCount)
                assertTrue(sink.messages.none { it is EoseMessage }, "got ${sink.messages.map { it.toJson() }}")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aParkedReqRegisteringAsTheSessionClosesNeverRuns() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val sink = Messages()
            val policy = ClosesWhileAccepting()
            val session = RelaySession(Backend(), policy, scope, sink, onClose = {})
            policy.session = session
            try {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertTrue(sink.messages.none { it is EoseMessage }, "got ${sink.messages.map { it.toJson() }}")
                assertEquals(0, scope.coroutineContext[Job]!!.children.count())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aSinkThatEndsItsSessionOnACountAnswerDoesNotDeadlock() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val backend = Backend()
            val sink = Messages()
            val session = RelaySession(backend, EmptyPolicy, scope, sink, onClose = {})
            // A transport that hangs up once it has the answer it was waiting for.
            sink.onMessage = { if (it is CountMessage) session.close() }
            try {
                session.receive("""["COUNT","a",{"kinds":[1]}]""")
                session.receive("""["COUNT","b",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                backend.counts[0].complete(1)
                testScheduler.advanceUntilIdle()
                backend.counts[1].complete(2)
                testScheduler.advanceUntilIdle()

                // "a" answered and ended the session, which stopped "b".
                assertEquals(listOf("a"), sink.messages.filterIsInstance<CountMessage>().map { it.queryId })
                assertEquals(0, scope.coroutineContext[Job]!!.children.count())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aWaiterResumedInlineByTheAnswerCanEndTheSession() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val backend = Backend()
            val sink = Messages()
            val session = RelaySession(backend, EmptyPolicy, scope, sink, onClose = {})
            // As NIP-FE's handler: the sink completes what a waiter awaits, and the waiter, resumed
            // on the sink's own thread (Unconfined), closes the session.
            val answered = CompletableDeferred<Unit>()
            sink.onMessage = { if (it is CountMessage) answered.complete(Unit) }
            val waiter =
                scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    answered.await()
                    session.close()
                }
            try {
                session.receive("""["COUNT","a",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                backend.counts[0].complete(1)
                testScheduler.advanceUntilIdle()

                assertTrue(waiter.isCompleted, "the waiter closed the session")
                assertEquals(listOf("a"), sink.messages.filterIsInstance<CountMessage>().map { it.queryId })
            } finally {
                scope.cancel()
            }
        }
}
