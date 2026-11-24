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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun theLeastRecentlyActiveSubscriptionsGoFirst() {
        // cap 3: a fourth is allowed as slack, the fifth brings it back to 3
        val stats = ReqStatsRepository(maxSubscriptions = 3)
        stats.add("a", 1)
        stats.add("b", 1)
        stats.add("c", 1)
        stats.add("d", 1)
        stats.add("a", 1) // "a" is now as recent as "d"
        stats.add("e", 1)

        val counts = stats.snapshot()
        assertEquals(setOf("a", "d", "e"), counts.keys.map { it.first }.toSet())
        assertEquals(2, counts["a" to 1])
    }

    @Test
    fun manyNewSubscriptionsKeepTheMostRecentNearTheCap() {
        val stats = ReqStatsRepository()
        for (i in 0 until 5_000) stats.add("sub-$i", 1)

        val kept =
            stats
                .snapshot()
                .keys
                .map { it.first }
                .toSet()
        val cap = ReqStatsRepository.DEFAULT_MAX_SUBSCRIPTIONS
        assertTrue(kept.size in cap..cap + cap / 10, "kept ${kept.size}")
        assertTrue("sub-4999" in kept)
        assertTrue("sub-0" !in kept)
        // the newest cap ids are all there
        assertTrue((5_000 - cap until 5_000).all { "sub-$it" in kept })
    }

    @Test
    fun manySubscriptionsActiveInTheSameStretchStillStayWithinTheCap() {
        // all active since the last new one: one shared stamp, so ties at the cutoff must go too
        val stats = ReqStatsRepository(maxSubscriptions = 10)
        for (i in 0 until 11) stats.add("sub-$i", 1)
        for (round in 0 until 3) for (i in 0 until 11) stats.add("sub-$i", 1)
        stats.add("new", 1)
        val kept =
            stats
                .snapshot()
                .keys
                .map { it.first }
                .toSet()
        // evicted down to the cap, and the newcomer is never its own eviction's victim
        assertEquals(10, kept.size)
        assertTrue("new" in kept)
    }

    @Test
    fun aNonPositiveCapIsRefused() {
        assertFailsWith<IllegalArgumentException> { ReqStatsRepository(maxSubscriptions = 0) }
        // the no-argument constructor native and Swift callers use is still there
        assertEquals(0, ReqStatsRepository().snapshot().size)
    }
}
