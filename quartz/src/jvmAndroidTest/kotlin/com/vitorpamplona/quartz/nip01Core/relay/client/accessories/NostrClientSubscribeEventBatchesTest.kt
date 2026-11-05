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
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrClientSubscribeEventBatchesTest {
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
    private val resume = CaughtUpResume(nowInSeconds = { clock })

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private fun note(createdAt: Long): Event = signer.sign(createdAt, 1, emptyArray(), "note $createdAt")

    private fun batches(
        maxBatch: Int = 500,
        maxPending: Int = 10_000,
    ) = client.subscribeEventBatches(relay, listOf(Filter(kinds = listOf(1), since = 500)), resume, maxBatch = maxBatch, maxPending = maxPending)

    private suspend fun awaitReqs(count: Int) = withTimeout(10_000) { while (reqs.size < count) delay(20) }

    private suspend fun awaitPoint(point: Long) = withTimeout(10_000) { resume.caughtUpAtFlow(relay).first { it == point } }

    @Test
    fun storedThenLiveInBatchesEachOnce() =
        runBlocking<Unit> {
            val stored = (0 until 30).map { note(900_000L + it) }
            hub.getOrCreate(relay).preload(*stored.toTypedArray())
            val got = Channel<List<Event>>(Channel.UNLIMITED)
            val job = scope.launch { batches(maxBatch = 8).collect { got.send(it) } }

            val seen = mutableListOf<Event>()
            withTimeout(10_000) { while (seen.size < 30) seen += got.receive().also { assertTrue(it.size in 1..8, "batch of ${it.size}") } }
            assertEquals(stored.map { it.id }.toSet(), seen.map { it.id }.toSet())
            assertEquals(30, seen.size, "each once")

            awaitPoint(900_029)
            val fresh = note(950_000)
            hub.getOrCreate(relay).publish(fresh)
            assertEquals(listOf(fresh.id), withTimeout(5_000) { got.receive() }.map { it.id })
            job.cancel()
        }

    @Test
    fun thePointCountsOnlyWhatTheCollectorProcessed() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(*(0 until 10).map { note(900_000L + it) }.toTypedArray())
            val holding = Channel<Int>(Channel.UNLIMITED)
            val gates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
            val job =
                scope.launch {
                    batches().collect { batch ->
                        val gate = CompletableDeferred<Unit>()
                        gates.send(gate)
                        holding.send(batch.size)
                        gate.await()
                    }
                }

            withTimeout(5_000) { holding.receive() }
            delay(300) // the EOSE is in, behind the stored events
            assertNull(resume.caughtUpAt(relay), "stored events count at EOSE, and only once processed")
            withTimeout(5_000) { gates.receive() }.complete(Unit)
            // The rest of the stored batch, if the relay split it, then the EOSE.
            withTimeout(10_000) {
                while (resume.caughtUpAt(relay) != 900_009L) {
                    val next = gates.tryReceive().getOrNull()
                    if (next == null) {
                        delay(20)
                    } else {
                        holding.receive()
                        next.complete(Unit)
                    }
                }
            }

            hub.getOrCreate(relay).publish(note(950_000))
            withTimeout(5_000) { holding.receive() }
            delay(300)
            assertEquals(900_009L, resume.caughtUpAt(relay), "a live event held by the collector doesn't move the point")
            withTimeout(5_000) { gates.receive() }.complete(Unit)
            awaitPoint(950_000)
            job.cancel()
        }

    @Test
    fun aReconnectResumesFromWhatWasProcessedNotWhatWasHanded() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(note(900_000))
            val held = CompletableDeferred<Unit>()
            val holding = CompletableDeferred<Unit>()
            val job =
                scope.launch {
                    batches().collect { batch ->
                        if (batch.any { it.createdAt == 950_000L }) {
                            holding.complete(Unit)
                            held.await()
                        }
                    }
                }
            awaitReqs(1)
            awaitPoint(900_000)

            hub.getOrCreate(relay).publish(note(950_000))
            withTimeout(5_000) { holding.await() }
            builder.dropAll()

            awaitReqs(2)
            assertEquals(900_000L, reqs[1].filters.single().since, "the event still being processed is asked for again")
            held.complete(Unit)
            awaitPoint(950_000)
            job.cancel()
        }

    @Test
    fun caughtUpAtFlowIsThePointToSave() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(note(900_000))
            val saved = CopyOnWriteArrayList<Long>()
            val saver = scope.launch { resume.caughtUpAtFlow(relay).filterNotNull().collect { saved += it } }
            val job = scope.launch { batches().collect { } }
            awaitPoint(900_000)
            hub.getOrCreate(relay).publish(note(950_000))
            awaitPoint(950_000)
            delay(100)
            assertEquals(listOf(900_000L, 950_000L), saved.toList())
            job.cancel()
            saver.cancel()
        }

    @Test
    fun aCollectorThatStopsLeavesTheConnectionServingOthers() =
        runBlocking<Unit> {
            val other = Channel<Event>(Channel.UNLIMITED)
            val otherJob = scope.launch { client.subscribeEvents(relay, listOf(Filter(kinds = listOf(1), since = 500)), CaughtUpResume()).collect { other.send(it) } }
            // Room for one waiting event: the next delivery waits on this collector, stuck on its first batch.
            val stuckJob = scope.launch { batches(maxPending = 1).collect { awaitCancellation() } }
            awaitReqs(2)

            val first = note(clock)
            hub.getOrCreate(relay).publish(first)
            assertEquals(first.id, withTimeout(5_000) { other.receive() }.id)
            // The stuck flow holds `first` and has no room left: delivering `second` waits on it,
            // and so does every subscription on the connection.
            val second = note(clock + 1)
            hub.getOrCreate(relay).publish(second)
            delay(300)
            stuckJob.cancel()

            assertEquals(second.id, withTimeout(5_000) { other.receive() }.id, "the wait gave up when the collector stopped")
            val third = note(clock + 2)
            hub.getOrCreate(relay).publish(third)
            assertEquals(third.id, withTimeout(5_000) { other.receive() }.id, "the connection still delivers")
            otherJob.cancel()
        }

    @Test
    fun aCollectorFallingBehindHoldsTheRelayAtMaxPending() =
        runBlocking<Unit> {
            val other = Channel<Event>(Channel.UNLIMITED)
            val otherJob = scope.launch { client.subscribeEvents(relay, listOf(Filter(kinds = listOf(1), since = 500)), CaughtUpResume()).collect { other.send(it) } }
            val stuckJob = scope.launch { batches(maxPending = 2).collect { awaitCancellation() } }
            awaitReqs(2)

            // Room for two: e0 and e1 wait in the stuck flow, and e2's delivery waits for room,
            // holding the connection. Each event reaches the two subscriptions in an order of the
            // relay's choosing, so e2 may reach the other one first; e3 can't.
            val events = (0 until 4).map { note(clock + it) }
            events.forEach { hub.getOrCreate(relay).publish(it) }
            val got = mutableSetOf<String>()
            withTimeout(5_000) { while (got.size < 2) got += other.receive().id }
            delay(500)
            while (true) got += other.tryReceive().getOrNull()?.id ?: break
            assertTrue(events[3].id !in got, "the fourth waits behind the stuck flow's room: $got")
            stuckJob.cancel()
            withTimeout(5_000) { while (events[3].id !in got) got += other.receive().id }
            otherJob.cancel()
        }

    @Test
    fun aClosedSubscriptionEndsTheFlow() =
        runBlocking<Unit> {
            refuseWith = "restricted: members only"
            val outcome = withTimeout(5_000) { runCatching { batches().collect { } }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals("restricted: members only", outcome.reason)
        }

    @Test
    fun anExceptionFromTheCollectorEndsTheFlowWithIt() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(note(900_000))
            val outcome = withTimeout(5_000) { runCatching { batches().collect { error("store down") } }.exceptionOrNull() }
            assertEquals("store down", outcome?.message)
            assertNull(resume.caughtUpAt(relay), "an unprocessed batch doesn't count")
        }
}
