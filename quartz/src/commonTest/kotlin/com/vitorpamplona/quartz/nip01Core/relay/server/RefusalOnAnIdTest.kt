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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountResult
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveListener
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveSubscription
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveSubscriptionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A refusal on a sub id ends whatever the client holds there, of either kind: a refused REQ
 * cancels the COUNT running on its id, and a refused COUNT ends the REQ open on its id. The
 * client takes the CLOSED as the end of that id, so nothing of the older request may follow it.
 */
class RefusalOnAnIdTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    /**
     * A live backend whose COUNTs wait for [countGate], even once cancelled, and answer 5; and
     * whose [openSubscription] throws [refuseOpen] for a REQ on kind 9.
     */
    private class Backend(
        val live: LiveEventStore = LiveEventStore(EventStore(null), AcceptingIngest),
    ) : LiveSubscriptionBackend by live {
        val countGate = CompletableDeferred<Unit>()
        var refuseOpen: Throwable = IllegalStateException("search is down")

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            withContext(NonCancellable) { countGate.await() }
            return 5
        }

        override suspend fun countResult(
            ctx: RequestContext,
            filters: List<Filter>,
        ): CountResult = CountResult(count(ctx, filters))

        override fun openSubscription(
            ctx: RequestContext,
            filters: List<Filter>,
            onLive: LiveListener,
        ): LiveSubscription {
            if (filters.any { it.kinds?.contains(9) == true }) throw refuseOpen
            return live.openSubscription(ctx, filters, onLive)
        }
    }

    /** Refuses every REQ and COUNT on kind 9. */
    private open class NoKind9 : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> = if (cmd.filters.any { it.kinds?.contains(9) == true }) PolicyResult.Rejected("blocked: no kind 9") else PolicyResult.Accepted(cmd)

        override fun accept(cmd: CountCmd): PolicyResult<CountCmd> = if (cmd.filters.any { it.kinds?.contains(9) == true }) PolicyResult.Rejected("blocked: no kind 9") else PolicyResult.Accepted(cmd)
    }

    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()
        val raw = mutableListOf<String>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {
            raw.add(json)
        }

        /** Every frame on [id], as wire JSON, in order. */
        fun on(id: String) =
            messages
                .filter {
                    (it as? CountMessage)?.queryId == id || (it as? ClosedMessage)?.subId == id || (it as? EoseMessage)?.subId == id
                }.map { it.toJson() }
    }

    private class Harness(
        val session: RelaySession,
        val frames: Frames,
        val backend: Backend,
        val scope: CoroutineScope,
    )

    private fun refusalTest(
        policy: IRelayPolicy,
        backend: Backend = Backend(),
        body: suspend TestScope.(Harness) -> Unit,
    ): TestResult =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val frames = Frames()
            try {
                body(Harness(RelaySession(backend, policy, scope, frames, onClose = {}), frames, backend, scope))
            } finally {
                scope.cancel()
            }
        }

    /** A COUNT running on "x", then [refusedReq] on "x": only the refusal, never the COUNT's answer. */
    private suspend fun TestScope.aRefusedReqCancelsTheCountOnItsId(
        h: Harness,
        refusedReq: String,
        closed: String,
    ) {
        h.session.receive("""["COUNT","x",{"kinds":[1]}]""")
        testScheduler.advanceUntilIdle()
        h.session.receive(refusedReq)
        testScheduler.advanceUntilIdle()
        // The COUNT's backend ignores its cancellation and answers anyway.
        h.backend.countGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(closed), h.frames.on("x"))
    }

    @Test
    fun aReqTheCapRefusesCancelsTheCountOnItsId() =
        refusalTest(LimitsPolicy(RelayLimits(maxSubscriptions = 1))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            aRefusedReqCancelsTheCountOnItsId(
                h,
                """["REQ","x",{"kinds":[1]}]""",
                """["CLOSED","x","rate-limited: too many concurrent subscriptions (max 1)"]""",
            )
        }

    @Test
    fun aReqThePolicyRefusesCancelsTheCountOnItsId() =
        refusalTest(NoKind9()) { h ->
            aRefusedReqCancelsTheCountOnItsId(h, """["REQ","x",{"kinds":[9]}]""", """["CLOSED","x","blocked: no kind 9"]""")
        }

    @Test
    fun aReqTheBackendRefusesToOpenCancelsTheCountOnItsId() =
        refusalTest(PassThroughPolicy()) { h ->
            aRefusedReqCancelsTheCountOnItsId(h, """["REQ","x",{"kinds":[9]}]""", """["CLOSED","x","error: search is down"]""")
        }

    @Test
    fun aCountThePolicyRefusesEndsTheReqOnItsId() =
        refusalTest(NoKind9()) { h ->
            h.session.receive("""["REQ","x",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(1, h.backend.live.liveSubscriptionCount)

            h.session.receive("""["COUNT","x",{"kinds":[9]}]""")
            testScheduler.advanceUntilIdle()
            // A live event the REQ would have streamed after its id was CLOSED.
            h.backend.live.submit(Event("2".padStart(64, '0'), pubkey, 1002, 1, emptyArray(), "late", sig)) {}
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("""["EOSE","x"]""", """["CLOSED","x","blocked: no kind 9"]"""), h.frames.on("x"))
            assertTrue(h.frames.raw.isEmpty(), "the REQ streamed after its id was CLOSED: ${h.frames.raw}")
            assertEquals(0, h.backend.live.liveSubscriptionCount)
        }

    @Test
    fun aBackendErrorOpeningAReqIsClosedAndTheConnectionLives() =
        refusalTest(PassThroughPolicy(), Backend().also { it.refuseOpen = NotImplementedError("not yet") }) { h ->
            h.session.receive("""["REQ","x",{"kinds":[9]}]""")
            testScheduler.advanceUntilIdle()
            h.session.receive("""["REQ","y",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("""["CLOSED","x","error: not yet"]"""), h.frames.on("x"))
            assertEquals(listOf("""["EOSE","y"]"""), h.frames.on("y"))
        }
}
