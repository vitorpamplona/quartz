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

import com.vitorpamplona.quartz.utils.cache.LargeCache
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock

/**
 * Events received per subscription and kind, for debugging which REQs bring in what.
 *
 * [add] runs for every received event, so it allocates nothing once a subscription is known: the
 * counts are keyed by the frame's own subscription id, and each subscription keeps its kinds and
 * counts in two small arrays. At most [maxSubscriptions] are kept, the oldest dropped first:
 * fetch helpers open a fresh id per call, which grew the map for the life of the process.
 */
class ReqStatsRepository(
    private val maxSubscriptions: Int = DEFAULT_MAX_SUBSCRIPTIONS,
) {
    data class Counter(
        val subscriptionId: String,
        val eventKind: Int,
    ) {
        var counter: Int = 0
    }

    /** One subscription's counts: [kinds] and [counts] side by side; a REQ brings few kinds. */
    private class SubCounts(
        val subscriptionId: String,
    ) {
        private val lock = PlatformLock()
        private var kinds = IntArray(4)
        private var counts = IntArray(4)
        private var size = 0

        fun add(kind: Int) =
            lock.withLock {
                for (i in 0 until size) {
                    if (kinds[i] == kind) {
                        counts[i]++
                        return@withLock
                    }
                }
                if (size == kinds.size) {
                    kinds = kinds.copyOf(size * 2)
                    counts = counts.copyOf(size * 2)
                }
                kinds[size] = kind
                counts[size] = 1
                size++
            }

        /** A consistent copy, so [action] runs outside the lock; only the print path calls it. */
        fun forEach(action: (subId: String, kind: Int, counter: Int) -> Unit) {
            val (kindsNow, countsNow) = lock.withLock { kinds.copyOf(size) to counts.copyOf(size) }
            for (i in kindsNow.indices) action(subscriptionId, kindsNow[i], countsNow[i])
        }
    }

    private val bySubscription = LargeCache<String, SubCounts>()

    /** Insertion order of [bySubscription]'s keys, for dropping the oldest; guarded by [trackLock]. */
    private val order = ArrayDeque<String>()
    private val trackLock = PlatformLock()

    fun add(
        subscriptionId: String,
        eventKind: Int,
    ) {
        val counts = bySubscription.get(subscriptionId) ?: track(subscriptionId)
        counts.add(eventKind)
    }

    /** The counts of a subscription seen for the first time; drops the oldest past the cap. */
    private fun track(subscriptionId: String): SubCounts =
        trackLock.withLock {
            bySubscription.get(subscriptionId)?.let { return it }
            val created = SubCounts(subscriptionId)
            bySubscription.put(subscriptionId, created)
            order.addLast(subscriptionId)
            while (order.size > maxSubscriptions) bySubscription.remove(order.removeFirst())
            created
        }

    fun printCounter(action: (subId: String, kind: Int, counter: Int) -> Unit) {
        bySubscription.forEach { _, counts -> counts.forEach(action) }
    }

    companion object {
        const val DEFAULT_MAX_SUBSCRIPTIONS = 1_000
    }
}
