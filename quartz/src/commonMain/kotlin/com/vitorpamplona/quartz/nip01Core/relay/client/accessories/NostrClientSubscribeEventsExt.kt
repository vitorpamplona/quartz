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
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.resume
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
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
 *   write ([SubscriptionListener.filtersForSync]). [resume] answers, per relay: it hears when the
 *   subscription is caught up (at EOSE, and at each live event) and names the `since` to resume
 *   from. A healthy connection costs nothing per event. The default, [CaughtUpResume], resumes from
 *   the last caught-up moment minus an overlap, and says what that can't see.
 * - **A CLOSED ends the flow.** The client doesn't send a CLOSED REQ again on the same connection,
 *   only on the next reconnect or AUTH, and not even then once the relay has refused the same
 *   filters three times running. On a healthy connection that can be never, and a live tail that
 *   is silent until the socket happens to drop is worse than one that stops. So the flow completes
 *   with [SubscriptionClosedException], naming the relay, and the caller decides: collect again,
 *   back off, or stop. One relay's CLOSED ends the whole flow; to keep relays independent, collect
 *   one flow per relay. Two CLOSEDs the client recovers from itself don't end it:
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
@OptIn(ExperimentalAtomicApi::class)
fun INostrClient.subscribeEvents(
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy? = CaughtUpResume(),
    authSettleMs: Long = 120_000,
): Flow<Event> =
    callbackFlow {
        val subId = "live-" + RandomInstance.randomChars(10)
        // Per relay, AUTHs accepted as of the last time it served us: an `auth-required:` refusal
        // waits for one newer than this, which is when the client re-sends the REQ.
        val authMarks = filters.keys.associateWith { AtomicInt(authSuccessMark(it)) }
        // Completed when the flow ends, before the unsubscribe: a send waiting for room gives up on it.
        val stopped = CompletableDeferred<Unit>()

        fun end(
            relay: NormalizedRelayUrl,
            message: String,
        ) = close(SubscriptionClosedException(relay, message))

        val listener =
            object : SubscriptionListener {
                // The pool asks this each time it writes the REQ on a connection: that is where a
                // reconnect, or the replay after an AUTH, picks up the resume point.
                override fun filtersForSync(
                    relay: NormalizedRelayUrl,
                    filters: List<Filter>,
                ): List<Filter>? {
                    if (relay !in authMarks || stopped.isCompleted) return null
                    return resume?.resume(relay, filters)
                }

                override suspend fun onEvent(
                    event: Event,
                    isLive: Boolean,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    if (relay !in authMarks || stopped.isCompleted) return
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
                    if (isLive) resume?.onCaughtUp(relay, forFilters)
                }

                override fun onEose(
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    val mark = authMarks[relay] ?: return
                    mark.store(authSuccessMark(relay))
                    resume?.onCaughtUp(relay, forFilters)
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
                    val mark = authMarks[relay] ?: return
                    // Retried by the client once the relay's stated back-off passes.
                    if (RetryAfter.parse(message, params) != null) return
                    if (MachineReadablePrefix.parse(message) != MachineReadablePrefix.AUTH_REQUIRED) {
                        end(relay, message)
                        return
                    }
                    val since = mark.load()
                    launch {
                        if (awaitAuthOutcome(relay, since, settleMs = authSettleMs) == AuthOutcome.AUTHENTICATED) {
                            // The client re-sent the REQ on the AUTH's OK. A later refusal needs a newer AUTH.
                            mark.store(authSuccessMark(relay))
                        } else {
                            end(relay, message)
                        }
                    }
                }
            }

        subscribe(subId, filters, listener)
        awaitClose {
            stopped.complete(Unit)
            unsubscribe(subId)
        }
    }

/**
 * A relay ended a [subscribeEvents] subscription with a CLOSED: [reason]. The client would not send
 * the REQ again on this connection.
 */
class SubscriptionClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the live subscription: $reason")
