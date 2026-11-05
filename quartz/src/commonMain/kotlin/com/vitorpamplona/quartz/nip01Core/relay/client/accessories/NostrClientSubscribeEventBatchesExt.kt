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
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select

/**
 * The live tail of [relay] for [filters], in batches, with its resume point counting only what the
 * collector has processed. See the multi-relay form.
 */
fun INostrClient.subscribeEventBatches(
    relay: NormalizedRelayUrl,
    filters: List<Filter>,
    resume: ResumePolicy? = CaughtUpResume(),
    maxBatch: Int = 500,
    maxPending: Int = 10_000,
    authSettleMs: Long = 120_000,
): Flow<List<Event>> = subscribeEventBatches(mapOf(relay to filters), resume, maxBatch, maxPending, authSettleMs)

/**
 * [subscribeEvents], for a consumer that writes in batches and saves where it got to (an indexer,
 * a mirror, a graph builder): the same live tail, stored then live, the same reconnects and the
 * same CLOSED rules, emitted as lists of at most [maxBatch] events. A batch is whatever has
 * arrived while the previous one was processed, so a quiet tail emits batches of one and a busy
 * one fills them.
 *
 * - **The resume point is what was processed.** [resume] hears an event, and an EOSE, only once
 *   the batch holding it has been processed: once `emit` returns, which in a [Flow] is once the
 *   collector's block returns for it. A reconnect resumes from there, and so does a restart that
 *   saved [CaughtUpResume.caughtUpAt] (or watched [CaughtUpResume.caughtUpAtFlow]): an event the
 *   relay sent that was still waiting in this buffer when the process died is asked for again.
 *   With [subscribeEvents] and a buffer of its own, the point counts what reached the buffer. A
 *   `buffer()` after this flow undoes it the same way: `emit` then returns before the work is done.
 * - **Stored events count at EOSE**, as [CaughtUpResume] has them: the EOSE travels behind the
 *   stored events in the same queue, so it reaches [resume] after the batches before it.
 * - **Backpressure.** At most [maxPending] events wait for the collector; past that the relay's
 *   delivery waits, holding up every subscription on that connection, as [subscribeEvents]'s
 *   suspending send does. A collector that stops never stalls the connection: the wait gives up.
 * - **Failures.** An exception from the collector ends the flow with it. A CLOSED the client
 *   doesn't recover from ends it with [SubscriptionClosedException] once the batches already
 *   received have been emitted.
 */
fun INostrClient.subscribeEventBatches(
    filters: Map<NormalizedRelayUrl, List<Filter>>,
    resume: ResumePolicy? = CaughtUpResume(),
    maxBatch: Int = 500,
    maxPending: Int = 10_000,
    authSettleMs: Long = 120_000,
): Flow<List<Event>> {
    require(maxBatch > 0) { "maxBatch must be positive: $maxBatch" }
    require(maxPending > 0) { "maxPending must be positive: $maxPending" }
    return flow {
        // Events and EOSEs, in the order the relays delivered them. Unbounded only in type:
        // [room] keeps events to maxPending, and EOSEs are one per REQ written.
        val queue = Channel<Delivered>(Channel.UNLIMITED)
        // One token per event waiting; a full channel is the backpressure.
        val room = Channel<Unit>(maxPending)
        // Completed when the collector is gone: a delivery waiting for room gives up on it.
        val stopped = CompletableDeferred<Unit>()
        val deferred = ProcessedResume(resume, queue)
        coroutineScope {
            val req =
                startReq(
                    scope = this,
                    filters = filters,
                    resume = deferred,
                    authSettleMs = authSettleMs,
                    untilEose = false,
                    onEvent =
                        ReqEventHandler { _ ->
                            if (room.trySend(Unit).isSuccess) {
                                true
                            } else {
                                try {
                                    select<Boolean> {
                                        room.onSend(Unit) { true }
                                        stopped.onAwait { false }
                                    }
                                } catch (e: Throwable) {
                                    // The connection's own cancellation must still reach it; this
                                    // flow ending must not.
                                    currentCoroutineContext().ensureActive()
                                    if (stopped.isCompleted) false else throw e
                                }
                            }
                        },
                    onEnd = { cause -> queue.close(cause) },
                )
            try {
                val pending = ArrayList<Delivered>()
                val events = ArrayList<Event>(maxBatch)
                while (true) {
                    val first = queue.receiveCatching()
                    if (first.isClosed) {
                        first.exceptionOrNull()?.let { throw it }
                        break
                    }
                    pending.add(first.getOrThrow())
                    var count = if (pending[0] is Delivered.Received) 1 else 0
                    while (count < maxBatch) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        pending.add(next)
                        if (next is Delivered.Received) count++
                    }
                    for (d in pending) if (d is Delivered.Received) events.add(d.event)
                    if (events.isNotEmpty()) emit(events.toList())
                    // Processed: now the policy hears them, in the order they came.
                    for (d in pending) {
                        when (d) {
                            is Delivered.Received -> {
                                resume?.onEvent(d.relay, d.event, d.isLive)
                                room.tryReceive()
                            }
                            is Delivered.Eose -> resume?.onEose(d.relay, d.filters)
                        }
                    }
                    pending.clear()
                    events.clear()
                }
            } finally {
                stopped.complete(Unit)
                req.dispose()
                queue.close()
            }
        }
    }
}

/** What a relay delivered, waiting to be processed. */
private sealed interface Delivered {
    class Received(
        val relay: NormalizedRelayUrl,
        val event: Event,
        val isLive: Boolean,
    ) : Delivered

    class Eose(
        val relay: NormalizedRelayUrl,
        val filters: List<Filter>?,
    ) : Delivered
}

/**
 * Stands in for the caller's policy while the read runs: what it hears is queued, to be passed on
 * once processed; where to resume is the caller's policy's own answer, which counts only what was
 * processed.
 */
private class ProcessedResume(
    private val inner: ResumePolicy?,
    private val queue: Channel<Delivered>,
) : ResumePolicy {
    override fun onEvent(
        relay: NormalizedRelayUrl,
        event: Event,
        isLive: Boolean,
    ) {
        queue.trySend(Delivered.Received(relay, event, isLive))
    }

    override fun onEose(
        relay: NormalizedRelayUrl,
        filters: List<Filter>?,
    ) {
        queue.trySend(Delivered.Eose(relay, filters))
    }

    override fun resumeFrom(
        relay: NormalizedRelayUrl,
        filters: List<Filter>,
    ): Long? = inner?.resumeFrom(relay, filters)
}
