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
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.cache.ConcurrentHashCache
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Where a live subscription resumes, per relay, when the client writes its REQ again (a reconnect,
 * the replay after an AUTH). The subscription only reports and asks; the policy decides.
 *
 * `subscribeEvents`, `DynamicSubscription` (`subscribe(relay, filters) { }`,
 * `subscribe(filters = { … })`) and `subscribeAsFlow` all use one.
 *
 * [onEvent] and [onEose] come from the connection's delivery coroutine, which every subscription on
 * that relay shares, [onEvent] once per event; [resumeFrom] from the one writing the REQ. Keep all
 * three cheap and non-blocking; save state elsewhere, on your own schedule.
 */
interface ResumePolicy {
    /**
     * The subscription handed [event] from [relay] to its subscriber: a stored one, before EOSE
     * ([isLive] false), or a live one after it.
     */
    fun onEvent(
        relay: NormalizedRelayUrl,
        event: Event,
        isLive: Boolean,
    )

    /**
     * EOSE from [relay] for the REQ that carried [filters] (null when the client can't say): every
     * stored match it had has been handed over.
     */
    fun onEose(
        relay: NormalizedRelayUrl,
        filters: List<Filter>?,
    )

    /**
     * Each time the client writes the REQ for [filters] on [relay]: the `since` to ask from, or null
     * to send them as given. A filter is never moved earlier than its own `since` ([resume]).
     */
    fun resumeFrom(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): Long?
}

/**
 * [filters] moved to where [ResumePolicy.resumeFrom] says, each one never earlier than its own
 * `since`, or null to send them as given. What a subscription answers
 * [SubscriptionListener.filtersForSync] with.
 *
 * A filter that asks for gift wraps ([GiftWrapEvent.KIND] in its `kinds`) resumes two days earlier:
 * NIP-59 backdates a wrap's `created_at` by up to two days, so one sent during the outage can carry
 * a `created_at` from before it. A filter without `kinds` gets no such window.
 *
 * A filter whose `until` is before the resume point is sent as given: moving its `since` past its
 * `until` would ask for nothing, and some relays refuse such a filter outright.
 */
fun ResumePolicy.resume(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
): List<Filter>? {
    val from = resumeFrom(relay, filters) ?: return null
    return filters.map { f ->
        val at = if (f.kinds?.contains(GiftWrapEvent.KIND) == true) from - TimeUtils.twoDays() else from
        val until = f.until
        when {
            until != null && until < at -> f
            // A filter that already starts later than the resume point keeps its own `since`.
            f.since?.let { it >= at } == true -> f
            else -> f.copy(since = at)
        }
    }
}

/**
 * The default [ResumePolicy]: per relay, the newest `created_at` the subscriber was handed, capped
 * at this device's clock. A connection that drops for an hour asks again from the last event it
 * delivered. Gift wraps get NIP-59's two-day window on top ([resume]).
 *
 * The point is a `created_at`, not the time the event was processed, because `since` filters on
 * `created_at`: messages the relay sent that were still queued when the client dropped the
 * connection, and a device clock running ahead of the relay's, can't move it past an event the
 * subscriber never saw. Stored events count only at EOSE: the relay sends them newest first, so a
 * connection cut halfway through leaves older ones undelivered. Until the subscription has been
 * handed an event there is nothing to resume from, and its filters go as given.
 *
 * The point holds only for the filters that were caught up. Filters that changed since (an author
 * added, a kind dropped; a moved `since` doesn't count) are sent as given, so a newly wanted
 * author's history is still asked for in full.
 *
 * An event the relay receives after the point but created before it (a relay mirroring others
 * does this all the time, and so does a publisher whose clock runs behind) is not sent again, nor
 * is anything past the relay's cap on stored results for one REQ. If every event matters,
 * reconcile the gap too (`negentropySync`).
 *
 * To resume after a restart, read [resumeFrom] on your own schedule, persist it, and set it as the
 * filters' `since` next time. It covers what was handed to the subscriber, so it can run ahead of
 * what a lagging consumer has processed by up to its buffer.
 */
@OptIn(ExperimentalAtomicApi::class)
class CaughtUpResume(
    private val nowInSeconds: () -> Long = TimeUtils::now,
) : ResumePolicy {
    private class Point {
        /** The newest `created_at` the subscription is caught up with; [NEVER] before the first. */
        val at = AtomicLong(NEVER)

        /** The newest `created_at` among the stored events of the REQ in flight, counted at its EOSE. */
        val pending = AtomicLong(NEVER)

        /** The filters of the REQ that last reached EOSE. */
        @Volatile var filters: List<Filter>? = null
    }

    private val points = ConcurrentHashCache<NormalizedRelayUrl, Point>()
    private val creating = PlatformLock()

    /**
     * The device clock as last read. A `created_at` at or below it is already in the past, so most
     * events need no clock read: a live tail's events arrive newer than the last one, and reading
     * the clock for each was most of what this policy cost per event. If the clock is stepped
     * back, events dated up to the earlier reading go uncapped until one is newer than it.
     */
    @Volatile private var clockRead = Long.MIN_VALUE

    private fun point(relay: NormalizedRelayUrl): Point =
        points.get(relay) ?: creating.withLock {
            points.get(relay) ?: Point().also { points.put(relay, it) }
        }

    /** The `created_at` the subscription would resume [relay] from, or null if it has none yet. */
    fun caughtUpAt(relay: NormalizedRelayUrl): Long? =
        points
            .get(relay)
            ?.at
            ?.load()
            ?.takeIf { it != NEVER }

    override fun onEvent(
        relay: NormalizedRelayUrl,
        event: Event,
        isLive: Boolean,
    ) {
        val point = point(relay)
        val cell = if (isLive) point.at else point.pending
        val createdAt = event.createdAt
        // An event that doesn't move the point costs a lookup and a compare.
        if (createdAt <= cell.load()) return
        raise(cell, if (createdAt <= clockRead) createdAt else minOf(createdAt, readClock()))
    }

    override fun onEose(
        relay: NormalizedRelayUrl,
        filters: List<Filter>?,
    ) {
        val point = point(relay)
        val stored = point.pending.exchange(NEVER)
        if (stored != NEVER) raise(point.at, stored)
        if (filters != null) point.filters = filters
    }

    private fun readClock(): Long = nowInSeconds().also { clockRead = it }

    /**
     * The caught-up REQ may itself have been resumed: only `since` is allowed to differ. Compared
     * field by field rather than through copies without `since`: a [Filter] checks every author
     * when it is built, and a REQ for a few hundred follows is written on every reconnect.
     */
    private fun sameIgnoringSince(
        caughtUp: List<Filter>,
        filters: List<Filter>,
    ): Boolean {
        if (caughtUp.size != filters.size) return false
        for (i in caughtUp.indices) {
            val a = caughtUp[i]
            val b = filters[i]
            if (a.ids != b.ids || a.authors != b.authors || a.kinds != b.kinds || a.tags != b.tags || a.tagsAll != b.tagsAll ||
                a.until != b.until || a.limit != b.limit || a.search != b.search
            ) {
                return false
            }
        }
        return true
    }

    override fun resumeFrom(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): Long? {
        val point = points.get(relay) ?: return null
        val at = point.at.load()
        if (at == NEVER) return null
        val forFilters = point.filters ?: return null
        if (!sameIgnoringSince(forFilters, filters)) return null
        return at
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE

        fun raise(
            cell: AtomicLong,
            to: Long,
        ) {
            while (true) {
                val current = cell.load()
                if (to <= current || cell.compareAndSet(current, to)) return
            }
        }
    }
}
