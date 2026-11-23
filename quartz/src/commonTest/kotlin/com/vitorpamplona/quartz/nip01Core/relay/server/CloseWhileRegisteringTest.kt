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
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A disconnect handled on another thread while the command loop is registering a REQ or COUNT:
 * what registers after the session's subscriptions were swept is stopped, not left running and
 * answering a connection that is gone.
 */
class CloseWhileRegisteringTest {
    /**
     * A REQ's query sends its EOSE inline, then parks at its live tail until cancelled. Counts
     * wait for the test, or with [inline] answer 5 without suspending.
     */
    private class Backend(
        val inline: Boolean = false,
    ) : SessionBackend {
        val counts = mutableListOf<CompletableDeferred<Int>>()

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            onEose()
            awaitCancellation()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            if (inline) return 5
            val answer = CompletableDeferred<Int>()
            counts.add(answer)
            return answer.await()
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

    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /**
     * Closes the session from inside [accept]: the command loop has read the frame and is about
     * to register its work, as when a disconnect lands on another thread at that moment.
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

    private class Fixture(
        test: TestScope,
        backend: SessionBackend,
        closesWhileAccepting: Boolean = true,
    ) {
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + Job())
        val frames = Frames()
        private val policy = ClosesWhileAccepting()
        val session = RelaySession(backend, policy, scope, frames, onClose = {})

        init {
            if (closesWhileAccepting) policy.session = session
        }

        fun running() = scope.coroutineContext[Job]!!.children.count()
    }

    @Test
    fun aCountRegisteringAsTheSessionClosesNeverAnswers() =
        runTest {
            val backend = Backend()
            val f = Fixture(this, backend)
            try {
                f.session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                backend.counts.forEach { it.complete(1) }
                testScheduler.advanceUntilIdle()

                assertTrue(f.frames.messages.none { it is CountMessage }, "got ${f.frames.messages.map { it.toJson() }}")
                assertEquals(0, f.running())
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aParkedReqRegisteringAsTheSessionClosesLeavesNothingRunning() =
        runTest {
            val f = Fixture(this, Backend())
            try {
                f.session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertEquals(0, f.running(), "the REQ's coroutine still parks at its live tail")
                assertTrue(f.frames.messages.none { it is EoseMessage }, "got ${f.frames.messages.map { it.toJson() }}")
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aLiveReqRegisteringAsTheSessionClosesLeavesNothingRegistered() =
        runTest {
            val live = LiveEventStore(EventStore(null), AcceptingIngest)
            val f = Fixture(this, live)
            try {
                f.session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertEquals(0, live.liveSubscriptionCount, "the REQ is still registered for live events")
                assertTrue(f.frames.messages.none { it is EoseMessage }, "got ${f.frames.messages.map { it.toJson() }}")
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aCountThatAnswersInlineAsTheSessionClosesNeverAnswers() =
        runTest {
            val f = Fixture(this, Backend(inline = true))
            try {
                f.session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertTrue(f.frames.messages.isEmpty(), "got ${f.frames.messages.map { it.toJson() }}")
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aClosedSessionRunsNoCountEvenOneThatAnswersInline() =
        runTest {
            val f = Fixture(this, Backend(inline = true), closesWhileAccepting = false)
            try {
                f.session.close()
                f.session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertTrue(f.frames.messages.isEmpty(), "got ${f.frames.messages.map { it.toJson() }}")
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aClosedSessionOpensNoReqEvenOneThatEosesInline() =
        runTest {
            val f = Fixture(this, Backend(), closesWhileAccepting = false)
            try {
                f.session.close()
                f.session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertTrue(f.frames.messages.isEmpty(), "got ${f.frames.messages.map { it.toJson() }}")
                assertEquals(0, f.running())
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun aSessionKeepsServingAfterItsSubscriptionsAreCancelled() =
        runTest {
            val backend = Backend()
            val f = Fixture(this, backend, closesWhileAccepting = false)
            try {
                f.session.receive("""["REQ","a",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                // Not a disconnect: the session drops what it holds and carries on.
                f.session.cancelAllSubscriptions()
                f.session.receive("""["REQ","b",{"kinds":[1]}]""")
                f.session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()
                backend.counts.single().complete(3)
                testScheduler.advanceUntilIdle()

                assertEquals(
                    listOf("a", "b"),
                    f.frames.messages
                        .filterIsInstance<EoseMessage>()
                        .map { it.subId },
                )
                assertEquals(
                    listOf("c" to 3),
                    f.frames.messages
                        .filterIsInstance<CountMessage>()
                        .map { it.queryId to it.result.count },
                )
                assertEquals(1, f.running(), "REQ b is still open at its live tail")
            } finally {
                f.scope.cancel()
            }
        }

    /**
     * Ends the session from inside its read, as a disconnect on another thread does while the
     * store works, then answers without suspending: rows and EOSE for a REQ, 5 for a COUNT.
     */
    private inner class EndsDuringRead : SessionBackend {
        var session: RelaySession? = null

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            session?.close()
            onEach(Event("3".padStart(64, '0'), PUBKEY, 1003, 1, emptyArray(), "after end", SIG))
            onEose()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            session?.close()
            return 5
        }
    }

    private class RawFrames : SessionSink {
        val frames = mutableListOf<String>()

        override fun message(message: Message) {
            frames.add(message.toJson())
        }

        override fun raw(json: String) {
            frames.add(json)
        }
    }

    @Test
    fun aParkedReqWhoseSessionEndsMidReadSendsNothingAfter() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val backend = EndsDuringRead()
            val frames = RawFrames()
            val session = RelaySession(backend, PassThroughPolicy(), scope, frames, onClose = {})
            backend.session = session
            try {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertEquals(emptyList(), frames.frames, "rows or EOSE went out after the session ended")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aCountWhoseSessionEndsMidReadSendsNothingAfter() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val backend = EndsDuringRead()
            val frames = RawFrames()
            val session = RelaySession(backend, PassThroughPolicy(), scope, frames, onClose = {})
            backend.session = session
            try {
                session.receive("""["COUNT","c",{"kinds":[1]}]""")
                testScheduler.advanceUntilIdle()

                assertEquals(emptyList(), frames.frames, "the COUNT answered after the session ended")
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        const val PUBKEY = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
        const val SIG = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"
    }
}
