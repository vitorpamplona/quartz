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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Events received per subscription and kind, for debugging which REQs bring in what.
 *
 * [add] runs for every received event, from every relay's thread at once (a REQ goes to every
 * relay under one id), so once a subscription and kind are known it neither allocates nor locks:
 * the counts are keyed by the frame's own subscription id, and each kind's count is an atomic.
 * A lock is taken only to add a subscription or a kind.
 *
 * At most [maxSubscriptions] are kept: fetch helpers open a fresh id per call, which grew the
 * map for the life of the process. Past the cap the subscription with the fewest events goes,
 * so the long-lived feeds stay while one-off fetches churn.
 */
@OptIn(ExperimentalAtomicApi::class)
class ReqStatsRepository(
    private val maxSubscriptions: Int,
) {
    constructor() : this(DEFAULT_MAX_SUBSCRIPTIONS)

    init {
        require(maxSubscriptions > 0) { "maxSubscriptions must be positive, was $maxSubscriptions" }
    }

    data class Counter(
        val subscriptionId: String,
        val eventKind: Int,
    ) {
        var counter: Int = 0
    }

    private class KindCount(
        val kind: Int,
    ) {
        val count = AtomicInt(0)
    }

    /** One subscription's counts. [kinds] is replaced, never changed, so [add] reads it unlocked. */
    private class SubCounts(
        val subscriptionId: String,
    ) {
        @Volatile
        private var kinds: Array<KindCount> = NO_KINDS
        private val lock = PlatformLock()

        fun add(kind: Int) {
            val now = kinds
            for (i in now.indices) {
                if (now[i].kind == kind) {
                    now[i].count.incrementAndFetch()
                    return
                }
            }
            addKind(kind)
        }

        private fun addKind(kind: Int) =
            lock.withLock {
                val now = kinds
                for (i in now.indices) {
                    if (now[i].kind == kind) {
                        now[i].count.incrementAndFetch()
                        return@withLock
                    }
                }
                val created = KindCount(kind)
                created.count.incrementAndFetch()
                kinds = now + created
            }

        fun total(): Long {
            val now = kinds
            var total = 0L
            for (i in now.indices) total += now[i].count.load()
            return total
        }

        fun forEach(action: (subId: String, kind: Int, counter: Int) -> Unit) {
            val now = kinds
            for (i in now.indices) action(subscriptionId, now[i].kind, now[i].count.load())
        }
    }

    private val bySubscription = LargeCache<String, SubCounts>()
    private val trackLock = PlatformLock()

    /**
     * Counts one event. An increment that lands on a subscription evicted at that moment is lost,
     * which a debugging count can afford.
     */
    fun add(
        subscriptionId: String,
        eventKind: Int,
    ) {
        val counts = bySubscription.get(subscriptionId) ?: track(subscriptionId)
        counts.add(eventKind)
    }

    /** The counts of a subscription seen for the first time, making room past the cap. */
    private fun track(subscriptionId: String): SubCounts =
        trackLock.withLock {
            bySubscription.get(subscriptionId)?.let { return it }
            if (bySubscription.size() >= maxSubscriptions) evictFewest()
            val created = SubCounts(subscriptionId)
            bySubscription.put(subscriptionId, created)
            created
        }

    /** Drops the subscription with the fewest events. Runs only past the cap, under [trackLock]. */
    private fun evictFewest() {
        var victim: String? = null
        var fewest = Long.MAX_VALUE
        bySubscription.forEach { subscriptionId, counts ->
            val total = counts.total()
            if (total < fewest) {
                fewest = total
                victim = subscriptionId
            }
        }
        victim?.let { bySubscription.remove(it) }
    }

    fun printCounter(action: (subId: String, kind: Int, counter: Int) -> Unit) {
        bySubscription.forEach { _, counts -> counts.forEach(action) }
    }

    companion object {
        const val DEFAULT_MAX_SUBSCRIPTIONS = 1_000

        private val NO_KINDS = emptyArray<KindCount>()
    }
}
