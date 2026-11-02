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
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.preload
import com.vitorpamplona.quartz.testing.publish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrClientLiveEventsTest {
    /** Every REQ the relay accepted, across connections. */
    private val reqs = CopyOnWriteArrayList<ReqCmd>()
    private var refuseWith: String? = null

    private inner class RecordingPolicy : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> {
            refuseWith?.let { return PolicyResult.Rejected(it) }
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

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private fun note(createdAt: Long): Event = signer.sign(createdAt, 1, emptyArray(), "note $createdAt")

    private fun live(
        since: Long? = null,
        endsOnClosed: (String) -> Boolean = { !it.startsWith("auth-required:") },
    ) = client.liveEvents(
        relay = relay,
        filters = listOf(Filter(kinds = listOf(1))),
        since = since,
        overlapSeconds = 60,
        sinceUpdateIntervalMs = 100,
        endsOnClosed = endsOnClosed,
        nowInSeconds = { clock },
    )

    private suspend fun awaitReqs(count: Int) = withTimeout(10_000) { while (reqs.size < count) delay(20) }

    @Test
    fun storedEventsThenLiveOnes() =
        runBlocking<Unit> {
            val stored = note(100)
            hub.getOrCreate(relay).preload(stored)
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { live().collect { received.send(it) } }

            assertEquals(stored.id, withTimeout(5_000) { received.receive() }.id)
            awaitReqs(1)
            delay(200)
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
            delay(400) // EOSE at clock 1_000_000: since moves to 999_940, stored without a REQ

            clock += 3_600 // an hour with nothing arriving: still caught up only at 1_000_000
            delay(400)
            builder.dropAll()

            awaitReqs(2)
            assertEquals(999_940L, reqs[1].filters.single().since, "resume from the last time it was caught up, minus the overlap")
            job.cancel()
        }

    @Test
    fun aLiveEventMovesSinceWithoutANewReq() =
        runBlocking<Unit> {
            val received = Channel<Event>(Channel.UNLIMITED)
            val job = scope.launch { live(since = 500).collect { received.send(it) } }
            awaitReqs(1)
            delay(200)

            clock += 1_000
            hub.getOrCreate(relay).publish(note(clock))
            withTimeout(5_000) { received.receive() }
            delay(400)
            assertEquals(1, reqs.size, "a since-only change is stored, not sent")

            builder.dropAll()
            awaitReqs(2)
            assertEquals(clock - 60, reqs[1].filters.single().since)
            job.cancel()
        }

    @Test
    fun aClosedSubscriptionEndsTheFlow() =
        runBlocking<Unit> {
            refuseWith = "restricted: members only"
            val outcome = withTimeout(5_000) { runCatching { live().collect { } }.exceptionOrNull() }
            assertIs<LiveEventsClosedException>(outcome)
            assertEquals("restricted: members only", outcome.reason)
        }

    @Test
    fun anAuthChallengeDoesNotEndTheFlowByDefault() =
        runBlocking<Unit> {
            refuseWith = "auth-required: sign in"
            val ended = scope.async { runCatching { live().collect { } } }
            assertNull(withTimeoutOrNull(1_000) { ended.await() }, "the client may still authenticate and replay the REQ")
            ended.cancel()

            val outcome = withTimeout(5_000) { runCatching { live(endsOnClosed = { true }).collect { } }.exceptionOrNull() }
            assertIs<LiveEventsClosedException>(outcome)
        }

    @Test
    fun cancellingTheCollectionUnsubscribes() =
        runBlocking<Unit> {
            val job = scope.launch { live().collect { } }
            awaitReqs(1)
            job.cancel()
            job.join()
            delay(200)
            builder.dropAll()
            delay(2_500)
            assertEquals(1, reqs.size, "nothing re-sends a REQ nobody is collecting")
            assertTrue(job.isCancelled)
        }
}
