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

interface SubscriptionListener {
    /**
     * The pool is writing this subscription's REQ to [relay] on a connection: when the connection
     * opens (the first one, or any reconnect) and when it replays every REQ after a successful AUTH.
     * Return the filters to send instead of [filters], or null to send those. [filters] are the ones
     * you subscribed with as this relay is sent them: kinds it refused by name stripped, merged to
     * fit its cap on filters per REQ. They are what [onEose] reports for the REQ.
     *
     * This is how a long-lived subscription keeps a REQ the client re-sends on its own current, a
     * moving `since` for one: the pool asks at the moment the REQ goes out, so nothing has to keep
     * the stored filters up to date in between. The answer applies to that REQ only; the filters
     * the subscription was given stay as they are. A change you make yourself, with
     * `INostrClient.subscribe`, is sent as you made it, without asking.
     *
     * Derive the answer from [filters] with [Filter.copy]: a filter subclass (an app's own, carrying
     * what the filter is for) keeps what it carries that way, and what the pool sends is what it
     * reports as in flight.
     *
     * Called on the connection's own thread, before the REQ is written and outside any lock: keep it
     * cheap and non-blocking. If it throws, the pool sends [filters].
     */
    fun filtersForSync(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): List<Filter>? = null

    fun onEose(
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {}

    /**
     * EOSE together with its NIP-67 completeness [hints] (`finish`, `more`, `auth`, …;
     * null when the relay sent the plain two-element EOSE). The pool calls this one;
     * the default forwards to the two-argument [onEose], so listeners that don't care
     * about hints keep overriding that.
     */
    fun onEose(
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
        hints: List<String>?,
    ) = onEose(relay, forFilters)

    suspend fun onEvent(
        event: Event,
        isLive: Boolean,
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {}

    fun onClosed(
        message: String,
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
    ) {}

    /**
     * CLOSED together with its optional NIP-01 [params] (null when the relay sent the plain
     * three-element CLOSED). For a `rate-limited:` [message] it is the back-off in milliseconds;
     * read it with [com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.RetryAfter.parse].
     * The pool calls this one; the default forwards to the three-argument [onClosed], so
     * listeners that don't care about params keep overriding that. A listener that overrides
     * this one should also forward the three-argument [onClosed] to it: a client that wraps or
     * stands in for the pool may still call that one, which would otherwise go unheard.
     */
    fun onClosed(
        message: String,
        relay: NormalizedRelayUrl,
        forFilters: List<Filter>?,
        params: String?,
    ) = onClosed(message, relay, forFilters)

    fun onCannotConnect(
        relay: NormalizedRelayUrl,
        message: String,
        forFilters: List<Filter>?,
    ) {}

    fun onSubscriptionStarted(
        relay: String,
        forFilters: List<Filter>,
    ) {}

    fun onSubscriptionClosed(relay: String) {}
}
