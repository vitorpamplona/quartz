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
 * A subscription whose filters come from [filter]. [refresh] re-reads it and sends what changed;
 * the pool also re-reads it each time it writes this subscription's REQ on a connection (the first
 * one, a reconnect, the replay after an AUTH), so a re-sent REQ carries what [filter] returns now,
 * a moved `since` for instance, not what it returned at the last [refresh].
 *
 * Which relays the subscription is open on changes only on [refresh]: a relay [filter] no longer
 * names keeps the filters it was last given. [filter] runs on the connection's thread then, so keep
 * it cheap.
 */
class DynamicSubscription(
    val client: INostrClient,
    val filter: () -> Map<NormalizedRelayUrl, List<Filter>>,
    val onEvent: (event: Event) -> Unit = {},
) : SubscriptionListener,
    SubscriptionHandle {
    val subId = RandomInstance.randomChars(10)

    override suspend fun onEvent(
        event: Event,
        isLive: Boolean,
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {
        onEvent(event)
    }

    /** What [filter] returns for [relay] now, so a re-sent REQ is current. */
    override fun filtersForSync(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): List<Filter>? = filter()[relay]

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
): SubscriptionHandle = DynamicSubscription(this, filters, onEvent)
