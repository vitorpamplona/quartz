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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A COUNT runs beside the connection's reader, as a REQ does, so a slow count holds up no
 * later frame; it is tracked by query id, so a re-COUNT, a CLOSE or a disconnect stops it, and
 * capped by the policy's subscription hook, so a client cannot queue them without bound.
 */
class RelaySessionCountTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    /** One call into the backend's count, answered (or failed) by the test. */
    private class Call(
        val kind: Int,
        val ctx: RequestContext,
    ) {
        val answer = CompletableDeferred<Int>()

        /** The identities the backend read from [ctx] once it got to count, past any gate. */
        var seenUsers: Set<HexKey>? = null
    }

    /**
     * Every count waits for the test to answer it; every EVENT is accepted at once. With a
     * [gate], counts take it one at a time first, as a relay's own read gate would.
     */
    private class SlowCountBackend(
        val gate: Mutex? = null,
    ) : SessionBackend {
        val calls = mutableListOf<Call>()
        var cancelled = 0

        /** Waits out cancellation, as a blocking or careless store does. */
        var ignoreCancellation = false

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int =
            try {
                if (gate != null) gate.withLock { countNow(ctx, filters) } else countNow(ctx, filters)
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }

        private suspend fun countNow(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            val call = Call(filters.first().kinds!!.first(), ctx)
            calls.add(call)
            val n = if (ignoreCancellation) withContext(NonCancellable) { call.answer.await() } else call.answer.await()
            call.seenUsers = ctx.authenticatedUsers
            return n
        }

        override suspend fun submit(
            event: Event,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)
    }

    private class Messages : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /** A session read by one coroutine, frame after frame, as a socket's reader loop does. */
    private class Fixture(
        test: TestScope,
        policy: IRelayPolicy,
        val backend: SessionBackend,
    ) {
        val sink = Messages()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + Job())
        val session = RelaySession(backend, policy, scope, sink, onClose = {})
        private val inbox = Channel<String>(Channel.UNLIMITED)
        val reader = scope.launch { for (text in inbox) session.receive(text) }

        val slow get() = backend as SlowCountBackend

        fun send(frame: String) {
            inbox.trySend(frame)
        }

        fun counts() = sink.messages.filterIsInstance<CountMessage>().map { it.queryId to it.result.count }

        fun closed() = sink.messages.filterIsInstance<ClosedMessage>().map { it.subId to it.message }

        fun coroutines() = scope.coroutineContext[Job]!!.children.count { it !== reader }
    }

    /** Runs [body] on a fresh [Fixture] and cancels the fixture's scope, reader included, after. */
    private fun countTest(
        policy: IRelayPolicy = EmptyPolicy,
        backend: SessionBackend = SlowCountBackend(),
        body: suspend TestScope.(Fixture) -> Unit,
    ): TestResult =
        runTest {
            val f = Fixture(this, policy, backend)
            try {
                body(f)
            } finally {
                f.scope.cancel()
            }
        }

    private fun count(
        id: String,
        kind: Int = 1,
    ) = """["COUNT","$id",{"kinds":[$kind]}]"""

    @Test
    fun anEventAfterASlowCountIsAnsweredFirst() =
        countTest { f ->
            f.send(count("c"))
            f.send(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.slow.calls.size, "the count is running")
            assertEquals(
                listOf(event(1).id),
                f.sink.messages
                    .filterIsInstance<OkMessage>()
                    .map { it.eventId },
            )
            assertTrue(f.counts().isEmpty(), "the count has not answered yet")

            f.slow.calls[0]
                .answer
                .complete(7)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("c" to 7), f.counts())
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aReqAfterASlowCountIsAnsweredFirst() =
        countTest { f ->
            f.send(count("c"))
            f.send("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("s"),
                f.sink.messages
                    .filterIsInstance<EoseMessage>()
                    .map { it.subId },
            )
            assertTrue(f.counts().isEmpty())

            f.slow.calls[0]
                .answer
                .complete(3)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("c" to 3), f.counts())
        }

    @Test
    fun countsOnOneConnectionRunAtOnceAndAnswerAsTheyFinish() =
        countTest { f ->
            f.send(count("a"))
            f.send(count("b"))
            testScheduler.advanceUntilIdle()

            // The engine does not serialize them: pacing a connection's reads is the app's gate.
            assertEquals(2, f.slow.calls.size)
            f.slow.calls[1]
                .answer
                .complete(2)
            testScheduler.advanceUntilIdle()
            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("b" to 2, "a" to 1), f.counts())
        }

    @Test
    fun aReCountOnTheSameIdReplacesTheRunningOne() =
        countTest { f ->
            f.send(count("c", kind = 1))
            testScheduler.advanceUntilIdle()
            f.send(count("c", kind = 7))
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.slow.cancelled, "the first count was cancelled")
            assertEquals(listOf(1, 7), f.slow.calls.map { it.kind })
            f.slow.calls[1]
                .answer
                .complete(2)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to 2), f.counts())
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aCountThatIgnoresCancellationIsNotAnsweredAfterItsReplacement() =
        countTest { f ->
            f.slow.ignoreCancellation = true
            f.send(count("c", kind = 1))
            testScheduler.advanceUntilIdle()
            f.send(count("c", kind = 7))
            testScheduler.advanceUntilIdle()

            // The replaced count runs to the end anyway, but its answer is not sent.
            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()
            assertTrue(f.counts().isEmpty(), "got ${f.counts()}")

            f.slow.calls[1]
                .answer
                .complete(2)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("c" to 2), f.counts())
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aCountThatIgnoresCancellationIsNotAnsweredAfterAClose() =
        countTest { f ->
            f.slow.ignoreCancellation = true
            f.send(count("c"))
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","c"]""")
            testScheduler.advanceUntilIdle()

            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()
            assertTrue(f.sink.messages.isEmpty(), "got ${f.sink.messages.map { it.toJson() }}")
        }

    @Test
    fun closeOnACountIdStopsItSilently() =
        countTest { f ->
            f.send(count("c"))
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","c"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.slow.cancelled)
            assertEquals(0, f.coroutines())
            // Like a CLOSE on a REQ: no answer, and no "no such subscription".
            assertTrue(f.sink.messages.isEmpty(), "got ${f.sink.messages.map { it.toJson() }}")
        }

    @Test
    fun closeOnAnIdUsedByAReqAndACountEndsBoth() =
        countTest { f ->
            f.send("""["REQ","x",{"kinds":[1]}]""")
            f.send(count("x"))
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","x"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.slow.cancelled, "the count was cancelled")
            assertTrue(f.closed().isEmpty(), "got ${f.closed()}")
            // Both are gone: a second CLOSE finds nothing.
            f.send("""["CLOSE","x"]""")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("x" to "error: no such subscription"), f.closed())
        }

    @Test
    fun closeOnAnAnsweredCountIdIsStillUnknown() =
        countTest { f ->
            f.send(count("c"))
            testScheduler.advanceUntilIdle()
            f.slow.calls[0]
                .answer
                .complete(5)
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","c"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to 5), f.counts())
            assertEquals(listOf("c" to "error: no such subscription"), f.closed())
        }

    @Test
    fun disconnectStopsEveryRunningCount() =
        countTest { f ->
            f.send(count("a"))
            f.send(count("b"))
            testScheduler.advanceUntilIdle()
            assertEquals(2, f.slow.calls.size)

            f.session.close()
            testScheduler.advanceUntilIdle()
            assertEquals(2, f.slow.cancelled, "both counts were cancelled")
            assertEquals(0, f.coroutines())
            f.slow.calls.forEach { it.answer.complete(9) }
            testScheduler.advanceUntilIdle()
            assertTrue(f.counts().isEmpty())
        }

    @Test
    fun aCountWaitingAtTheBackendsGateCanBeClosed() =
        countTest(backend = SlowCountBackend(gate = Mutex())) { f ->
            f.send(count("a", kind = 1))
            f.send(count("b", kind = 2))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(1), f.slow.calls.map { it.kind }, "b waits at the gate")

            f.send("""["CLOSE","b"]""")
            testScheduler.advanceUntilIdle()
            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(1), f.slow.calls.map { it.kind }, "b never counted")
            assertEquals(listOf("a" to 1), f.counts())
            assertTrue(f.closed().isEmpty(), "got ${f.closed()}")
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aReCountOnACountWaitingAtTheGateReplacesIt() =
        countTest(backend = SlowCountBackend(gate = Mutex())) { f ->
            f.send(count("a", kind = 1))
            f.send(count("b", kind = 2))
            f.send(count("b", kind = 3))
            testScheduler.advanceUntilIdle()

            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(1, 3), f.slow.calls.map { it.kind }, "the replaced b never counted")
            f.slow.calls[1]
                .answer
                .complete(3)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a" to 1, "b" to 3), f.counts())
        }

    /** Refuses any COUNT on kind 9. */
    private object NoKind9Counts : PassThroughPolicy() {
        override fun accept(cmd: CountCmd): PolicyResult<CountCmd> = if (cmd.filters.any { it.kinds?.contains(9) == true }) PolicyResult.Rejected("blocked: no kind 9") else PolicyResult.Accepted(cmd)
    }

    @Test
    fun aRefusedReCountStillCancelsTheRunningOne() =
        countTest(policy = NoKind9Counts) { f ->
            f.send(count("c", kind = 1))
            testScheduler.advanceUntilIdle()
            f.send(count("c", kind = 9))
            testScheduler.advanceUntilIdle()

            // As a refused re-REQ drops the subscription it replaced.
            assertEquals(1, f.slow.cancelled)
            assertEquals(listOf("c" to "blocked: no kind 9"), f.closed())
            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()
            assertTrue(f.counts().isEmpty())
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aBackendFailureMidCountAnswersClosedError() =
        countTest { f ->
            f.send(count("c"))
            testScheduler.advanceUntilIdle()
            f.slow.calls[0]
                .answer
                .completeExceptionally(IllegalStateException("disk on fire"))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to "error: disk on fire"), f.closed())
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aStoresOwnCancellationAnswersClosedError() =
        countTest { f ->
            f.send(count("c"))
            testScheduler.advanceUntilIdle()
            // Not this count being cancelled: the store gave up on something of its own.
            f.slow.calls[0]
                .answer
                .completeExceptionally(CancellationException("store gave up"))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to "error: store gave up"), f.closed())
            assertEquals(0, f.coroutines())
        }

    /** Every count runs into its own timeout, as a store bounding its reads does. */
    private object TimingOutBackend : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int =
            withTimeout(10) {
                delay(1000)
                1
            }
    }

    @Test
    fun aStoreTimeoutAnswersClosedError() =
        countTest(backend = TimingOutBackend) { f ->
            f.send(count("c"))
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.closed().size, "got ${f.sink.messages.map { it.toJson() }}")
            val (id, reason) = f.closed().single()
            assertEquals("c", id)
            assertTrue(reason.startsWith("error: "), reason)
            assertEquals(0, f.coroutines())
        }

    @Test
    fun pendingCountsAreCappedByTheSubscriptionHook() =
        countTest(policy = LimitsPolicy(RelayLimits(maxSubscriptions = 2))) { f ->
            f.send(count("a"))
            f.send(count("b"))
            f.send(count("c"))
            testScheduler.advanceUntilIdle()

            assertEquals(2, f.slow.calls.size, "the third count never reached the store")
            assertEquals(listOf("c" to "rate-limited: too many concurrent subscriptions (max 2)"), f.closed())

            // A re-COUNT on a pending id replaces it 1-for-1, so the cap does not refuse it.
            f.send(count("a", kind = 7))
            testScheduler.advanceUntilIdle()
            assertEquals(1, f.slow.calls.count { it.kind == 7 })
            assertEquals(1, f.closed().size)

            // An answered count frees its slot.
            f.slow.calls[1]
                .answer
                .complete(2)
            testScheduler.advanceUntilIdle()
            f.send(count("d"))
            testScheduler.advanceUntilIdle()
            assertEquals(4, f.slow.calls.size)
            assertEquals(1, f.closed().size, "got ${f.closed()}")
        }

    @Test
    fun countsAndReqsAreCappedSeparately() =
        countTest(policy = LimitsPolicy(RelayLimits(maxSubscriptions = 2))) { f ->
            // Two open REQs at the cap still leave room for counts, as before counts ran async.
            f.send("""["REQ","r1",{"kinds":[1]}]""")
            f.send("""["REQ","r2",{"kinds":[1]}]""")
            f.send(count("a"))
            f.send(count("b"))
            testScheduler.advanceUntilIdle()
            assertEquals(2, f.slow.calls.size)
            assertTrue(f.closed().isEmpty(), "got ${f.closed()}")

            // And two pending counts do not take a REQ's slot.
            f.send("""["CLOSE","r2"]""")
            f.send("""["REQ","r3",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf("r1", "r2", "r3"),
                f.sink.messages
                    .filterIsInstance<EoseMessage>()
                    .map { it.subId },
            )
            assertTrue(f.closed().isEmpty(), "got ${f.closed()}")
        }

    @Test
    fun aCountSeesTheIdentitiesAuthenticatedBeforeItArrived() =
        countTest(backend = SlowCountBackend(gate = Mutex())) { f ->
            f.send(count("a", kind = 1))
            f.send(count("b", kind = 2))
            testScheduler.advanceUntilIdle()
            assertEquals(null, f.session.authenticateByTransport(pubkey))
            f.send(count("c", kind = 3))
            testScheduler.advanceUntilIdle()

            f.slow.calls[0]
                .answer
                .complete(1)
            testScheduler.advanceUntilIdle()
            f.slow.calls[1]
                .answer
                .complete(2)
            testScheduler.advanceUntilIdle()
            f.slow.calls[2]
                .answer
                .complete(3)
            testScheduler.advanceUntilIdle()

            // b waited at the gate past the sign-in but counts as the connection stood when it
            // arrived; c arrived after it.
            assertEquals(listOf(1, 2, 3), f.slow.calls.map { it.kind })
            assertEquals(listOf(emptySet(), emptySet(), setOf(pubkey)), f.slow.calls.map { it.seenUsers })
            assertEquals(listOf("a" to 1, "b" to 2, "c" to 3), f.counts())
        }

    @Test
    fun aFastCountStillAnswersBeforeTheNextFrame() =
        countTest(backend = InstantCountBackend) { f ->
            f.session.receive(count("c"))

            // A count that does not suspend is answered before receive returns, as it was.
            assertEquals(listOf("c" to 4), f.counts())
        }

    private object InstantCountBackend : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ) = 4
    }
}
