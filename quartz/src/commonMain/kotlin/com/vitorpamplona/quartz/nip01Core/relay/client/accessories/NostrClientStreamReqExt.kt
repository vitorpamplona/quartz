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
import com.vitorpamplona.quartz.nip01Core.relay.client.IgnoresRelayMessages
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.AuthOutcome
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.authSuccessMark
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.awaitAuthOutcome
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.resume
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MachineReadablePrefix
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.RetryAfter
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
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
import kotlin.concurrent.atomics.decrementAndFetch

/**
 * The read behind [subscribeEvents], [fetchEvents] and [fetch]: sends one REQ per relay in
 * [filters], hands each event to [onEvent], and calls [onEnd] once when the read ends:
 *
 * - with null, under [untilEose], once every relay has sent its EOSE;
 * - with [SubscriptionClosedException] on a CLOSED the client won't recover from;
 * - with [RelayUnreachableException], under [untilEose], when a relay can't be reached (a live
 *   read keeps waiting for the client to reconnect);
 * - with what [onEvent] threw.
 *
 * [onEvent] runs on each relay's delivery coroutine, the one every subscription on that connection
 * shares: one event at a time per relay, concurrently across relays, and while it runs the
 * connection waits. It returns whether it took the event; one it didn't take (a consumer on its way
 * out) is not counted by [resume]. No event is handed over once the read has ended.
 *
 * The grace and AUTH waits a CLOSED starts run in [scope]. Dispose the returned handle when done
 * with the read, whether or not it ended: it closes the REQ, cancels those waits, and ends the
 * read without calling [onEnd].
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun INostrClient.startReq(
    scope: CoroutineScope,
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy?,
    authSettleMs: Long,
    untilEose: Boolean,
    onEvent: ReqEventHandler,
    onEnd: (Throwable?) -> Unit,
): DisposableHandle {
    val subId = (if (untilEose) "fetch-" else "live-") + RandomInstance.randomChars(10)
    // Per relay, AUTHs accepted on its current connection as of the last time it served us or
    // the REQ was written: an `auth-required:` refusal waits for one newer than this, which is
    // when the client re-sends the REQ. The count starts again on every connection.
    val authMarks = filters.keys.associateWith { AtomicInt(authSuccessMark(it)) }
    // 1 once the read has ended or been disposed: nothing more is handed over, onEnd runs once.
    val over = AtomicInt(0)
    // The grace and AUTH waits below, cancelled together when the read is disposed.
    val waits = SupervisorJob(scope.coroutineContext[Job])
    // Per relay, how many times its connection dropped, and how many CLOSEDs in a row were
    // followed by one (a relay going away) since it last reached EOSE.
    val drops = filters.keys.associateWith { MutableStateFlow(0) }
    val goingAwayInARow = filters.keys.associateWith { AtomicInt(0) }
    // Under untilEose: which relays sent their EOSE (counted once each, a re-sent REQ sends
    // another), and how many have yet to.
    val reachedEose = filters.keys.associateWith { AtomicInt(0) }
    val stillServing = AtomicInt(filters.size)
    // Only counts drops: IgnoresRelayMessages keeps it out of the client's per-message dispatch.
    val connections =
        object : RelayConnectionListener, IgnoresRelayMessages {
            override fun onDisconnected(relay: IRelayClient) {
                drops[relay.url]?.update { it + 1 }
            }
        }

    fun finish(cause: Throwable?) {
        if (over.compareAndSet(0, 1)) onEnd(cause)
    }

    fun end(
        relay: NormalizedRelayUrl,
        message: String,
    ) = finish(SubscriptionClosedException(relay, message))

    val listener =
        object : SubscriptionListener {
            // The pool asks this each time it writes the REQ on a connection: that is where a
            // reconnect, or the replay after an AUTH, picks up the resume point.
            override fun filtersForSync(
                relay: NormalizedRelayUrl,
                filters: List<Filter>,
            ): List<Filter>? {
                val mark = authMarks[relay] ?: return null
                if (over.load() != 0) return null
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
                if (relay !in authMarks || over.load() != 0) return
                val taken =
                    try {
                        onEvent.take(event)
                    } catch (e: Throwable) {
                        // The connection's own cancellation must still reach it; the consumer's
                        // failure ends this read, not the connection.
                        currentCoroutineContext().ensureActive()
                        finish(e)
                        return
                    }
                if (taken) resume?.onEvent(relay, event, isLive)
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
                // handed to onEvent, so the read can end here without losing one.
                if (untilEose && reachedEose[relay]?.compareAndSet(0, 1) == true && stillServing.decrementAndFetch() == 0) {
                    finish(null)
                }
            }

            override fun onCannotConnect(
                relay: NormalizedRelayUrl,
                message: String,
                forFilters: List<Filter>?,
            ) {
                if (untilEose && relay in authMarks) finish(RelayUnreachableException(relay, message))
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
                    // again on the next connection, so that CLOSED doesn't end the read. Three in a
                    // row without an EOSE do: the client stops re-sending a REQ refused that often,
                    // and the read would go silent.
                    MachineReadablePrefix.ERROR, null -> {
                        val dropsAtClose = drops[relay]?.value ?: 0
                        scope.launch(waits) {
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
                scope.launch(waits) {
                    if (awaitAuthOutcome(relay, since, settleMs = authSettleMs) == AuthOutcome.AUTHENTICATED) {
                        // The client re-sent the REQ on the AUTH's OK. A later refusal needs a newer AUTH.
                        mark.store(authSuccessMark(relay))
                    } else {
                        end(relay, message)
                    }
                }
            }
        }

    addConnectionListener(connections)
    subscribe(subId, filters, listener)
    return DisposableHandle {
        over.store(1)
        waits.cancel()
        removeConnectionListener(connections)
        unsubscribe(subId)
    }
}

/**
 * [startReq] as a cold [Flow]: each collection runs one read, and each event is handed over with
 * a suspending send, so a collector that falls behind holds up the read (and every other
 * subscription on that relay's connection) instead of losing events. A collector that stops never
 * stalls the connection: the send it was waiting on gives up, and the event is dropped. The flow
 * completes when the read ends, with the read's exception if it failed.
 */
internal fun INostrClient.streamReqAsFlow(
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy?,
    authSettleMs: Long,
    untilEose: Boolean,
): Flow<Event> =
    callbackFlow {
        // Completed when the flow ends: a send waiting for room gives up on it.
        val stopped = CompletableDeferred<Unit>()
        val req =
            startReq(
                scope = this,
                filters = filters,
                resume = resume,
                authSettleMs = authSettleMs,
                untilEose = untilEose,
                onEvent = ReqEventHandler { event -> channel.sendUnlessStopped(event, stopped) },
                onEnd = { cause -> close(cause) },
            )
        awaitClose {
            stopped.complete(Unit)
            req.dispose()
        }
    }

/**
 * How [startReq] hands each event over: it may suspend (the read waits on it, the backpressure),
 * and returns whether it took the event (one it didn't take, from a consumer on its way out, is
 * not counted by the resume policy). A fun interface rather than a suspend lambda type: calling a
 * suspend lambda allocates a new instance of it on every call, and this runs once per event.
 */
internal fun interface ReqEventHandler {
    suspend fun take(event: Event): Boolean
}

/**
 * Sends [value], waiting for room, unless [stopped] completes first: how a [ReqEventHandler] hands
 * an event to its consumer. Returns whether it was sent. Waiting for room is the backpressure;
 * waiting past the consumer's end would stall the connection for good (a channel closed by its
 * collector leaving never wakes a suspended send), so the wait also ends with [stopped].
 *
 * Inline so that the common case, room to spare, costs one `trySend` and no suspension: the wait
 * is a tail call into [awaitRoomUnlessStopped], so the handler calling this needs no state machine
 * of its own.
 */
@Suppress("NOTHING_TO_INLINE")
internal suspend inline fun <T> SendChannel<T>.sendUnlessStopped(
    value: T,
    stopped: Deferred<Unit>,
): Boolean {
    val offered = trySend(value)
    return when {
        offered.isSuccess -> true
        offered.isClosed || stopped.isCompleted -> false
        else -> awaitRoomUnlessStopped(value, stopped)
    }
}

/** [sendUnlessStopped]'s slow path: the channel is full. */
internal suspend fun <T> SendChannel<T>.awaitRoomUnlessStopped(
    value: T,
    stopped: Deferred<Unit>,
): Boolean =
    try {
        select {
            onSend(value) { true }
            stopped.onAwait { false }
        }
    } catch (e: Throwable) {
        // The connection's own cancellation must still reach it; the consumer ending must not.
        currentCoroutineContext().ensureActive()
        if (stopped.isCompleted || isClosedForSend) false else throw e
    }

/** How long after a CLOSED the connection may drop for the CLOSED to count as the relay going away. */
private const val GOING_AWAY_GRACE_MS = 3_000L

/** The pool stops re-sending a REQ its relay refused this many times running. */
private const val MAX_GOING_AWAY_IN_A_ROW = 3
