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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.RawEvent

/**
 * A [SessionBackend] whose REQs keep their live tail as a registration instead of a parked
 * coroutine. [com.vitorpamplona.quartz.nip01Core.relay.server.RelaySession] uses
 * [openSubscription] when its backend implements this, and [SessionBackend.queryRaw] /
 * [SessionBackend.query] otherwise.
 *
 * A separate interface on purpose: a wrapper written as `SessionBackend by inner` that
 * overrides `queryRaw` to gate or time reads stays on that path, instead of having this
 * method forwarded past its override. To wrap this path, implement it and decorate both the
 * [LiveListener] (live delivery) and the returned [LiveSubscription] (the replay).
 */
interface LiveSubscriptionBackend : SessionBackend {
    /**
     * Registers a REQ's [filters] for live delivery to [onLive] and returns its handle. From
     * the moment this returns, every event the backend accepts that matches reaches [onLive],
     * on [RequestContext.liveExecutor] when [ctx] has one and otherwise on the thread that
     * accepted it, in acceptance order. Call [LiveSubscription.replayRaw] or
     * [LiveSubscription.replay] once to stream the stored events; an event both stored and
     * live is delivered once, by one path or the other. Non-suspending, so the caller holds the
     * handle before anything can target it.
     */
    fun openSubscription(
        ctx: RequestContext,
        filters: List<Filter>,
        onLive: LiveListener,
    ): LiveSubscription
}

/** Receives a [LiveSubscription]'s live events. Must be cheap and must not block. */
fun interface LiveListener {
    /**
     * One live event, with its wire JSON [body]: on a storage-backed relay, the same [String]
     * instance for every subscription the event reaches.
     */
    fun onLive(
        event: Event,
        body: String,
    )
}

/** A REQ registered by [LiveSubscriptionBackend.openSubscription]. */
interface LiveSubscription {
    /**
     * Streams the stored events matching the subscription to [onEachStored] as [RawEvent]s,
     * then calls [onEose]. Returns after [onEose]; live delivery carries on without a
     * coroutine. Unregisters the subscription if it throws or is cancelled.
     */
    suspend fun replayRaw(
        onEachStored: (RawEvent) -> Unit,
        onEose: () -> Unit,
    )

    /** [replayRaw] with each stored event materialized, for a policy that screens events. */
    suspend fun replay(
        onEach: (Event) -> Unit,
        onEose: () -> Unit,
    )

    /**
     * Unregisters the subscription: CLOSE, a replaced sub id or a disconnect. Idempotent and
     * callable from any thread. No live delivery starts once it returns, though one already
     * running on another thread may finish.
     */
    fun cancel()
}
