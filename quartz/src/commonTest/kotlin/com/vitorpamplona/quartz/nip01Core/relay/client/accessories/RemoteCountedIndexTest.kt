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

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteCountedIndexTest {
    private val window = Filter(kinds = listOf(1))
    private val ten = NegentropyLocalIndex.of(List(10) { IdAndTime(1000L + it, "id$it") })

    @Test
    fun theLargerSideDecides() =
        runTest {
            assertEquals(15_000, NegentropyLocalIndex.Empty.withRemoteCount(100) { 15_000 }.count(window), "a first sync: the other side's")
            assertEquals(10, ten.withRemoteCount(100) { 3 }.count(window), "ours when it is larger")
        }

    @Test
    fun noAnswerLeavesOurCount() =
        runTest {
            assertEquals(10, ten.withRemoteCount(100) { null }.count(window))
            val cantCount =
                object : NegentropyLocalIndex {
                    override suspend fun count(window: Filter): Int? = null

                    override suspend fun entriesFor(window: Filter) = emptyList<IdAndTime>()
                }
            assertEquals(7, cantCount.withRemoteCount(100) { 7 }.count(window), "a store that can't count: theirs")
            assertNull(cantCount.withRemoteCount(100) { null }.count(window), "neither: no pre-split, as without the decorator")
        }

    @Test
    fun anUnansweredCountIsAskedAgainOnce() =
        runTest {
            val answers = ArrayDeque(listOf(null, 15_000))
            var asked = 0
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100, nowMs = { testScheduler.currentTime }) {
                    asked++
                    answers.removeFirst()
                }
            assertEquals(15_000, index.count(window), "a connection coming back answers the retry")
            assertEquals(2, asked)
        }

    @Test
    fun aSideThatNeverAnswersIsPausedNotAskedPerWindow() =
        runTest {
            var asked = 0
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100, nowMs = { testScheduler.currentTime }) {
                    asked++
                    null
                }
            repeat(10) { index.count(window) }
            assertEquals(4, asked, "two windows, each asked twice, then a pause: not two asks per window")
            advanceTimeBy(60_001)
            index.count(window)
            assertEquals(6, asked, "asked again once the pause has passed")
        }

    @Test
    fun aHiccupPausesAskingAndAnAnswerAfterItCountsAgain() =
        runTest {
            var reachable = false
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100, nowMs = { testScheduler.currentTime }) {
                    if (reachable) 15_000 else null
                }
            repeat(2) { assertEquals(0, index.count(window)) }
            reachable = true
            assertEquals(0, index.count(window), "paused: our side alone")
            advanceTimeBy(60_001)
            assertEquals(15_000, index.count(window), "the relay counts again: not turned off for good")
        }

    @Test
    fun anAnswerInBetweenResetsTheRun() =
        runTest {
            // Per window: an ask, and on no answer one retry.
            val answers = ArrayDeque(listOf(null, null, 5, null, null, 5, null, null, null, null))
            var asked = 0
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100, nowMs = { testScheduler.currentTime }) {
                    asked++
                    answers.removeFirst()
                }
            // Windows 1, 3 and 5 go unanswered (two asks each), 2 and 4 answer: no two in a row.
            repeat(5) { index.count(window) }
            assertEquals(8, asked)
            index.count(window) // window 6: the second unanswered in a row
            assertEquals(10, asked, "only two unanswered windows in a row pause it")
            index.count(window)
            assertEquals(10, asked, "and then it is paused")
        }

    @Test
    fun atMostTheBudgetIsAsked() =
        runTest {
            var asked = 0
            val index =
                ten.withRemoteCount(maxRemoteCounts = 5) {
                    asked++
                    Int.MAX_VALUE
                }
            val counts = List(20) { index.count(window) }
            assertEquals(5, asked, "a relay answering a huge number to every COUNT is asked a bounded number of times")
            assertEquals(List(5) { Int.MAX_VALUE } + List(15) { 10 }, counts, "then our count decides alone")
        }

    @Test
    fun entriesAreOurs() =
        runTest {
            assertEquals(ten.entriesFor(window), ten.withRemoteCount(100) { 1_000_000 }.entriesFor(window))
        }
}
