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
package com.vitorpamplona.quartz.nip01Core.relay.client.reqs.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class ReqStatsRepositoryTest {
    private fun ReqStatsRepository.snapshot(): Map<Pair<String, Int>, Int> {
        val out = HashMap<Pair<String, Int>, Int>()
        printCounter { subId, kind, counter -> out[subId to kind] = counter }
        return out
    }

    @Test
    fun countsEachSubscriptionAndKindApart() {
        val stats = ReqStatsRepository()
        repeat(3) { stats.add("feed", 1) }
        stats.add("feed", 6)
        repeat(2) { stats.add("notifications", 1) }
        // more kinds than the first array holds
        for (kind in 10..20) stats.add("feed", kind)

        val counts = stats.snapshot()
        assertEquals(3, counts["feed" to 1])
        assertEquals(1, counts["feed" to 6])
        assertEquals(2, counts["notifications" to 1])
        assertEquals(1, counts["feed" to 20])
        assertEquals(2 + 11 + 1, counts.size)
    }

    @Test
    fun keepsOnlyTheNewestSubscriptionsPastTheCap() {
        val stats = ReqStatsRepository(maxSubscriptions = 3)
        for (i in 1..5) stats.add("fetch-$i", 1)
        // the oldest two were dropped; a known one keeps counting
        stats.add("fetch-5", 1)

        val counts = stats.snapshot()
        assertEquals(setOf("fetch-3", "fetch-4", "fetch-5"), counts.keys.map { it.first }.toSet())
        assertEquals(2, counts["fetch-5" to 1])
    }
}
