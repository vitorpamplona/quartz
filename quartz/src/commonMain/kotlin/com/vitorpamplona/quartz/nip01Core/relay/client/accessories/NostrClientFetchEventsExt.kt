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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.flow.Flow

/**
 * What [relay] holds for [filters], as a [Flow] of its events that completes at the relay's EOSE:
 * one REQ, no subscription id, listener or unsubscribe to manage. Each collection sends its own
 * REQ; cancelling the collector closes it.
 *
 * - **As the relay sent them.** Events are emitted one at a time in arrival order and are not
 *   verified, deduped, stored or sorted. Verify them (`Event.verify()`) if they matter: like every
 *   [INostrClient] read, this hands over what the relay sent. An event the relay sends twice is
 *   emitted twice, and so is everything the relay re-sends when the client sends the REQ again
 *   after a reconnect.
 * - **Backpressure.** Each event is handed over with a suspending send, so a collector that falls
 *   behind holds up the subscription instead of losing events. That also holds up every other
 *   subscription on that relay's connection; add a `buffer()` to decouple them.
 * - **How it ends.** At EOSE the flow completes. A CLOSED ends it with [SubscriptionClosedException]
 *   and a relay the client can't connect to with [RelayUnreachableException], so "the relay had
 *   nothing" (an empty, completed flow) is never confused with "the relay didn't answer". The
 *   CLOSEDs the client recovers from itself don't end it, as in [subscribeEvents]: a relay going
 *   away (an `error:` CLOSED and the connection dropping), a `rate-limited:` one with a back-off,
 *   and an `auth-required:` one while this client's NIP-42 responder can still get in (at most
 *   [authSettleMs] for a signer someone is approving).
 *
 * There is no idle timeout: a relay that accepts the REQ and then never finishes keeps the flow
 * open. Bound it at the call site with `withTimeout`.
 *
 * [subscribeEvents] is the same flow kept open past EOSE for new events.
 */
fun INostrClient.fetchEvents(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    authSettleMs: Long = 120_000,
): Flow<Event> = streamReqAsFlow(mapOf(relay to filters), resume = null, authSettleMs = authSettleMs, untilEose = true)

/** [fetchEvents] for one [filter]. */
fun INostrClient.fetchEvents(
    relay: NormalizedRelayUrl,
    filter: Filter,
    authSettleMs: Long = 120_000,
): Flow<Event> = fetchEvents(relay, listOf(filter), authSettleMs)

/** [fetchEvents] for a relay url that is normalized first. */
fun INostrClient.fetchEvents(
    relay: String,
    filters: List<Filter>,
    authSettleMs: Long = 120_000,
): Flow<Event> = fetchEvents(RelayUrlNormalizer.normalize(relay), filters, authSettleMs)

/** [fetchEvents] for a relay url that is normalized first, and one [filter]. */
fun INostrClient.fetchEvents(
    relay: String,
    filter: Filter,
    authSettleMs: Long = 120_000,
): Flow<Event> = fetchEvents(RelayUrlNormalizer.normalize(relay), listOf(filter), authSettleMs)

/**
 * Sends one REQ with [filters] to [relay], hands each event to [onEvent] as it arrives, and returns
 * at the relay's EOSE with how many it handed over. What it does and does not do (no verify, no
 * dedup) and how it ends are as in [fetchEvents]: it throws [SubscriptionClosedException] or
 * [RelayUnreachableException] when the relay refuses or can't be reached, and rethrows what
 * [onEvent] throws.
 *
 * Unlike [fetchEvents], there is no flow in between: [onEvent] is called directly on the relay's
 * delivery coroutine, as a [com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener]
 * is, one event at a time. That coroutine serves every subscription on the connection, so keep
 * [onEvent] short, and switch context yourself if it must run elsewhere (on a UI thread, say).
 * Cancelling the caller stops the fetch once the current [onEvent] returns.
 */
suspend fun INostrClient.fetch(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    authSettleMs: Long = 120_000,
    onEvent: suspend (Event) -> Unit,
): Int {
    var delivered = 0
    streamReq(mapOf(relay to filters), resume = null, authSettleMs = authSettleMs, untilEose = true) { event ->
        onEvent(event)
        delivered++
        true
    }
    return delivered
}

/** [fetch] for one [filter]. */
suspend fun INostrClient.fetch(
    relay: NormalizedRelayUrl,
    filter: Filter,
    authSettleMs: Long = 120_000,
    onEvent: suspend (Event) -> Unit,
): Int = fetch(relay, listOf(filter), authSettleMs, onEvent)

/** [fetch] for a relay url that is normalized first. */
suspend fun INostrClient.fetch(
    relay: String,
    filters: List<Filter>,
    authSettleMs: Long = 120_000,
    onEvent: suspend (Event) -> Unit,
): Int = fetch(RelayUrlNormalizer.normalize(relay), filters, authSettleMs, onEvent)

/** [fetch] for a relay url that is normalized first, and one [filter]. */
suspend fun INostrClient.fetch(
    relay: String,
    filter: Filter,
    authSettleMs: Long = 120_000,
    onEvent: suspend (Event) -> Unit,
): Int = fetch(RelayUrlNormalizer.normalize(relay), listOf(filter), authSettleMs, onEvent)

/** [fetchEvents] or [fetch] could not reach [relay]: [reason] is the client's connection error. */
class RelayUnreachableException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} could not be reached: $reason")
