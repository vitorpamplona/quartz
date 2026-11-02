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

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MachineReadablePrefix
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.RetryAfter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The live tail of [relay] for [filters], as a [Flow] of its events, for as long as it is
 * collected: what a long-running consumer (an indexer, a mirror, a graph builder) reads, where
 * `subscribeAsFlow` (which accumulates a list for a screen) and `fetchAsFlow` (which ends at
 * EOSE) do not fit.
 *
 * - **Stored, then live.** The relay's stored matches come first, then each new event as the
 *   relay accepts it. Only [relay]'s events are emitted, whatever else the client is connected to.
 * - **Backpressure.** Each event is handed over with a suspending send, so a collector that falls
 *   behind holds up the subscription instead of losing events. That also holds up every other
 *   subscription on [relay]'s connection; add a `buffer()` to decouple them.
 * - **Reconnects resume.** The client reconnects on its own and re-sends the REQ. Its `since` is
 *   moved forward only when this subscription is known to be caught up — at EOSE, and at each live
 *   event — minus [overlapSeconds], and at most every [sinceUpdateIntervalMs] (a `since`-only change
 *   is stored without a new REQ). A connection that drops for an hour therefore resumes from just
 *   before the drop, not from an hour later. Expect the overlap's events again after a reconnect.
 * - **A closed subscription ends the flow.** After a CLOSED the client does not send the REQ
 *   again on its own (except a `rate-limited:` one that states a back-off, which it retries), so
 *   the subscription would stay silent until something reconnected the relay. Instead, a CLOSED
 *   for which [endsOnClosed] is true completes the flow with [LiveEventsClosedException], and
 *   the caller decides: collect again, back off, or stop. By default every CLOSED but
 *   `auth-required:` ends it, since a client with a signer authenticates and the REQ is replayed;
 *   a client that can't answer AUTH should end on that one too.
 *
 * [since] is where the first REQ starts (null: the relay's whole matching history); any `since`
 * in [filters] is replaced.
 */
@OptIn(ExperimentalAtomicApi::class)
fun INostrClient.liveEvents(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    since: Long? = null,
    overlapSeconds: Long = 60,
    sinceUpdateIntervalMs: Long = 60_000,
    endsOnClosed: (message: String) -> Boolean = { MachineReadablePrefix.parse(it) != MachineReadablePrefix.AUTH_REQUIRED },
    nowInSeconds: () -> Long = TimeUtils::now,
): Flow<Event> =
    callbackFlow {
        val target = relay
        val subId = "live-" + RandomInstance.randomChars(10)
        // When this subscription was last known to be caught up with the relay; 0 = not yet.
        val caughtUpAt = AtomicLong(0L)
        val sinceUpdates = Channel<Unit>(Channel.CONFLATED)

        fun filtersFrom(from: Long?) = if (from == null) filters else filters.map { it.copy(since = from) }

        val listener =
            object : SubscriptionListener {
                override suspend fun onEvent(
                    event: Event,
                    isLive: Boolean,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay != target) return
                    send(event)
                    if (isLive) caughtUpAt.store(nowInSeconds())
                }

                override fun onEose(
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay != target) return
                    caughtUpAt.store(nowInSeconds())
                    sinceUpdates.trySend(Unit)
                }

                override fun onClosed(
                    message: String,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) = onClosed(message, relay, forFilters, null)

                override fun onClosed(
                    message: String,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                    params: String?,
                ) {
                    if (relay != target) return
                    // Retried by the client once the relay's stated back-off passes.
                    if (RetryAfter.parse(message, params) != null) return
                    if (endsOnClosed(message)) close(LiveEventsClosedException(relay, message))
                }
            }

        // Only this coroutine moves `since`, so the stored filters never go backwards.
        launch {
            var stored = since
            while (true) {
                withTimeoutOrNull(sinceUpdateIntervalMs) { sinceUpdates.receive() }
                val at = caughtUpAt.load()
                if (at == 0L) continue
                val from = at - overlapSeconds
                val current = stored
                if (current == null || from > current) {
                    stored = from
                    subscribe(subId, mapOf(target to filtersFrom(from)), listener)
                }
            }
        }

        subscribe(subId, mapOf(target to filtersFrom(since)), listener)
        awaitClose { unsubscribe(subId) }
    }

/** The relay ended a [liveEvents] subscription with a CLOSED, and the client won't re-send it on its own. */
class LiveEventsClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the live subscription: $reason")
