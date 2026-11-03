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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.TestRelay
import com.vitorpamplona.quartz.testing.preload
import com.vitorpamplona.quartz.testing.publish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class NostrClientSubscribeEventsTest {
    /** Every REQ the relay accepted, across connections. */
    private val reqs = CopyOnWriteArrayList<ReqCmd>()
    private var refuseWith: String? = null

    /** How many REQs to refuse as `rate-limited:` with a back-off before accepting. */
    private val rateLimitedReqs = AtomicInteger(0)

    private inner class RecordingPolicy : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> {
            refuseWith?.let { return PolicyResult.Rejected(it) }
            if (rateLimitedReqs.getAndDecrement() > 0) return PolicyResult.Rejected.rateLimited("slow down", retryAfterMs = 300)
            reqs += cmd
            return PolicyResult.Accepted(cmd)
        }
    }

    /** The hub, keeping each socket it builds so a test can drop the connection under the client. */
    private class DroppableBuilder(
        private val delegate: WebsocketBuilder,
    ) : WebsocketBuilder {
        val sockets = CopyOnWriteArrayList<WebSocket>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket = delegate.build(url, out).also { sockets += it }

        fun dropAll() = sockets.forEach { it.disconnect() }
    }

    private val hub = InProcessRelays(defaultPolicy = { RecordingPolicy() })
    private val builder = DroppableBuilder(hub)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = NostrClient(builder, scope)
    private val relay = InProcessRelays.DEFAULT_URL
    private val signer = NostrSignerSync()
    private var clock = 1_000_000L

    /** The resume policy every stream here uses unless a test passes its own, on the test's clock. */
    private val resume = CaughtUpResume(nowInSeconds = { clock })

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private fun note(createdAt: Long): Event = signer.sign(createdAt, 1, emptyArray(), "note $createdAt")

    private fun live(since: Long? = null) = live(listOf(Filter(kinds = listOf(1), since = since)))

    /** The client, counting how often the stored filters are rewritten. */
    private val counting =
        object : INostrClient by client {
            val subscribes = AtomicInteger(0)

            override fun subscribe(
                subId: String,
                filters: Map<NormalizedRelayUrl, List<Filter>>,
                listener: SubscriptionListener?,
            ) {
                subscribes.incrementAndGet()
                client.subscribe(subId, filters, listener)
            }
        }

    private fun live(
        filters: List<Filter>,
        resume: ResumePolicy? = this.resume,
    ) = counting.subscribeEvents(
        relay = relay,
        filters = filters,
        resume = resume,
    )

    private suspend fun awaitReqs(count: Int) = withTimeout(10_000) { while (reqs.size < count) delay(20) }

    /** Waits until the stream has reported being caught up at [point]. */
    private suspend fun awaitResumePoint(point: Long) = withTimeout(10_000) { while (resume.caughtUpAt(relay) != point) delay(20) }

    @Test
    fun storedEventsThenLiveOnes() =
        runBlocking<Unit> {
            val stored = note(100)
            hub.getOrCreate(relay).preload(stored)
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { live().collect { received.send(it) } }

            assertEquals(stored.id, withTimeout(5_000) { received.receive() }.id)
            awaitResumePoint(clock) // EOSE seen: the subscription is live
            val fresh = note(200)
            hub.getOrCreate(relay).publish(fresh)
            assertEquals(fresh.id, withTimeout(5_000) { received.receive() }.id)
            job.cancel()
        }

    @Test
    fun aReconnectResumesFromWhenTheSubscriptionWasCaughtUp() =
        runBlocking<Unit> {
            val job = scope.launch { live(since = 500).collect { } }
            awaitReqs(1)
            assertEquals(500L, reqs[0].filters.single().since)
            awaitResumePoint(1_000_000) // EOSE at clock 1_000_000: stored without a REQ

            clock += 3_600 // an hour with nothing arriving: still caught up only at 1_000_000
            delay(400) // several since-update ticks, none of which may move it
            builder.dropAll()

            awaitReqs(2)
            assertEquals(1_000_000L, reqs[1].filters.single().since, "resume from the last time it was caught up")
            job.cancel()
        }

    @Test
    fun aLiveEventMovesSinceWithoutANewReq() =
        runBlocking<Unit> {
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { live(since = 500).collect { received.send(it) } }
            awaitResumePoint(1_000_000)

            clock += 1_000
            hub.getOrCreate(relay).publish(note(clock))
            withTimeout(5_000) { received.receive() }
            awaitResumePoint(clock)
            assertEquals(1, reqs.size, "a since-only change is stored, not sent")

            builder.dropAll()
            awaitReqs(2)
            assertEquals(clock, reqs[1].filters.single().since)
            job.cancel()
        }

    @Test
    fun nothingResubscribesTheResumePointRidesTheResend() =
        runBlocking<Unit> {
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { live(since = 500).collect { received.send(it) } }
            awaitResumePoint(1_000_000)
            repeat(5) {
                clock += 100
                hub.getOrCreate(relay).publish(note(clock))
                withTimeout(5_000) { received.receive() }
            }
            awaitResumePoint(clock)
            assertEquals(1, counting.subscribes.get(), "live events move the resume point in memory only")

            builder.dropAll()
            awaitReqs(2)
            assertEquals(1, counting.subscribes.get(), "the pool asks for the filters as it re-sends; nothing resubscribes")
            assertEquals(clock, reqs[1].filters.single().since)
            job.cancel()
        }

    @Test
    fun aNullPolicyNeverRewritesTheFilters() =
        runBlocking<Unit> {
            val job = scope.launch { live(listOf(Filter(kinds = listOf(1), since = 500)), resume = null).collect { } }
            awaitReqs(1)
            delay(300) // EOSE seen
            builder.dropAll()
            awaitReqs(2)
            assertEquals(500L, reqs[1].filters.single().since, "the filters re-sent as given")
            assertEquals(1, counting.subscribes.get())
            job.cancel()
        }

    @Test
    fun aReconnectAsksThePolicyAndSendsItsAnswer() =
        runBlocking<Unit> {
            val asked = AtomicInteger(0)
            var answer: Long? = null
            val fixed =
                object : ResumePolicy {
                    override fun onCaughtUp(
                        relay: NormalizedRelayUrl,
                        filters: List<Filter>?,
                    ) {}

                    override fun resumeFrom(
                        relay: NormalizedRelayUrl,
                        filters: List<Filter>,
                    ): Long? {
                        asked.incrementAndGet()
                        return answer
                    }
                }
            val job = scope.launch { live(listOf(Filter(kinds = listOf(1), since = 500)), resume = fixed).collect { } }
            awaitReqs(1)
            assertEquals(500L, reqs[0].filters.single().since, "no answer yet: the filters as given")

            answer = 123_456
            val before = asked.get()
            builder.dropAll()
            awaitReqs(2)
            assertEquals(before + 1, asked.get(), "asked once, for the re-sent REQ")
            assertEquals(123_456L, reqs[1].filters.single().since)
            job.cancel()
        }

    @Test
    fun eachFilterStartsFromItsOwnSinceAndNeverResumesEarlier() =
        runBlocking<Unit> {
            val later = 2_000_000L // past the resume point the reconnect will use
            val job = scope.launch { live(listOf(Filter(kinds = listOf(1), since = 500), Filter(kinds = listOf(7), since = later))).collect { } }
            awaitReqs(1)
            assertEquals(listOf(500L, later), reqs[0].filters.map { it.since }, "the first REQ asks for the filters as given")
            awaitResumePoint(1_000_000)

            builder.dropAll()
            awaitReqs(2)
            assertEquals(listOf(1_000_000L, later), reqs[1].filters.map { it.since }, "each moves to the resume point, never earlier than its own")
            job.cancel()
        }

    @Test
    fun aCollectorThatStopsMidSendLeavesTheConnectionServingOthers() =
        runBlocking<Unit> {
            val other = Channel<Event>(Channel.UNLIMITED)
            val otherJob = scope.launch { live().collect { other.send(it) } }
            // No buffer: the second event's send suspends on the connection's delivery coroutine
            // while this collector is stuck on the first.
            val stuckJob = scope.launch { live().buffer(Channel.RENDEZVOUS).collect { awaitCancellation() } }
            awaitReqs(2)

            val first = note(clock)
            hub.getOrCreate(relay).publish(first)
            assertEquals(first.id, withTimeout(5_000) { other.receive() }.id)
            val second = note(clock + 1)
            hub.getOrCreate(relay).publish(second)
            delay(300) // the delivery coroutine is now suspended in the stuck flow's send
            stuckJob.cancel()

            assertEquals(second.id, withTimeout(5_000) { other.receive() }.id)
            val third = note(clock + 2)
            hub.getOrCreate(relay).publish(third)
            assertEquals(third.id, withTimeout(5_000) { other.receive() }.id, "the connection still delivers")
            otherJob.cancel()
        }

    @Test
    fun aRateLimitWithABackOffKeepsTheFlow() =
        runBlocking<Unit> {
            rateLimitedReqs.set(1)
            val stored = note(100)
            hub.getOrCreate(relay).preload(stored)
            val received = Channel<Event>(Channel.UNLIMITED)
            val outcome = scope.async { runCatching { live().collect { received.send(it) } } }

            assertEquals(stored.id, withTimeout(5_000) { received.receive() }.id, "the client retried after the back-off")
            assertTrue(outcome.isActive, "a retried rate limit must not end the flow")
            outcome.cancel()
        }

    @Test
    fun aResumedReqIsNotUndoneAtItsEoseAndTheNextReconnectResumesAgain() =
        runBlocking<Unit> {
            val job = scope.launch { live(since = 500).collect { } }
            awaitResumePoint(1_000_000)

            clock += 1_000
            builder.dropAll()
            awaitReqs(2)
            assertEquals(1_000_000L, reqs[1].filters.single().since)
            awaitResumePoint(clock) // the resumed REQ's EOSE
            delay(300)
            assertEquals(2, reqs.size, "the resumed REQ's EOSE sends nothing: the pool knows it stands for since = 500")

            builder.dropAll()
            awaitReqs(3)
            assertEquals(clock, reqs[2].filters.single().since, "resumed again from the new point")
            delay(300)
            assertEquals(3, reqs.size)
            job.cancel()
        }

    @Test
    fun severalRelaysInOneFlowEachResumeFromTheirOwnPoint() =
        runBlocking<Unit> {
            val other = RelayUrlNormalizer.normalize("ws://127.0.0.1:7771/")
            val a = note(100)
            val b = note(101)
            hub.getOrCreate(relay).preload(a)
            hub.getOrCreate(other).preload(b)
            val filters = listOf(Filter(kinds = listOf(1), since = 50))
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { counting.subscribeEvents(mapOf(relay to filters, other to filters), resume).collect { received.send(it) } }

            assertEquals(setOf(a.id, b.id), withTimeout(5_000) { setOf(received.receive().id, received.receive().id) })
            withTimeout(10_000) { while (resume.caughtUpAt(relay) == null || resume.caughtUpAt(other) == null) delay(20) }

            clock += 1_000 // a live event on one relay only moves that relay's point
            hub.getOrCreate(other).publish(note(clock))
            withTimeout(5_000) { received.receive() }
            withTimeout(10_000) { while (resume.caughtUpAt(other) != clock) delay(20) }

            builder.dropAll()
            awaitReqs(4)
            assertEquals(setOf(1_000_000L, clock), reqs.drop(2).map { it.filters.single().since }.toSet())
            job.cancel()
        }

    @Test
    fun oneRelaysClosedEndsTheFlowAndNamesIt() =
        runBlocking<Unit> {
            val other = RelayUrlNormalizer.normalize("ws://127.0.0.1:7771/")
            hub.register(
                TestRelay(
                    url = other,
                    policyBuilder = {
                        object : PassThroughPolicy() {
                            override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> = PolicyResult.Rejected("restricted: members only")
                        }
                    },
                ),
            )
            val filters = listOf(Filter(kinds = listOf(1)))
            val outcome = withTimeout(5_000) { runCatching { client.subscribeEvents(mapOf(relay to filters, other to filters)).collect { } }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals(other, outcome.relay)
        }

    @Test
    fun aRelayGoingAwayClosesAndDropsAndTheFlowCarriesOn() =
        runBlocking<Unit> {
            // As nostream does on shutdown: every subscription CLOSEd with `error:`, then the socket.
            refuseWith = "error: relay shutting down"
            val received = Channel<Event>(Channel.UNLIMITED)
            val outcome = scope.async { runCatching { live().collect { received.send(it) } } }
            delay(500) // the CLOSED arrived
            refuseWith = null
            builder.dropAll()

            val fresh = note(clock + 10)
            hub.getOrCreate(relay).preload(fresh)
            assertEquals(fresh.id, withTimeout(10_000) { received.receive() }.id, "served again on the next connection")
            delay(3_500) // past the grace
            assertTrue(outcome.isActive, "a relay going away does not end the flow")
            outcome.cancel()
        }

    @Test
    fun aClosedSubscriptionEndsTheFlow() =
        runBlocking<Unit> {
            refuseWith = "restricted: members only"
            val outcome = withTimeout(5_000) { runCatching { live().collect { } }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals("restricted: members only", outcome.reason)
        }

    @Test
    fun anAuthChallengeWithNobodyToAnswerItEndsAtOnce() =
        runBlocking<Unit> {
            // No NIP-42 responder on this client: nothing will ever sign, so nothing will replay the REQ.
            refuseWith = "auth-required: sign in"
            val (outcome, elapsed) = measureTimedValue { withTimeout(5_000) { runCatching { live().collect { } }.exceptionOrNull() } }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals("auth-required: sign in", outcome.reason)
            assertTrue(elapsed.inWholeMilliseconds < 2_000, "no wait when nobody can answer; was $elapsed")
        }

    @Test
    fun cancellingTheCollectionUnsubscribes() =
        runBlocking<Unit> {
            val job = scope.launch { live().collect { } }
            awaitReqs(1)
            val cancelled = reqs[0].subId
            // Keeps the relay wanted, so the client reconnects after the drop and re-sends every
            // REQ it still holds: a leaked one would come back with it.
            val kept = scope.launch { live().collect { } }
            awaitReqs(2)
            val keptSub = reqs[1].subId
            job.cancel()
            job.join()

            builder.dropAll()
            withTimeout(10_000) { while (reqs.drop(2).none { it.subId == keptSub }) delay(20) }
            delay(300)
            assertTrue(reqs.drop(2).none { it.subId == cancelled }, "nothing re-sends a REQ nobody is collecting")
            kept.cancel()
        }
}
