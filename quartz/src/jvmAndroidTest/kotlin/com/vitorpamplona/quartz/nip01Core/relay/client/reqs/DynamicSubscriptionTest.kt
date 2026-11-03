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
package com.vitorpamplona.quartz.nip01Core.relay.client.reqs

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
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicSubscriptionTest {
    /** Every REQ the relay accepted, across connections. */
    private val reqs = CopyOnWriteArrayList<ReqCmd>()

    /** How many REQs to refuse as `restricted:` before accepting. */
    private val refusals = AtomicInteger(0)

    private inner class RecordingPolicy : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> {
            if (refusals.getAndDecrement() > 0) return PolicyResult.Rejected("restricted: not now")
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

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private suspend fun awaitReqs(count: Int) = withTimeout(10_000) { while (reqs.size < count) delay(20) }

    /** A stored note on the relay, created [secondsAgo] before now. */
    private suspend fun stored(secondsAgo: Long): Event =
        NostrSignerSync().sign<Event>(TimeUtils.now() - secondsAgo, 1, emptyArray(), "stored $secondsAgo").also {
            hub.getOrCreate(relay).preload(it)
        }

    @Test
    fun aReconnectSendsWhatTheLastRefreshSentWithoutCallingTheLambda() =
        runBlocking<Unit> {
            val calls = AtomicInteger(0)
            var since = 500L
            val sub =
                DynamicSubscription(client, {
                    calls.incrementAndGet()
                    mapOf(relay to listOf(Filter(kinds = listOf(1), since = since)))
                }, resume = null)
            awaitReqs(1)
            assertEquals(500L, reqs[0].filters.single().since)
            val callsBefore = calls.get()

            since = 900L // no refresh
            builder.dropAll()
            awaitReqs(2)
            assertEquals(500L, reqs[1].filters.single().since, "what the last refresh sent")
            assertEquals(callsBefore, calls.get(), "the lambda runs only on refresh, on the caller's thread")
            sub.close()
        }

    @Test
    fun aRelayTheLambdaNoLongerNamesKeepsItsFiltersAndResumes() =
        runBlocking<Unit> {
            val note = stored(100)
            val resume = CaughtUpResume()
            var relays = setOf(relay)
            val sub = DynamicSubscription(client, { relays.associateWith { listOf(Filter(kinds = listOf(1), since = 500)) } }, resume)
            awaitReqs(1)
            awaitEose(resume)

            relays = emptySet() // no refresh: still open there, with the filters it was given
            builder.dropAll()
            awaitReqs(2)
            assertEquals(note.createdAt, reqs[1].filters.single().since, "resumed, not its whole history again")
            sub.close()
        }

    /** Waits for the subscription's first EOSE, with something delivered, to have been seen. */
    private suspend fun awaitEose(resume: CaughtUpResume) = withTimeout(10_000) { while (resume.caughtUpAt(relay) == null) delay(20) }

    @Test
    fun aCallbackSubscriptionResumesAfterAReconnect() =
        runBlocking<Unit> {
            val note = stored(100)
            val resume = CaughtUpResume()
            val sub = DynamicSubscription(client, { mapOf(relay to listOf(Filter(kinds = listOf(1)))) }, resume)
            awaitReqs(1)
            assertNull(reqs[0].filters.single().since, "the first REQ as given")
            awaitEose(resume)
            assertEquals(note.createdAt, resume.caughtUpAt(relay), "the newest event delivered, not the time it arrived")

            builder.dropAll()
            awaitReqs(2)
            assertEquals(note.createdAt, reqs[1].filters.single().since, "from the newest event delivered, not the whole history")
            sub.close()
        }

    @Test
    fun theFixedFilterOverloadResumesToo() =
        runBlocking<Unit> {
            val note = stored(100)
            val sub = client.subscribe(relay, Filter(kinds = listOf(1))) { }
            awaitReqs(1)
            delay(300) // EOSE seen
            builder.dropAll()
            awaitReqs(2)
            assertEquals(note.createdAt, reqs[1].filters.single().since)
            sub.close()
        }

    @Test
    fun theDeprecatedStaticSubscriptionResumesToo() =
        runBlocking<Unit> {
            val note = stored(100)

            @Suppress("DEPRECATION")
            val sub = StaticSubscription(client, mapOf(relay to listOf(Filter(kinds = listOf(1)))))
            awaitReqs(1)
            delay(300) // EOSE seen
            builder.dropAll()
            awaitReqs(2)
            assertEquals(note.createdAt, reqs[1].filters.single().since)
            sub.close()
        }

    @Test
    fun filtersChangedByARefreshAreSentAsGiven() =
        runBlocking<Unit> {
            stored(100)
            val resume = CaughtUpResume()
            var authors = listOf("a".repeat(64))
            val sub = DynamicSubscription(client, { mapOf(relay to listOf(Filter(kinds = listOf(1), authors = authors, since = 500))) }, resume)
            awaitReqs(1)
            delay(300) // EOSE seen

            authors = authors + "b".repeat(64) // a new author: its history must still be asked for
            sub.refresh()
            awaitReqs(2)
            assertEquals(500L, reqs[1].filters.single().since)
            assertEquals(
                2,
                reqs[1]
                    .filters
                    .single()
                    .authors
                    ?.size,
            )
            sub.close()
        }

    @Test
    fun aClosedCallbackSubscriptionComesBackOnTheNextReconnect() =
        runBlocking<Unit> {
            refusals.set(1)
            val received = CopyOnWriteArrayList<Event>()
            val stored = NostrSignerSync().sign<Event>(TimeUtils.now() - 10, 1, emptyArray(), "stored")
            hub.getOrCreate(relay).preload(stored)
            val sub = client.subscribe(relay, Filter(kinds = listOf(1))) { received += it }
            delay(500)
            assertTrue(received.isEmpty(), "the relay refused the first REQ")

            builder.dropAll()
            withTimeout(10_000) { while (received.isEmpty()) delay(20) }
            assertEquals(stored.id, received.single().id)
            sub.close()
        }

    @Test
    fun aListFlowResumesAndOutlivesAClosed() =
        runBlocking<Unit> {
            refusals.set(1)
            val stored = NostrSignerSync().sign<Event>(TimeUtils.now() - 10, 1, emptyArray(), "stored")
            hub.getOrCreate(relay).preload(stored)
            val lists = Channel<List<Event>>(Channel.UNLIMITED)
            val job = scope.launch { client.subscribeAsFlow(relay, Filter(kinds = listOf(1))).collect { lists.send(it) } }
            delay(500)
            assertTrue(job.isActive, "a CLOSED doesn't end the list")
            assertTrue(lists.tryReceive().isFailure, "the relay refused the first REQ")

            builder.dropAll() // the reconnect sends it again
            assertEquals(stored.id, withTimeout(10_000) { lists.receive() }.single().id)

            // Now caught up, a further reconnect resumes instead of asking for everything again.
            delay(300)
            val count = reqs.size
            builder.dropAll()
            awaitReqs(count + 1)
            assertTrue(
                reqs
                    .last()
                    .filters
                    .single()
                    .since != null,
                "the re-sent REQ resumes",
            )
            job.cancel()
        }
}
