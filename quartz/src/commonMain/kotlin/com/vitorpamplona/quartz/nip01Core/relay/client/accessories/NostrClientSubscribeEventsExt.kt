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
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.resume
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.flow.Flow

/**
 * The live tail of [relay] for [filters], as a [Flow] of its events. See the multi-relay form.
 */
fun INostrClient.subscribeEvents(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    resume: ResumePolicy? = CaughtUpResume(),
    authSettleMs: Long = 120_000,
): Flow<Event> = subscribeEvents(mapOf(relay to filters), resume, authSettleMs)

/**
 * The live tail of each relay in [filters], for its filters, as one [Flow] of their events, for as
 * long as it is collected: what a long-running consumer (an indexer, a mirror, a graph builder)
 * reads, where `subscribeAsFlow` (which accumulates a list for a screen) and `fetchAsFlow` (which
 * ends at EOSE) do not fit.
 *
 * - **Stored, then live.** Each relay's stored matches come first, then each new event as the
 *   relay accepts it. Only these relays' events are emitted, whatever else the client is connected
 *   to. The same event from two relays is emitted twice.
 * - **Backpressure.** Each event is handed over with a suspending send, so a collector that falls
 *   behind holds up the subscription instead of losing events. That also holds up every other
 *   subscription on that relay's connection; add a `buffer()` to decouple them. A collector that
 *   stops (cancelled, or the flow ended) never disturbs the connection: its pending events are dropped.
 * - **Reconnects resume.** The client reconnects on its own and re-sends the REQ, and each time it
 *   does (a reconnect, or the replay after an AUTH) it asks the subscription for the filters to
 *   write ([SubscriptionListener.filtersForSync]). [resume] answers, per relay: it hears each event handed
 *   over and each EOSE, and names the `since` to resume from. The default, [CaughtUpResume],
 *   resumes from the newest event delivered (two days earlier for gift wraps), and says what that
 *   can't see.
 * - **A CLOSED ends the flow.** The client doesn't send a CLOSED REQ again on the same connection,
 *   only on the next reconnect or AUTH, and not even then once the relay has refused the same
 *   filters three times running. On a healthy connection that can be never, and a live tail that
 *   is silent until the socket happens to drop is worse than one that stops. So the flow completes
 *   with [SubscriptionClosedException], naming the relay, and the caller decides: collect again,
 *   back off, or stop. One relay's CLOSED ends the whole flow; to keep relays independent, collect
 *   one flow per relay. Three kinds of CLOSED the client recovers from itself don't end it:
 *   - an `error:` or unprefixed one followed within 3 s by the connection dropping: a relay going
 *     away (a restart, a deploy) CLOSEs its subscriptions first, and the client sends the REQ again
 *     on the next connection.
 *     Three of those in a row with no EOSE between them do end it;
 *   - a `rate-limited:` one that states a back-off, which the client retries once it passes;
 *   - an `auth-required:` one, while the client's NIP-42 responder can still get in. It waits on
 *     the AUTH's own verdict ([awaitAuthOutcome]): on success the client has re-sent the REQ and
 *     the flow carries on; with no responder attached it ends at once; a responder that declines,
 *     an AUTH the relay refuses, or a signer still unfinished after [authSettleMs] (a person
 *     approving a NIP-46/NIP-55 prompt) ends it then.
 *
 * Each filter starts from its own `since` (none: the relay's whole matching history). A resume
 * moves every filter of a relay to the same point, but never earlier than its own `since`: one REQ
 * gets one EOSE, so "caught up" holds for all of them at once. A null [resume] always sends the
 * filters as given.
 */
fun INostrClient.subscribeEvents(
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy? = CaughtUpResume(),
    authSettleMs: Long = 120_000,
): Flow<Event> = streamReqAsFlow(filters, resume, authSettleMs, untilEose = false)

/**
 * A relay ended a [subscribeEvents] or [fetchEvents] subscription with a CLOSED: [reason]. The
 * client would not send the REQ again on this connection.
 */
class SubscriptionClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the subscription: $reason")
