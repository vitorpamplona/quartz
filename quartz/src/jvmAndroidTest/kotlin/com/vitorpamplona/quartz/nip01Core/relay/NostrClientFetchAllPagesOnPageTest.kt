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
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PageReport
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PageRetryBackoff
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PagedFetchResult
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllPages
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `onPage` hears every REQ of a walk once it ends: what was asked, what crossed the socket, what
 * was handed on, how long it took and how it ended, so a caller can profile or diagnose a relay
 * without writing its own page loop or listening on the socket beside the walk.
 */
class NostrClientFetchAllPagesOnPageTest {
    private val relay = RelayUrlNormalizer.normalize("wss://pages.example.com")

    @Test
    fun everyPageIsReportedWithWhatItAskedAndDelivered() =
        runBlocking {
            val client = FakePagingRelay(this, FakePagingRelay.corpus(1_200), maxLimit = 500)
            val pages = mutableListOf<PageReport>()
            var events = 0

            val result =
                client.fetchAllPages(relay, listOf(Filter(kinds = listOf(1))), idleTimeoutMs = 2_000, throttleBackoff = PageRetryBackoff.NONE, onPage = { pages.add(it) }) {
                    events++
                }

            assertEquals(PagedFetchResult.End.DRAINED, result.end)
            assertEquals(client.requests.size, pages.size, "one report per REQ")
            assertEquals(client.requests.map { it }, pages.map { it.asked }, "the filters that went on the wire")
            assertEquals(events, pages.sumOf { it.delivered }, "delivered adds up to what onEvent saw")
            assertEquals(result.downloaded, pages.sumOf { it.delivered })
            assertNull(
                pages
                    .first()
                    .asked
                    .single()
                    .until,
                "the first page is open-ended",
            )
            assertEquals(500, pages.first().received)
            assertEquals(500, pages.first().delivered)
            // Later pages re-fetch their boundary second: received, not delivered.
            assertEquals(1, pages[1].duplicates)
            assertEquals(pages[1].received, pages[1].delivered + pages[1].duplicates + pages[1].unmatched)
            assertTrue(pages.dropLast(1).all { it.ending == PageReport.Ending.EOSE })
            assertEquals(0, pages.last().delivered, "the empty EOSEd page that drained the walk")
            assertTrue(pages.all { it.elapsedMs >= 0 })
            assertTrue(pages.all { it.relay == relay && it.message == null })
        }

    @Test
    fun aRefusedPageCarriesTheRelaysWordsEvenWhenTheWalkRecovers() =
        runBlocking {
            val client = FakePagingRelay(this, FakePagingRelay.corpus(800), maxLimit = 500, refuseAboveMax = true)
            val pages = mutableListOf<PageReport>()

            val result = client.fetchAllPages(relay, listOf(Filter(kinds = listOf(1), limit = 700)), idleTimeoutMs = 2_000, onPage = { pages.add(it) }) { }

            assertEquals(PagedFetchResult.End.LIMIT_REACHED, result.end)
            assertNull(result.message, "the walk recovered, so its result carries no refusal")
            val refused = pages.first()
            assertEquals(PageReport.Ending.CLOSED, refused.ending)
            assertEquals("blocked: limit too high: 700 (max 500)", refused.message)
            assertEquals(700, refused.asked.single().limit)
            assertEquals(500, pages[1].asked.single().limit, "the re-ask at the learned cap is its own page")
        }

    @Test
    fun eventsTheRelaySendsOutsideTheAskAreCountedNotDelivered() =
        runBlocking {
            // A relay that ignores the kinds of the filter: kind-7 events cross the socket, and
            // the walk drops them. Before, only a listener on the socket could see that happen.
            val corpus =
                FakePagingRelay.corpus(10) +
                    (0 until 5).map { i -> Event(("70$i").padStart(64, '0'), "f".repeat(64), 999_995L - i, 7, emptyArray(), "r$i", "0".repeat(128)) }
            val client =
                FakePagingRelay(this, corpus, answerFor = { req, _, honest -> if (req == 1) honest + corpus.filter { it.kind == 7 } else honest })
            val pages = mutableListOf<PageReport>()

            client.fetchAllPages(relay, listOf(Filter(kinds = listOf(1))), idleTimeoutMs = 2_000, throttleBackoff = PageRetryBackoff.NONE, onPage = { pages.add(it) }) { }

            assertEquals(15, pages.first().received)
            assertEquals(10, pages.first().delivered)
            assertEquals(5, pages.first().unmatched)
        }

    @Test
    fun aClosedWalkStillReportsItsMessageOnTheResult() =
        runBlocking {
            // PagedFetchResult.message already carries the CLOSED text of a walk that ends on it.
            val client = FakePagingRelay(this, FakePagingRelay.corpus(10), closeWith = { "blocked: not allowed here" })
            val pages = mutableListOf<PageReport>()
            val result = client.fetchAllPages(relay, listOf(Filter(kinds = listOf(1))), idleTimeoutMs = 2_000, onPage = { pages.add(it) }) { }
            assertEquals(PagedFetchResult.End.CLOSED, result.end)
            assertEquals("blocked: not allowed here", result.message)
            assertEquals("blocked: not allowed here", pages.single().message)
        }
}
