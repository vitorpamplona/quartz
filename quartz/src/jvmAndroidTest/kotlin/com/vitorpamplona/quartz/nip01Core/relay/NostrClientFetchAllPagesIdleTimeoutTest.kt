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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllPages
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Pins [fetchAllPages]'s timeout to the package-wide idle-window convention:
 * `idleTimeoutMs` is silence measured from the relay's MOST RECENT message, not a
 * wall-clock deadline for the page. A relay that keeps streaming — however
 * slowly — must never have a page cropped mid-delivery.
 *
 * Real-clock ([runBlocking]) on purpose: the page watchdog is a monotonic
 * `IdleClock` bumped from the socket reader thread, which virtual time can't
 * exercise.
 */
class NostrClientFetchAllPagesIdleTimeoutTest {
    /** Captures the subscription listener so the test can play a relay. */
    private class ScriptedClient : INostrClient by EmptyNostrClient() {
        @Volatile
        var listener: SubscriptionListener? = null

        @Volatile
        var subscribeCount = 0

        override fun subscribe(
            subId: String,
            filters: Map<NormalizedRelayUrl, List<Filter>>,
            listener: SubscriptionListener?,
        ) {
            subscribeCount++
            this.listener = listener
        }
    }

    private val relay = RelayUrlNormalizer.normalize("wss://slow.example.com")

    private fun event(
        i: Int,
        createdAt: Long = i.toLong(),
    ) = Event(
        id = i.toString(16).padStart(64, '0'),
        pubKey = "f".repeat(64),
        createdAt = createdAt,
        kind = 1,
        tags = emptyArray(),
        content = "e$i",
        sig = "0".repeat(128),
    )

    @Test
    fun streamingPageOutlivesTheIdleWindow() =
        runBlocking {
            val client = ScriptedClient()
            val feeder =
                launch {
                    // 6 events, each arriving 150ms apart — every gap is under the
                    // 500ms idle window, but the whole page (~950ms) far exceeds it.
                    // The old hard per-page deadline truncated this mid-stream and
                    // re-subscribed for another page; the idle window must not.
                    repeat(6) { i ->
                        delay(150)
                        client.listener!!.onEvent(event(i + 1), false, relay, null)
                    }
                    delay(50)
                    client.listener!!.onEose(relay, null)
                }

            var pages = 0
            val got = mutableListOf<Event>()
            val total =
                client.fetchAllPages(
                    relay = relay,
                    filters = listOf(Filter(kinds = listOf(1), limit = 6)),
                    idleTimeoutMs = 500,
                    onNewPage = { pages++ },
                ) { got.add(it) }
            feeder.join()

            assertEquals(6, total.downloaded, "a slowly-but-actively streaming page must never be cropped")
            assertEquals(6, got.size)
            assertEquals(1, client.subscribeCount, "the whole stream must arrive in ONE page — a hard deadline would truncate and re-subscribe")
            assertEquals(0, pages, "no pagination should be needed")
        }

    @Test
    fun silentRelayGivesUpOneIdleWindowAfterItsLastMessage() =
        runBlocking {
            val client = ScriptedClient()
            val feeder =
                launch {
                    // Two quick events, then the relay goes quiet without EOSE: the
                    // page must end ~one idle window after the LAST message.
                    delay(50)
                    client.listener!!.onEvent(event(1), false, relay, null)
                    delay(50)
                    client.listener!!.onEvent(event(2), false, relay, null)
                }

            val start = TimeSource.Monotonic.markNow()
            val got = mutableListOf<Event>()
            // limit = 3 keeps the filter unfulfilled, so only the stall can end the
            // page; the events already delivered are kept and, since nothing older
            // followed, the next page comes back empty and the walk terminates.
            val total =
                client.fetchAllPages(
                    relay = relay,
                    filters = listOf(Filter(kinds = listOf(1), limit = 3)),
                    idleTimeoutMs = 300,
                ) { got.add(it) }
            feeder.join()
            val elapsedMs = start.elapsedNow().inWholeMilliseconds

            assertEquals(2, total.downloaded, "events delivered before the stall are kept")
            assertTrue(elapsedMs >= 300, "must wait out at least one idle window, took ${elapsedMs}ms")
            assertTrue(elapsedMs < 5_000, "a stalled page must end promptly after the idle window, took ${elapsedMs}ms")
        }

    /**
     * Time the CONSUMER spends in `onEvent` is not the relay going silent. A
     * caller whose handler suspends on its own backpressure (a full ingest
     * queue) for longer than the idle window used to have the page ended as
     * idle under it — the walk then re-asked from its cursor and the relay
     * served the same events again, which for a mirror under a burst meant
     * re-downloading the burst it was already too busy to take. The window
     * now measures the relay alone: it does not run while the handler does.
     */
    @Test
    fun aSlowConsumerDoesNotEndThePageAsIdle() =
        runBlocking {
            val client = ScriptedClient()
            val feeder =
                launch {
                    // The relay is prompt: three events and an EOSE inside 100ms.
                    // Only the consumer is slow.
                    delay(50)
                    repeat(3) { i -> client.listener!!.onEvent(event(3 - i, createdAt = 100L - i), false, relay, null) }
                    client.listener!!.onEose(relay, null)
                }

            val got = mutableListOf<Event>()
            val total =
                client.fetchAllPages(
                    relay = relay,
                    filters = listOf(Filter(kinds = listOf(1), limit = 3)),
                    idleTimeoutMs = 300,
                ) {
                    // Blocked on the first event for well over the idle window.
                    if (got.isEmpty()) delay(900)
                    got.add(it)
                }
            feeder.join()

            assertEquals(3, total.downloaded, "every event the relay sent is delivered")
            assertEquals(3, got.size)
            assertEquals(1, client.subscribeCount, "one page: a consumer's own wait must not end it as idle and re-ask the relay")
        }

    /**
     * The other half of the hold: once a slow handler RETURNS, the window runs
     * again from that moment. A relay that sends one event, makes the handler
     * wait past the window, then goes silent without EOSE must still have the
     * page end about one window after the handler returned — a hold left set
     * would hang this walk forever instead.
     */
    @Test
    fun theIdleWindowRunsAgainAfterASlowHandlerReturns() =
        runBlocking {
            val client = ScriptedClient()
            val feeder =
                launch {
                    delay(50)
                    client.listener!!.onEvent(event(1), false, relay, null)
                }

            val start = TimeSource.Monotonic.markNow()
            val total =
                withTimeoutOrNull(10_000) {
                    client.fetchAllPages(
                        relay = relay,
                        filters = listOf(Filter(kinds = listOf(1), limit = 3)),
                        idleTimeoutMs = 300,
                    ) { delay(700) }
                }
            feeder.join()
            val elapsedMs = start.elapsedNow().inWholeMilliseconds

            assertTrue(total != null, "the page must still end on silence once the handler has returned")
            assertEquals(1, total.downloaded)
            assertTrue(elapsedMs >= 50 + 700 + 300, "the window starts after the handler, took ${elapsedMs}ms")
            assertTrue(elapsedMs < 5_000, "and ends promptly after it, took ${elapsedMs}ms")
        }

    /**
     * Documents why [fetchAllPages] has no wall-clock ceiling — nor should any
     * accessory: a hard bound composes at the call site as `withTimeoutOrNull`.
     *
     * A per-page ceiling cannot bound this walk: when a page ends, the loop advances
     * the cursor and fires the NEXT `REQ`, so an endless trickle against an unbounded
     * filter is merely re-paged. This pins that reality — the walk runs until the
     * caller cancels — so nobody re-adds a `maxPageMs` believing it caps anything.
     * What actually bounds a download is the filter's `limit`.
     */
    @Test
    fun anEndlessTrickleIsBoundedByCancellationNotByAWallClock() =
        runBlocking {
            val client = ScriptedClient()
            var ts = 10_000_000L
            var i = 1
            val feeder =
                launch {
                    // Trickles forever, strictly decreasing created_at, never EOSE.
                    while (true) {
                        delay(40)
                        client.listener?.onEvent(event(i++, ts--), false, relay, null)
                    }
                }

            val returned =
                withTimeoutOrNull(1_500) {
                    client.fetchAllPages(
                        relay = relay,
                        filters = listOf(Filter(kinds = listOf(1))), // unbounded: no limit
                        idleTimeoutMs = 200,
                    ) { }
                }
            feeder.cancel()

            assertNull(returned, "an unbounded filter against an endless trickle ends only by cancellation")
        }
}
