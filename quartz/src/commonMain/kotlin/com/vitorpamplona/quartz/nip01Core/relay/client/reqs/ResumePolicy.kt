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

import com.vitorpamplona.quartz.nip01Core.relay.client.pool.FiltersChanged
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.cache.ConcurrentHashCache

/**
 * Where a live subscription resumes, per relay, when the client writes its REQ again (a reconnect,
 * the replay after an AUTH). The subscription only reports and asks; the policy decides.
 *
 * `subscribeEvents`, `DynamicSubscription` (`subscribe(relay, filters) { }`,
 * `subscribe(filters = { … })`) and `subscribeAsFlow` all use one.
 *
 * Both calls come from the client's own threads: [onCaughtUp] from the connection's delivery
 * coroutine, which every subscription on that relay shares, and [resumeFrom] from the one writing
 * the REQ. Keep both cheap and non-blocking; save state elsewhere, on your own schedule.
 */
interface ResumePolicy {
    /**
     * At EOSE and at each live event: the subscription is caught up with [relay] as of now, for
     * [filters], the ones that REQ carried (null when the client can't say).
     */
    fun onCaughtUp(
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
 */
fun ResumePolicy.resume(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
): List<Filter>? {
    val from = resumeFrom(relay, filters) ?: return null
    return filters.map { f ->
        val at = if (f.kinds?.contains(GiftWrapEvent.KIND) == true) from - TimeUtils.twoDays() else from
        // A filter that already starts later than the resume point keeps its own `since`.
        f.since?.takeIf { it >= at }?.let { f } ?: f.copy(since = at)
    }
}

/**
 * The default [ResumePolicy]: per relay, the last moment the subscription was caught up. A
 * connection that drops for an hour asks again from when it dropped. Gift wraps get NIP-59's
 * two-day window on top ([resume]).
 *
 * The point holds only for the filters that were caught up. Filters that changed since (an author
 * added, a kind dropped; a moved `since` doesn't count) are sent as given, so a newly wanted
 * author's history is still asked for in full.
 *
 * `since` filters on `created_at`, so an event the relay stores while the connection is down but
 * created before the resume point (a relay mirroring others does this all the time) is not sent
 * again, nor is anything past the relay's cap on stored results for one REQ. If every event
 * matters, reconcile the gap too (`negentropySync`).
 *
 * To resume after a restart, read [resumeFrom] on your own schedule, persist it, and set it as the
 * filters' `since` next time. It covers what was handed to the subscriber, so it can run ahead of
 * what a lagging consumer has processed by up to its buffer.
 */
class CaughtUpResume(
    private val nowInSeconds: () -> Long = TimeUtils::now,
) : ResumePolicy {
    private class CaughtUp(
        val at: Long,
        val filters: List<Filter>?,
    )

    private val caughtUp = ConcurrentHashCache<NormalizedRelayUrl, CaughtUp>()

    /** Epoch seconds the subscription was last caught up with [relay], or null if never. */
    fun caughtUpAt(relay: NormalizedRelayUrl): Long? = caughtUp.get(relay)?.at

    override fun onCaughtUp(
        relay: NormalizedRelayUrl,
        filters: List<Filter>?,
    ) {
        // A live event names no filters; it is still caught up with the ones it last had.
        caughtUp.put(relay, CaughtUp(nowInSeconds(), filters ?: caughtUp.get(relay)?.filters))
    }

    /** The caught-up REQ may itself have been resumed: only `since` is allowed to differ. */
    private fun sameIgnoringSince(
        caughtUp: List<Filter>,
        filters: List<Filter>,
    ): Boolean {
        if (caughtUp.size != filters.size) return false
        for (i in caughtUp.indices) {
            if (FiltersChanged.needsToResendRequest(caughtUp[i].copy(since = null), filters[i].copy(since = null))) return false
        }
        return true
    }

    override fun resumeFrom(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): Long? {
        val last = caughtUp.get(relay) ?: return null
        val forFilters = last.filters ?: return null
        if (!sameIgnoringSince(forFilters, filters)) return null
        return last.at
    }
}
