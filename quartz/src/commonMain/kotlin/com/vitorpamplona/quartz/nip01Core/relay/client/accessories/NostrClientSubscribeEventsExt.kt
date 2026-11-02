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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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
 *   subscription on [relay]'s connection; add a `buffer()` to decouple them. A collector that
 *   stops (cancelled, or the flow ended) never disturbs the connection: its pending events are dropped.
 * - **Reconnects resume.** The client reconnects on its own and re-sends the REQ. Its `since` is
 *   moved forward only when this subscription is known to be caught up — at EOSE, and at each live
 *   event — minus [overlapSeconds]: on EOSE at once, otherwise at most every [sinceUpdateIntervalMs]
 *   (a `since`-only change is stored without a new REQ). A connection that drops for an hour asks
 *   again from just before the drop, not from an hour later. Expect the overlap's events again.
 * - **What a resume can't see.** `since` filters on `created_at`. An event the relay stores while
 *   the connection is down, but created before the resume point (a relay mirroring others does
 *   this all the time), is not sent again; nor is anything past the relay's own cap on stored
 *   results for one REQ. If every event matters, reconcile the gap too (`negentropySync`).
 * - **Restarts resume.** [onResumePoint] hears each `since` the subscription would resume from.
 *   Persist it and pass it back as [since] after a restart. It covers what was handed to this flow,
 *   so it can run ahead of what a lagging collector has processed by up to its buffer; the
 *   overlap covers a lag shorter than [overlapSeconds].
 * - **A closed subscription ends the flow.** After a CLOSED the client does not send the REQ
 *   again on its own (except a `rate-limited:` one that states a back-off, which it retries), so
 *   the subscription would stay silent until something reconnected the relay. A CLOSED for which
 *   [endsOnClosed] is true completes the flow with [SubscriptionClosedException] at once, and the
 *   caller decides: collect again, back off, or stop. One it lets through (by default
 *   `auth-required:`, which a client with a signer answers and replays) still ends the flow if no
 *   EOSE or event follows within [replayTimeoutMs]: a client that can't authenticate never replays it.
 *
 * [since] is where the first REQ starts (null: the relay's whole matching history); any `since`
 * in [filters] is replaced.
 */
@OptIn(ExperimentalAtomicApi::class)
fun INostrClient.subscribeEvents(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    since: Long? = null,
    overlapSeconds: Long = 60,
    sinceUpdateIntervalMs: Long = 300_000,
    endsOnClosed: (message: String) -> Boolean = { MachineReadablePrefix.parse(it) != MachineReadablePrefix.AUTH_REQUIRED },
    replayTimeoutMs: Long = 30_000,
    onResumePoint: (since: Long) -> Unit = {},
    nowInSeconds: () -> Long = TimeUtils::now,
): Flow<Event> =
    callbackFlow {
        val target = relay
        val subId = "live-" + RandomInstance.randomChars(10)
        // When this subscription was last known to be caught up with the relay; 0 = not yet.
        val caughtUpAt = AtomicLong(0L)
        // Moves on every EOSE and event: shows whether a REQ came back after a CLOSED.
        val progress = AtomicLong(0L)
        // Completed when the flow ends, before the unsubscribe: a send waiting for room gives up on
        // it, and a `since` update racing the unsubscribe undoes its own subscribe.
        val stopped = CompletableDeferred<Unit>()
        val sinceUpdates = Channel<Unit>(Channel.CONFLATED)

        fun filtersFrom(from: Long?) = if (from == null) filters else filters.map { it.copy(since = from) }

        fun end(message: String) = close(SubscriptionClosedException(target, message))

        val listener =
            object : SubscriptionListener {
                override suspend fun onEvent(
                    event: Event,
                    isLive: Boolean,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay != target || stopped.isCompleted) return
                    // This runs on the connection's delivery coroutine, which every other
                    // subscription on the relay shares. Waiting for room is the backpressure; waiting
                    // past this flow's end would stall the connection for good (a channel closed by
                    // its collector leaving never wakes a suspended send), so the wait also ends with
                    // the flow, and the event is dropped.
                    val offered = trySend(event)
                    if (offered.isClosed) return
                    if (offered.isFailure) {
                        val delivered =
                            try {
                                select<Boolean> {
                                    onSend(event) { true }
                                    stopped.onAwait { false }
                                }
                            } catch (e: Throwable) {
                                // The connection's own cancellation must still reach it; this
                                // flow ending must not.
                                currentCoroutineContext().ensureActive()
                                if (stopped.isCompleted || isClosedForSend) return
                                throw e
                            }
                        if (!delivered) return
                    }
                    progress.incrementAndFetch()
                    if (isLive) caughtUpAt.store(nowInSeconds())
                }

                override fun onEose(
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay != target) return
                    progress.incrementAndFetch()
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
                    if (endsOnClosed(message)) {
                        end(message)
                        return
                    }
                    // Left open for something to replay the REQ (an AUTH). If nothing comes back, nothing will.
                    val before = progress.load()
                    launch {
                        delay(replayTimeoutMs)
                        if (progress.load() == before) end(message)
                    }
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
                if (current != null && from <= current) continue
                if (stopped.isCompleted) break
                subscribe(subId, mapOf(target to filtersFrom(from)), listener)
                // The flow ended while this subscribed: awaitClose's unsubscribe may already have run.
                if (stopped.isCompleted) {
                    unsubscribe(subId)
                    break
                }
                stored = from
                onResumePoint(from)
            }
        }

        subscribe(subId, mapOf(target to filtersFrom(since)), listener)
        awaitClose {
            stopped.complete(Unit)
            unsubscribe(subId)
        }
    }

/** The relay ended a [subscribeEvents] subscription with a CLOSED, and the client won't re-send it on its own. */
class SubscriptionClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the live subscription: $reason")
