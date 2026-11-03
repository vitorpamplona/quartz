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

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

/**
 * A live subscription with a callback, open until [close]: what `subscribe(relay, filters) { }`
 * and `subscribe(filters = { … }) { }` return.
 *
 * - **Filters come from [filter].** [refresh] re-reads it and sends what changed; nothing else
 *   calls it, so it runs on the caller's thread only. A reconnect re-sends what the last [refresh]
 *   sent.
 * - **Reconnects resume.** [resume] hears what each relay's REQ delivered and moves the re-sent
 *   filters' `since` to the newest event handed over (two days earlier for gift wraps, whose
 *   `created_at` NIP-59 backdates), so a reconnect doesn't download the relay's whole history
 *   again. Filters that changed since are sent as given. A null [resume] re-sends the filters as
 *   given.
 * - **A CLOSED doesn't end it.** The client sends the REQ again on the next reconnect or AUTH,
 *   unless the relay keeps refusing the same filters. To stop on a CLOSED, or to tail a relay with
 *   backpressure, use `subscribeEvents`.
 */
class DynamicSubscription(
    val client: INostrClient,
    val filter: () -> Map<NormalizedRelayUrl, List<Filter>>,
    val resume: ResumePolicy? = CaughtUpResume(),
    val onEvent: (event: Event) -> Unit = {},
) : SubscriptionListener,
    SubscriptionHandle {
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        client: INostrClient,
        filter: () -> Map<NormalizedRelayUrl, List<Filter>>,
        onEvent: (event: Event) -> Unit = {},
    ) : this(client, filter, CaughtUpResume(), onEvent)

    val subId = RandomInstance.randomChars(10)

    override suspend fun onEvent(
        event: Event,
        isLive: Boolean,
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {
        onEvent(event)
        resume?.onEvent(relay, event, isLive)
    }

    override fun onEose(
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {
        resume?.onEose(relay, forFilters)
    }

    /** The filters the pool is writing to [relay], moved to where [resume] says. */
    override fun filtersForSync(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): List<Filter>? = resume?.resume(relay, filters)

    /**
     * Creates or Updates the filter with relays. This method should be called
     * everytime the filter changes.
     */
    override fun refresh() = client.subscribe(subId, filter(), this)

    override fun close() = client.unsubscribe(subId)

    init {
        refresh()
    }
}

fun INostrClient.subscribe(
    filters: () -> Map<NormalizedRelayUrl, List<Filter>>,
    onEvent: (event: Event) -> Unit = {},
): SubscriptionHandle = DynamicSubscription(this, filters, onEvent = onEvent)
