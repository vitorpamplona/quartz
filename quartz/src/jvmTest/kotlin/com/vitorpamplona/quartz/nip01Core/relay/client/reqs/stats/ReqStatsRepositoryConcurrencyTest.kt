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

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A REQ goes to every relay under one id, so its events are counted from many threads at once. */
class ReqStatsRepositoryConcurrencyTest {
    @Test
    fun countsFromManyRelaysAtOnceAreExact() {
        val stats = ReqStatsRepository()
        val threads = 8
        val perThread = 20_000
        val start = CountDownLatch(1)
        val workers =
            (0 until threads).map { t ->
                thread {
                    start.await()
                    for (i in 0 until perThread) {
                        // each thread adds the kinds in a different order, so new kinds race too
                        stats.add("feed", (i + t) % 7)
                        if (i % 10 == 0) stats.add("sub-$t-${i % 50}", 1)
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join() }

        var feedTotal = 0
        val feedKinds = HashSet<Int>()
        stats.printCounter { subId, kind, counter ->
            if (subId == "feed") {
                feedTotal += counter
                feedKinds.add(kind)
            }
        }
        assertEquals(threads * perThread, feedTotal)
        assertEquals((0 until 7).toSet(), feedKinds)
    }

    @Test
    fun evictionUnderManyThreadsStaysBoundedAndConsistent() {
        val stats = ReqStatsRepository(maxSubscriptions = 100)
        val start = CountDownLatch(1)
        val workers =
            (0 until 8).map { t ->
                thread {
                    start.await()
                    for (i in 0 until 20_000) stats.add("t$t-$i", i % 3)
                }
            }
        start.countDown()
        workers.forEach { it.join() }

        val subs = HashSet<String>()
        var total = 0
        stats.printCounter { subId, _, counter ->
            subs.add(subId)
            total += counter
        }
        assertTrue(subs.size in 100..110, "kept ${subs.size}")
        // each kept subscription saw exactly one event
        assertEquals(subs.size, total)
    }
}
