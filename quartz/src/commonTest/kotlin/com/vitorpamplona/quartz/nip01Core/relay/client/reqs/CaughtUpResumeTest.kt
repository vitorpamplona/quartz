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
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CaughtUpResumeTest {
    private var clock = 10_000L
    private val resume = CaughtUpResume(nowInSeconds = { clock })
    private val relayA = RelayUrlNormalizer.normalize("wss://a.example")
    private val relayB = RelayUrlNormalizer.normalize("wss://b.example")
    private val notes = listOf(Filter(kinds = listOf(1), since = 500))

    private fun note(createdAt: Long) = Event("a".repeat(64), "b".repeat(64), createdAt, 1, emptyArray(), "", "c".repeat(128))

    /** A REQ that delivered stored events at [createdAt] and reached EOSE. */
    private fun served(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
        vararg createdAt: Long,
    ) {
        createdAt.forEach { resume.onEvent(relay, note(it), isLive = false) }
        resume.onEose(relay, filters)
    }

    @Test
    fun nothingToResumeFromBeforeTheFirstCatchUp() {
        assertNull(resume.resumeFrom(relayA, notes), "the filters stay as given")
        assertNull(resume.resume(relayA, notes))
    }

    @Test
    fun anEoseWithNothingDeliveredResumesFromTheReqsSince() {
        served(relayA, notes)
        assertEquals(500L, resume.caughtUpAt(relayA), "a restart that saves this misses nothing")
        assertEquals(500L, resume.resumeFrom(relayA, notes))
        assertNull(resume.resume(relayA, notes), "no filter moves: they go as given")
    }

    @Test
    fun anEoseWithNothingDeliveredForFiltersWithoutSinceHasNothingToResumeFrom() {
        val all = listOf(Filter(kinds = listOf(1)))
        served(relayA, all)
        assertNull(resume.caughtUpAt(relayA), "a filter without since asks for all history")
        assertNull(resume.resumeFrom(relayA, all))
        served(relayB, notes + Filter(kinds = listOf(7)))
        assertNull(resume.caughtUpAt(relayB), "one filter without since is enough")
    }

    @Test
    fun anEoseWithNothingDeliveredTakesTheEarliestSince() {
        val two = listOf(Filter(kinds = listOf(1), since = 700), Filter(kinds = listOf(7), since = 600))
        served(relayA, two)
        assertEquals(600L, resume.caughtUpAt(relayA))
    }

    @Test
    fun anEoseWithNothingDeliveredIsCappedAtTheDeviceClock() {
        val ahead = listOf(Filter(kinds = listOf(1), since = clock + 3_600))
        served(relayA, ahead)
        assertEquals(clock, resume.caughtUpAt(relayA))
    }

    @Test
    fun aLaterEmptyEoseNeverMovesThePointBack() {
        served(relayA, notes, 900)
        served(relayA, notes)
        assertEquals(900L, resume.caughtUpAt(relayA))
    }

    @Test
    fun resumesFromTheNewestEventDelivered() {
        served(relayA, notes, 1_000, 3_000, 2_000)
        assertEquals(3_000L, resume.resumeFrom(relayA, notes))

        clock = 50_000 // time passes with nothing delivered: still 3_000
        assertEquals(3_000L, resume.resumeFrom(relayA, notes))

        resume.onEvent(relayA, note(40_000), isLive = true)
        assertEquals(40_000L, resume.resumeFrom(relayA, notes))
        resume.onEvent(relayA, note(20_000), isLive = true) // older, out of order: doesn't move it back
        assertEquals(40_000L, resume.resumeFrom(relayA, notes))
    }

    @Test
    fun notTheTimeItWasProcessed() {
        // Delivery lags: the event was created at 1_000 but handed over at 10_000. Anything the
        // relay sent after it may still have been queued when the connection went.
        served(relayA, notes, 1_000)
        assertEquals(1_000L, resume.resumeFrom(relayA, notes))
    }

    @Test
    fun aFutureEventIsCappedAtTheDeviceClock() {
        served(relayA, notes, 1_000)
        resume.onEvent(relayA, note(99_999), isLive = true)
        assertEquals(10_000L, resume.resumeFrom(relayA, notes))
    }

    @Test
    fun storedEventsCountOnlyAtEose() {
        served(relayA, notes, 1_000)
        // A resumed REQ: the relay sends its stored matches newest first, and the connection drops
        // before the older ones and the EOSE.
        resume.onEvent(relayA, note(5_000), isLive = false)
        assertEquals(1_000L, resume.resumeFrom(relayA, notes), "the older stored ones were never delivered")
        resume.onEose(relayA, notes)
        assertEquals(5_000L, resume.resumeFrom(relayA, notes))
    }

    @Test
    fun eachRelayHasItsOwnPoint() {
        served(relayA, notes, 1_000)
        served(relayB, notes, 2_000)
        assertEquals(1_000L, resume.resumeFrom(relayA, notes))
        assertEquals(2_000L, resume.resumeFrom(relayB, notes))
        assertEquals(1_000L, resume.caughtUpAt(relayA))
    }

    @Test
    fun aMovedSinceStillResumes() {
        served(relayA, notes, 1_000)
        assertEquals(1_000L, resume.resumeFrom(relayA, listOf(Filter(kinds = listOf(1), since = 900))))
    }

    @Test
    fun changedFiltersAreSentAsGiven() {
        served(relayA, notes, 1_000)
        // An author added: its history before the point was never asked for.
        assertNull(resume.resumeFrom(relayA, listOf(Filter(kinds = listOf(1), authors = listOf("a".repeat(64)), since = 500))))
        assertNull(resume.resumeFrom(relayA, notes + Filter(kinds = listOf(7))))
    }

    @Test
    fun unknownFiltersAreSentAsGiven() {
        resume.onEvent(relayA, note(1_000), isLive = true)
        resume.onEose(relayA, null)
        assertNull(resume.resumeFrom(relayA, notes), "caught up with what, the client couldn't say")
    }

    @Test
    fun resumeMovesEachFilterButNeverEarlierThanItsOwnSince() {
        val later = Filter(kinds = listOf(7), since = 2_000)
        val none = Filter(kinds = listOf(3))
        val filters = notes + later + none
        served(relayA, filters, 1_000)
        assertEquals(listOf(1_000L, 2_000L, 1_000L), resume.resume(relayA, filters)?.map { it.since })
    }

    @Test
    fun aFilterThatEndsBeforeThePointIsSentAsGiven() {
        val window = Filter(kinds = listOf(1), since = 100, until = 800)
        val open = Filter(kinds = listOf(1), until = 5_000)
        val filters = listOf(window, open)
        served(relayA, filters, 1_000)
        val resumed = resume.resume(relayA, filters)!!
        assertSame(window, resumed[0], "since 1_000 past until 800 would ask for nothing")
        assertEquals(1_000L, resumed[1].since)
    }

    @Test
    fun giftWrapsResumeTwoDaysEarlier() {
        val wraps = Filter(kinds = listOf(GiftWrapEvent.KIND), tags = mapOf("p" to listOf("a".repeat(64))))
        val filters = notes + wraps
        clock = 1_000_000
        served(relayA, filters, 1_000_000)
        // NIP-59 backdates a wrap's created_at by up to two days; everything else resumes at the point itself.
        assertEquals(listOf(1_000_000L, 1_000_000L - TimeUtils.twoDays()), resume.resume(relayA, filters)?.map { it.since })
    }

    @Test
    fun aGiftWrapFilterStillNeverResumesEarlierThanItsOwnSince() {
        val wraps = listOf(Filter(kinds = listOf(GiftWrapEvent.KIND), since = 900_000))
        clock = 1_000_000
        served(relayA, wraps, 1_000_000)
        assertEquals(listOf(900_000L), resume.resume(relayA, wraps)?.map { it.since })
    }
}
