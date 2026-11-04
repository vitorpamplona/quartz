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
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NostrClientFetchEventsTest {
    /** Every REQ the relay accepted. */
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

    private val hub = InProcessRelays(defaultPolicy = { RecordingPolicy() })
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = NostrClient(hub, scope)
    private val relay = InProcessRelays.DEFAULT_URL
    private val signer = NostrSignerSync()
    private val kind1 = Filter(kinds = listOf(1))

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private fun note(createdAt: Long): Event = signer.sign(createdAt, 1, emptyArray(), "note $createdAt")

    @Test
    fun emitsEveryStoredEventThenCompletesAtEose() =
        runBlocking<Unit> {
            val stored = (1..50L).map { note(1_000 + it) }
            hub.getOrCreate(relay).preload(stored)

            val received = withTimeout(5_000) { client.fetchEvents(relay, kind1).toList() }

            assertEquals(stored.map { it.id }.toSet(), received.map { it.id }.toSet())
            assertEquals(stored.size, received.size, "each stored event once")
            assertEquals(1, reqs.size, "one REQ")
        }

    @Test
    fun anEmptyRelayCompletesWithNothing() =
        runBlocking<Unit> {
            val received = withTimeout(5_000) { client.fetchEvents(relay, kind1).toList() }
            assertEquals(emptyList(), received)
        }

    @Test
    fun theSubscriptionIsClosedAtEose() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(note(1_000))
            withTimeout(5_000) { client.fetchEvents(relay, kind1).toList() }
            val subId = reqs.single().subId
            assertTrue(subId.startsWith("fetch-"))
            withTimeout(5_000) { while (client.getReqFiltersOrNull(subId) != null) delay(20) }
        }

    @Test
    fun aSlowCollectorLosesNothing() =
        runBlocking<Unit> {
            val stored = (1..200L).map { note(1_000 + it) }
            hub.getOrCreate(relay).preload(stored)

            // No buffer: every send waits for the collector.
            val received =
                withTimeout(20_000) {
                    val out = mutableListOf<Event>()
                    client.fetchEvents(relay, kind1).buffer(Channel.RENDEZVOUS).collect {
                        if (out.size % 50 == 0) delay(50)
                        out += it
                    }
                    out
                }
            assertEquals(stored.map { it.id }.toSet(), received.map { it.id }.toSet())
        }

    @Test
    fun fetchHandsEachEventToTheCallbackAndCountsThem() =
        runBlocking<Unit> {
            val stored = (1..20L).map { note(1_000 + it) }
            hub.getOrCreate(relay).preload(stored)
            val seen = mutableListOf<String>()

            val delivered = withTimeout(5_000) { client.fetch(relay, kind1) { seen += it.id } }

            assertEquals(stored.size, delivered)
            assertEquals(stored.map { it.id }.toSet(), seen.toSet())
        }

    @Test
    fun anExceptionInFetchsCallbackEndsTheFetchAndClosesTheReq() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload((1..10L).map { note(1_000 + it) })
            val seen = AtomicInteger(0)

            val outcome =
                withTimeout(5_000) {
                    runCatching { client.fetch(relay, kind1) { if (seen.incrementAndGet() == 3) error("consumer failed") } }.exceptionOrNull()
                }

            assertIs<IllegalStateException>(outcome)
            assertEquals("consumer failed", outcome.message)
            assertEquals(3, seen.get(), "nothing is handed over after the callback throws")
            val subId = reqs.single().subId
            withTimeout(5_000) { while (client.getReqFiltersOrNull(subId) != null) delay(20) }
        }

    @Test
    fun fetchInlineHandsEachEventToTheCallbackAndCountsThem() =
        runBlocking<Unit> {
            val stored = (1..20L).map { note(1_000 + it) }
            hub.getOrCreate(relay).preload(stored)
            val seen = mutableListOf<String>()

            val delivered = withTimeout(5_000) { client.fetchInline(relay, kind1) { seen += it.id } }

            assertEquals(stored.size, delivered)
            assertEquals(stored.map { it.id }.toSet(), seen.toSet())
            val subId = reqs.single().subId
            withTimeout(5_000) { while (client.getReqFiltersOrNull(subId) != null) delay(20) }
        }

    @Test
    fun anExceptionInFetchInlinesCallbackEndsItAndClosesTheReq() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload((1..10L).map { note(1_000 + it) })
            val seen = AtomicInteger(0)

            val outcome =
                withTimeout(5_000) {
                    runCatching { client.fetchInline(relay, kind1) { if (seen.incrementAndGet() == 3) error("consumer failed") } }.exceptionOrNull()
                }

            assertIs<IllegalStateException>(outcome)
            assertEquals("consumer failed", outcome.message)
            assertEquals(3, seen.get(), "nothing is handed over after the callback throws")
            val subId = reqs.single().subId
            withTimeout(5_000) { while (client.getReqFiltersOrNull(subId) != null) delay(20) }
        }

    @Test
    fun aClosedReqEndsFetchInlineWithTheReason() =
        runBlocking<Unit> {
            refuseWith = "restricted: members only"
            val outcome = withTimeout(5_000) { runCatching { client.fetchInline(relay, kind1) { } }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals("restricted: members only", outcome.reason)
        }

    @Test
    fun aClosedReqEndsWithTheReason() =
        runBlocking<Unit> {
            refuseWith = "restricted: members only"
            val outcome = withTimeout(5_000) { runCatching { client.fetchEvents(relay, kind1).toList() }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals(relay, outcome.relay)
            assertEquals("restricted: members only", outcome.reason)
        }

    @Test
    fun anAuthChallengeWithNobodyToAnswerItEndsAtOnce() =
        runBlocking<Unit> {
            refuseWith = "auth-required: sign in"
            val outcome = withTimeout(5_000) { runCatching { client.fetchEvents(relay, kind1).toList() }.exceptionOrNull() }
            assertIs<SubscriptionClosedException>(outcome)
            assertEquals("auth-required: sign in", outcome.reason)
        }

    @Test
    fun aRateLimitWithABackOffIsWaitedOut() =
        runBlocking<Unit> {
            rateLimitedReqs.set(1)
            val stored = note(1_000)
            hub.getOrCreate(relay).preload(stored)

            val received = withTimeout(5_000) { client.fetchEvents(relay, kind1).toList() }

            assertEquals(listOf(stored.id), received.map { it.id }, "the client retried after the back-off")
        }

    @Test
    fun anUnreachableRelayEndsWithRelayUnreachable() =
        runBlocking<Unit> {
            val offline = "ws://127.0.0.1:1/".normalizeRelayUrl()
            val socketClient = NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() }, scope)
            try {
                val outcome = withTimeout(5_000) { runCatching { socketClient.fetchEvents(offline, kind1).toList() }.exceptionOrNull() }
                assertIs<RelayUnreachableException>(outcome)
                assertEquals(offline, outcome.relay)
            } finally {
                socketClient.disconnect()
            }
        }

    @Test
    fun eachCollectionSendsItsOwnReq() =
        runBlocking<Unit> {
            hub.getOrCreate(relay).preload(note(1_000))
            val flow = client.fetchEvents(relay, kind1)
            withTimeout(5_000) { flow.toList() }
            withTimeout(5_000) { flow.toList() }
            assertEquals(2, reqs.size)
            assertTrue(reqs[0].subId != reqs[1].subId)
        }

    @Test
    fun cancellingTheCollectionClosesTheReq() =
        runBlocking<Unit> {
            // A collector that never finishes the first event: the REQ is open until it is cancelled.
            hub.getOrCreate(relay).preload((1..10L).map { note(1_000 + it) })
            val job = scope.launch { client.fetchEvents(relay, kind1).buffer(Channel.RENDEZVOUS).collect { delay(Long.MAX_VALUE) } }
            withTimeout(5_000) { while (reqs.isEmpty()) delay(20) }
            val subId = reqs.single().subId
            job.cancel()
            job.join()
            withTimeout(5_000) { while (client.getReqFiltersOrNull(subId) != null) delay(20) }
        }
}
