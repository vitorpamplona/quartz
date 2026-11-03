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
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.AuthOutcome
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.authSuccessMark
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.awaitAuthOutcome
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MachineReadablePrefix
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.RetryAfter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.concurrent.atomics.AtomicInt
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
 *   subscription on [relay]'s connection; add a `buffer()` to decouple them. A collector that
 *   stops (cancelled, or the flow ended) never disturbs the connection: its pending events are dropped.
 * - **Reconnects resume.** The client reconnects on its own and re-sends the REQ. [resume] hears
 *   when the subscription is caught up (at EOSE, and at each live event) and, once per disconnect,
 *   names the `since` to resume from; the stored filters are rewritten with it before the client
 *   re-sends them (it tells its connection listeners before it schedules the reconnect). A healthy
 *   connection costs nothing per event. The default, [CaughtUpResume], resumes from the last
 *   caught-up moment minus an overlap, and says what that can't see. A REQ the client re-sends on
 *   a live connection, after an AUTH, keeps the older `since`: it re-reads, and misses nothing.
 * - **A closed subscription ends the flow.** After a CLOSED the client does not send the REQ
 *   again on its own, so the subscription would stay silent until something reconnected the
 *   relay. Instead the flow completes with [SubscriptionClosedException], and the caller decides:
 *   collect again, back off, or stop. Two CLOSEDs the client recovers from itself don't end it:
 *   - a `rate-limited:` one that states a back-off, which the client retries once it passes;
 *   - an `auth-required:` one, while the client's NIP-42 responder can still get in. It waits on
 *     the AUTH's own verdict ([awaitAuthOutcome]): on success the client has re-sent the REQ and
 *     the flow carries on; with no responder attached it ends at once; a responder that declines,
 *     an AUTH the relay refuses, or a signer still unfinished after [authSettleMs] (a person
 *     approving a NIP-46/NIP-55 prompt) ends it then.
 *
 * The first REQ asks for [filters] as given: each filter's own `since` is where it starts (none:
 * the relay's whole matching history). A resume moves every filter to the same point, but never
 * earlier than its own `since`: one REQ gets one EOSE, so "caught up" holds for all of them at once.
 * A null [resume] never rewrites them.
 */
@OptIn(ExperimentalAtomicApi::class)
fun INostrClient.subscribeEvents(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    resume: ResumePolicy? = CaughtUpResume(),
    authSettleMs: Long = 120_000,
): Flow<Event> =
    callbackFlow {
        val target = relay
        val subId = "live-" + RandomInstance.randomChars(10)
        // AUTHs accepted on the relay as of the last time it served us: an `auth-required:` refusal
        // waits for one newer than this, which is when the client re-sends the REQ.
        val authMark = AtomicInt(authSuccessMark(target))
        // The resume point the stored filters carry; 0 = still the filters as given.
        val storedFrom = AtomicLong(0L)
        // Completed when the flow ends, before the unsubscribe: a send waiting for room gives up on
        // it, and a `since` update racing the unsubscribe undoes its own subscribe.
        val stopped = CompletableDeferred<Unit>()

        // A filter that already starts later than the resume point keeps its own `since`.
        fun filtersFrom(from: Long) = filters.map { f -> f.since?.takeIf { it >= from }?.let { f } ?: f.copy(since = from) }

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
                    if (isLive) resume?.onCaughtUp()
                }

                override fun onEose(
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay != target) return
                    authMark.store(authSuccessMark(target))
                    resume?.onCaughtUp()
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
                    if (MachineReadablePrefix.parse(message) != MachineReadablePrefix.AUTH_REQUIRED) {
                        end(message)
                        return
                    }
                    val since = authMark.load()
                    launch {
                        if (awaitAuthOutcome(target, since, settleMs = authSettleMs) == AuthOutcome.AUTHENTICATED) {
                            // The client re-sent the REQ on the AUTH's OK. A later refusal needs a newer AUTH.
                            authMark.store(authSuccessMark(target))
                        } else {
                            end(message)
                        }
                    }
                }
            }

        // Rewrites the stored filters from the policy's resume point, once per disconnect, before
        // the client re-sends them. Runs synchronously in the client's disconnect callback: a
        // coroutine could lose the race with the reconnect.
        val connection =
            object : RelayConnectionListener {
                override fun onDisconnected(relay: IRelayClient) {
                    if (relay.url != target || stopped.isCompleted) return
                    val from = resume?.resumeFrom() ?: return
                    // Only forward: a stale callback never moves `since` back.
                    while (true) {
                        val current = storedFrom.load()
                        if (current != 0L && from <= current) return
                        if (storedFrom.compareAndSet(current, from)) break
                    }
                    subscribe(subId, mapOf(target to filtersFrom(from)), listener)
                    // The flow ended while this subscribed: awaitClose's unsubscribe may already have run.
                    if (stopped.isCompleted) unsubscribe(subId)
                }
            }

        if (resume != null) addConnectionListener(connection)
        subscribe(subId, mapOf(target to filters), listener)
        awaitClose {
            stopped.complete(Unit)
            removeConnectionListener(connection)
            unsubscribe(subId)
        }
    }

/** The relay ended a [subscribeEvents] subscription with a CLOSED, and the client won't re-send it on its own. */
class SubscriptionClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the live subscription: $reason")
