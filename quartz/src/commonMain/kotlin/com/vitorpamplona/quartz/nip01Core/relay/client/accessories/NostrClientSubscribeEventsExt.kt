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
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.resume
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
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
): Flow<Event> = relayEvents(filters, resume, authSettleMs, endAtEose = false)

/**
 * The flow behind [subscribeEvents] and [fetchEvents]: the same hand-over, backpressure and
 * CLOSED/AUTH handling, either for as long as it is collected or, with [endAtEose], until every
 * relay in [filters] has sent its EOSE. A fetch also ends with [RelayUnreachableException] when a
 * relay can't be reached; a live tail keeps waiting for the client to reconnect.
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun INostrClient.relayEvents(
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy?,
    authSettleMs: Long,
    endAtEose: Boolean,
): Flow<Event> =
    callbackFlow {
        val subId = (if (endAtEose) "fetch-" else "live-") + RandomInstance.randomChars(10)
        // Per relay, AUTHs accepted on its current connection as of the last time it served us or
        // the REQ was written: an `auth-required:` refusal waits for one newer than this, which is
        // when the client re-sends the REQ. The count starts again on every connection.
        val authMarks = filters.keys.associateWith { AtomicInt(authSuccessMark(it)) }
        // Completed when the flow ends, before the unsubscribe: a send waiting for room gives up on it.
        val stopped = CompletableDeferred<Unit>()
        // Per relay, how many times its connection dropped, and how many CLOSEDs in a row were
        // followed by one (a relay going away) since it last reached EOSE.
        val drops = filters.keys.associateWith { MutableStateFlow(0) }
        val goingAwayInARow = filters.keys.associateWith { AtomicInt(0) }
        // A fetch: which relays sent their EOSE (counted once each, a re-sent REQ sends another),
        // and how many have yet to.
        val reachedEose = filters.keys.associateWith { AtomicInt(0) }
        val stillServing = AtomicInt(filters.size)
        val connections =
            object : RelayConnectionListener {
                override fun onDisconnected(relay: IRelayClient) {
                    drops[relay.url]?.update { it + 1 }
                }
            }
        addConnectionListener(connections)

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
                    val mark = authMarks[relay] ?: return null
                    if (stopped.isCompleted) return null
                    // AUTHs are counted per connection, and this REQ is going out on the current
                    // one: a refusal of it waits for an AUTH newer than what this connection has
                    // seen, not the last one, which would leave a reconnect waiting forever.
                    mark.store(authSuccessMark(relay))
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
                    resume?.onEvent(relay, event, isLive)
                }

                override fun onEose(
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    val mark = authMarks[relay] ?: return
                    mark.store(authSuccessMark(relay))
                    goingAwayInARow[relay]?.store(0)
                    resume?.onEose(relay, forFilters)
                    // EOSE comes on the delivery coroutine after this relay's stored events were
                    // handed over, so closing here still lets the collector receive every one.
                    if (endAtEose && reachedEose[relay]?.compareAndSet(0, 1) == true && stillServing.decrementAndFetch() == 0) {
                        close()
                    }
                }

                override fun onCannotConnect(
                    relay: NormalizedRelayUrl,
                    message: String,
                    forFilters: List<Filter>?,
                ) {
                    if (endAtEose && relay in authMarks) close(RelayUnreachableException(relay, message))
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
                    when (MachineReadablePrefix.parse(message)) {
                        MachineReadablePrefix.AUTH_REQUIRED -> {}
                        // A relay going away (a restart, a deploy) CLOSEs its subscriptions with an
                        // `error:` (or no prefix) and then drops the socket; the client sends the REQ
                        // again on the next connection, so that CLOSED doesn't end the flow. Three in a
                        // row without an EOSE do: the client stops re-sending a REQ refused that often,
                        // and the flow would go silent.
                        MachineReadablePrefix.ERROR, null -> {
                            val dropsAtClose = drops[relay]?.value ?: 0
                            launch {
                                val goingAway = withTimeoutOrNull(GOING_AWAY_GRACE_MS) { drops[relay]?.first { it > dropsAtClose } } != null
                                if (!goingAway || (goingAwayInARow[relay]?.addAndFetch(1) ?: 0) >= MAX_GOING_AWAY_IN_A_ROW) end(relay, message)
                            }
                            return
                        }
                        // `blocked:`, `restricted:`, `invalid:`, …: a refusal, not a shutdown.
                        else -> {
                            end(relay, message)
                            return
                        }
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
            removeConnectionListener(connections)
            unsubscribe(subId)
        }
    }

/** How long after a CLOSED the connection may drop for the CLOSED to count as the relay going away. */
private const val GOING_AWAY_GRACE_MS = 3_000L

/** The pool stops re-sending a REQ its relay refused this many times running. */
private const val MAX_GOING_AWAY_IN_A_ROW = 3

/**
 * A relay ended a [subscribeEvents] or [fetchEvents] subscription with a CLOSED: [reason]. The
 * client would not send the REQ again on this connection.
 */
class SubscriptionClosedException(
    val relay: NormalizedRelayUrl,
    val reason: String,
) : Exception("${relay.url} closed the subscription: $reason")
