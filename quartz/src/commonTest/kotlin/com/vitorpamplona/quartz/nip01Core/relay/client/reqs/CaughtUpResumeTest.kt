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

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaughtUpResumeTest {
    private var clock = 1_000L
    private val resume = CaughtUpResume(overlapSeconds = 60, nowInSeconds = { clock })
    private val relayA = RelayUrlNormalizer.normalize("wss://a.example")
    private val relayB = RelayUrlNormalizer.normalize("wss://b.example")
    private val notes = listOf(Filter(kinds = listOf(1), since = 500))

    @Test
    fun nothingToResumeFromBeforeTheFirstCatchUp() {
        assertNull(resume.resumeFrom(relayA, notes), "the filters stay as given")
        assertNull(resume.resume(relayA, notes))
    }

    @Test
    fun resumesFromTheLastCatchUpMinusTheOverlap() {
        resume.onCaughtUp(relayA, notes)
        assertEquals(940L, resume.resumeFrom(relayA, notes))

        clock = 5_000 // time passes with nothing caught up: still 940
        assertEquals(940L, resume.resumeFrom(relayA, notes))

        resume.onCaughtUp(relayA, null) // a live event: still the same filters
        assertEquals(4_940L, resume.resumeFrom(relayA, notes))
    }

    @Test
    fun eachRelayHasItsOwnPoint() {
        resume.onCaughtUp(relayA, notes)
        clock = 2_000
        resume.onCaughtUp(relayB, notes)
        assertEquals(940L, resume.resumeFrom(relayA, notes))
        assertEquals(1_940L, resume.resumeFrom(relayB, notes))
    }

    @Test
    fun aMovedSinceStillResumes() {
        resume.onCaughtUp(relayA, notes)
        assertEquals(940L, resume.resumeFrom(relayA, listOf(Filter(kinds = listOf(1), since = 900))))
    }

    @Test
    fun changedFiltersAreSentAsGiven() {
        resume.onCaughtUp(relayA, notes)
        // An author added: its history before the caught-up point was never asked for.
        assertNull(resume.resumeFrom(relayA, listOf(Filter(kinds = listOf(1), authors = listOf("a".repeat(64)), since = 500))))
        assertNull(resume.resumeFrom(relayA, notes + Filter(kinds = listOf(7))))
    }

    @Test
    fun unknownFiltersAreSentAsGiven() {
        resume.onCaughtUp(relayA, null)
        assertNull(resume.resumeFrom(relayA, notes), "caught up with what, the client couldn't say")
    }

    @Test
    fun resumeMovesEachFilterButNeverEarlierThanItsOwnSince() {
        val later = Filter(kinds = listOf(7), since = 2_000)
        val none = Filter(kinds = listOf(3))
        val filters = notes + later + none
        resume.onCaughtUp(relayA, filters)
        assertEquals(listOf(940L, 2_000L, 940L), resume.resume(relayA, filters)?.map { it.since })
    }
}
