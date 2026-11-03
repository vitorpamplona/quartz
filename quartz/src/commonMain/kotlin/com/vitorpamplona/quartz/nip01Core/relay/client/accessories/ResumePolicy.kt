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

import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.concurrent.Volatile

/**
 * Where a [subscribeEvents] subscription resumes after its connection drops. The subscription
 * only reports and asks; the policy decides.
 *
 * Both calls come from the client's own threads: [onCaughtUp] from the connection's delivery
 * coroutine, which every subscription on that relay shares, and [resumeFrom] from the one writing
 * the REQ. Keep both cheap and non-blocking; save state elsewhere, on your own schedule.
 */
interface ResumePolicy {
    /** At EOSE and at each live event: the subscription is caught up with the relay as of now. */
    fun onCaughtUp()

    /**
     * Each time the client writes the REQ on a connection (a reconnect, the replay after an AUTH,
     * or the first connection if the relay wasn't connected yet): the `since` to ask from, or null
     * to send the filters as given. A filter is never moved earlier than its own `since`.
     */
    fun resumeFrom(): Long?
}

/**
 * The default [ResumePolicy]: the last moment the subscription was caught up, minus
 * [overlapSeconds]. A connection that drops for an hour asks again from just before the drop.
 *
 * `since` filters on `created_at`, so an event the relay stores while the connection is down but
 * created before the resume point (a relay mirroring others does this all the time) is not sent
 * again, nor is anything past the relay's cap on stored results for one REQ. The overlap narrows
 * the first; if every event matters, reconcile the gap too (`negentropySync`).
 *
 * To resume after a restart, read [resumeFrom] on your own schedule, persist it, and set it as the
 * filters' `since` next time. It covers what was handed to the flow, so it can run ahead of what a
 * lagging collector has processed by up to its buffer; the overlap covers a lag shorter than it.
 */
class CaughtUpResume(
    val overlapSeconds: Long = 60,
    private val nowInSeconds: () -> Long = TimeUtils::now,
) : ResumePolicy {
    /** Epoch seconds the subscription was last caught up; 0 = not yet. */
    @Volatile
    var caughtUpAt: Long = 0L
        private set

    override fun onCaughtUp() {
        caughtUpAt = nowInSeconds()
    }

    override fun resumeFrom(): Long? = caughtUpAt.takeIf { it > 0L }?.minus(overlapSeconds)
}
