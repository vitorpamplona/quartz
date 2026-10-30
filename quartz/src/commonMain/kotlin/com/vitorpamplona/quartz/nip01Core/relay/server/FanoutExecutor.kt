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
package com.vitorpamplona.quartz.nip01Core.relay.server

/**
 * The thread that delivers a sink's live frames: on Netty, the channel's event loop. A
 * [SessionSink] declares one through [SessionSink.liveExecutor].
 *
 * The storage-backed relay (`LiveEventStore`) keeps one partition of its live index per
 * executor. When it accepts an event it hands it, once, to each executor that holds a
 * subscription the event may match; that task matches the executor's own subscriptions and
 * calls their sinks on the executor's thread. The ingest thread never walks the subscriptions
 * themselves, and a sink running on its executor can write without a cross-thread queue.
 *
 * The instance is the partition key: give every sink served by one thread the same instance
 * (one per event loop), not a new wrapper per connection.
 *
 * The executor owns the queue between the ingest and its thread: each accepted event it may
 * deliver adds one task, holding the event and a snapshot of its subscriptions, so an executor
 * that falls behind grows that queue at the ingest rate. Bound it if that matters; a task it
 * refuses is logged and that event is not delivered to its subscriptions.
 */
interface FanoutExecutor {
    /**
     * Runs [task] on this executor's thread, after every task submitted before it and one at a
     * time. Called from any thread, including this executor's own; it must queue [task] even
     * then, never run it inline, and never block.
     */
    fun execute(task: () -> Unit)
}
