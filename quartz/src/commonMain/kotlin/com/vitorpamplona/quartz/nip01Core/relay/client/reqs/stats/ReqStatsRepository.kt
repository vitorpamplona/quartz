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
 * Events received per subscription and kind, for debugging which REQs bring in what, kept for
 * the [maxSubscriptions] subscriptions that received an event most recently.
 *
 * [add] runs for every received event, from every relay's thread at once (a REQ goes to every
 * relay under one id), so once a subscription and kind are known it neither allocates nor locks:
 * the counts are keyed by the frame's own subscription id, and each kind's count is an atomic.
 *
 * Recency is an epoch that advances only when a new subscription is first seen: each event
 * copies it into its subscription's [SubCounts.lastSeen], writing only when it changed, so the
 * frames of one busy stretch share no counter and mostly write nothing. Once a few more than
 * [maxSubscriptions] are tracked, one pass drops the least recently active back down to the cap,
 * so the sort runs once per many new subscriptions, never per event.
 *
 * Racy on purpose where a debugging count can afford it: an event that lands on a subscription
 * at the moment it is evicted is lost, and that subscription starts again from its next event.
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
        lastSeen: Long,
    ) {
        @Volatile
        var kinds: Array<KindCount> = NO_KINDS

        /** The [epoch] of this subscription's latest event: higher is more recent. */
        @Volatile
        var lastSeen: Long = lastSeen
    }

    private val bySubscription = LargeCache<String, SubCounts>()

    /** Guards adding subscriptions and kinds, [tracked] and eviction; never taken per event. */
    private val trackLock = PlatformLock()

    /** Subscriptions in [bySubscription], counted here: a skip list's size() walks every node. */
    private var tracked = 0

    /** Advanced once per new subscription; see the class documentation. */
    @Volatile
    private var epoch = 0L

    private val slack = maxOf(1, maxSubscriptions / 10)

    // Eviction's buffers, reused under [trackLock]: when more subscriptions are active than the
    // cap, eviction runs every [slack] new ones.
    private var stamps = LongArray(0)
    private val victims = ArrayList<String>()

    fun add(
        subscriptionId: String,
        eventKind: Int,
    ) {
        val counts = bySubscription.get(subscriptionId) ?: track(subscriptionId)
        val now = epoch
        if (counts.lastSeen != now) counts.lastSeen = now
        val kinds = counts.kinds
        for (i in kinds.indices) {
            if (kinds[i].kind == eventKind) {
                kinds[i].count.incrementAndFetch()
                return
            }
        }
        addKind(counts, eventKind)
    }

    private fun addKind(
        counts: SubCounts,
        kind: Int,
    ) = trackLock.withLock {
        val now = counts.kinds
        for (i in now.indices) {
            if (now[i].kind == kind) {
                now[i].count.incrementAndFetch()
                return@withLock
            }
        }
        val created = KindCount(kind)
        created.count.incrementAndFetch()
        counts.kinds = now + created
    }

    /** The counts of a subscription seen for the first time; two threads seeing it at once share one. */
    private fun track(subscriptionId: String): SubCounts =
        trackLock.withLock {
            bySubscription.get(subscriptionId)?.let { return it }
            val created = SubCounts(subscriptionId, ++epoch)
            bySubscription.put(subscriptionId, created)
            if (++tracked > maxSubscriptions + slack) evictLeastRecent()
            created
        }

    /** Drops the least recently active subscriptions down to [maxSubscriptions]. Under [trackLock]. */
    private fun evictLeastRecent() {
        if (stamps.size < tracked) stamps = LongArray(tracked + slack)
        val stamps = stamps
        var n = 0
        bySubscription.forEach { _, counts -> if (n < stamps.size) stamps[n++] = counts.lastSeen }
        if (n <= maxSubscriptions) return
        stamps.sort(0, n)
        val toEvict = n - maxSubscriptions
        // Everything seen before the cutoff goes, then as many seen at it as still needed: many
        // subscriptions can share a stamp (all active since the last new one), and keeping every
        // tie would let the map grow past the cap.
        val cutoff = stamps[toEvict - 1]
        var olderThanCutoff = 0
        for (i in 0 until toEvict) if (stamps[i] < cutoff) olderThanCutoff++
        var tiesToEvict = toEvict - olderThanCutoff
        val victims = victims
        bySubscription.forEach { subscriptionId, counts ->
            val seen = counts.lastSeen
            if (seen < cutoff) {
                victims.add(subscriptionId)
            } else if (seen == cutoff && tiesToEvict > 0) {
                tiesToEvict--
                victims.add(subscriptionId)
            }
        }
        for (i in victims.indices) {
            if (bySubscription.remove(victims[i]) != null) tracked--
        }
        victims.clear()
    }

    fun printCounter(action: (subId: String, kind: Int, counter: Int) -> Unit) {
        bySubscription.forEach { _, counts ->
            val kinds = counts.kinds
            for (i in kinds.indices) action(counts.subscriptionId, kinds[i].kind, kinds[i].count.load())
        }
    }

    companion object {
        /** Subscriptions kept by default, the most recently active first. */
        const val DEFAULT_MAX_SUBSCRIPTIONS = 1_000

        private val NO_KINDS = emptyArray<KindCount>()
    }
}
