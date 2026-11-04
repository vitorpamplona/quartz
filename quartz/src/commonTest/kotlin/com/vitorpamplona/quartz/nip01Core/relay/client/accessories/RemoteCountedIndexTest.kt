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
    fun aSideThatNeverAnswersIsNotAskedAgainAfterTwo() =
        runTest {
            var asked = 0
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100) {
                    asked++
                    null
                }
            repeat(10) { index.count(window) }
            assertEquals(2, asked, "a relay without NIP-45 costs two timeouts, not one per window")
        }

    @Test
    fun anAnswerInBetweenResetsTheRun() =
        runTest {
            val answers = ArrayDeque(listOf(null, 5, null, 5, null, null, 5))
            var asked = 0
            val index =
                NegentropyLocalIndex.Empty.withRemoteCount(100) {
                    asked++
                    answers.removeFirst()
                }
            repeat(7) { index.count(window) }
            assertEquals(6, asked, "only two unanswered in a row stop it")
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
