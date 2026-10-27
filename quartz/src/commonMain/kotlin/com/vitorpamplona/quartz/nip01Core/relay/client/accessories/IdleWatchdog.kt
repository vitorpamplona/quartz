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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/**
 * Monotonic "last activity" marker for the idle watchdogs shared by the accessory
 * fetch/sync loops. [bump] on every sign of life from the relay; [elapsedMs] reports
 * the silence since the last bump (or since construction, before the first bump) —
 * and reads 0 while any [holding] block runs: time the CALLER spends handling what
 * the relay sent is not the relay's silence.
 *
 * This is the timeout convention for every accessory in this package: an
 * `idleTimeoutMs` is an **idle window measured from the relay's most recent
 * progress**, not a wall-clock deadline — an actively streaming relay is never cut
 * off mid-delivery, only one that goes silent — and never counting the caller's own
 * handler or backpressure. The name is the contract: a parameter here is called
 * `idleTimeoutMs` precisely because it is not a deadline.
 *
 * [bump] is on the per-event hot path (a connection listener may bump for every
 * message the relay sends — millions during a large download), so it must not
 * allocate: a single [start] mark is taken once (unboxed field) and each bump only
 * writes a `Long` of nanos-since-start into a `@Volatile` field. Reader threads
 * write, the driver coroutine reads — visibility is all we need, so a plain volatile
 * Long beats boxing a `ValueTimeMark` into an `AtomicReference` on every event.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class IdleClock {
    private val start = TimeSource.Monotonic.markNow()

    @Volatile
    private var lastNanos = 0L

    /** [holding] blocks running right now; the clock stands still while any does. */
    @PublishedApi
    internal val holds = AtomicInt(0)

    fun bump() {
        lastNanos = start.elapsedNow().inWholeNanoseconds
    }

    /**
     * Run [block] — the caller's handling of something the relay sent, or a wait
     * on the caller's own backpressure — with the clock stopped, and count it as
     * activity when it returns.
     *
     * The window measures the RELAY's silence, and time spent in the caller's
     * handler is not that: a handler that suspends on its own backpressure (a
     * mirror's full ingest queue) for longer than the window used to have the
     * page ended as idle under it, so the walk re-asked from its cursor and
     * downloaded again what the relay had already sent; and a reconcile whose
     * hand-off to a full download pipeline outlasted the window failed as a
     * silent relay. Nothing is lost by not timing it: a walk cannot move on while
     * a handler holds the event in flight anyway, so the only thing the timeout
     * could do there was misreport a busy consumer as a silent relay. A caller
     * that wants a stuck handler bounded bounds it itself (`withTimeout`).
     *
     * COUNTED, so blocks may overlap — a session clock is shared by concurrent
     * workers — and the clock runs again only when the last one ends. The release
     * is ordered for the driver thread: the bump is written before the count
     * drops, so a reader that sees the clock running again also sees the mark.
     */
    inline fun <T> holding(block: () -> T): T {
        holds.incrementAndFetch()
        try {
            return block()
        } finally {
            bump()
            holds.decrementAndFetch()
        }
    }

    fun elapsedMs(): Long = if (holds.load() > 0) 0 else (start.elapsedNow().inWholeNanoseconds - lastNanos) / 1_000_000
}

/**
 * Receives the next item, giving up (returning `null`) only after [idleMs] elapse with
 * no activity on [clock]. Because [clock] can be bumped by *any* relay message — not
 * just items on this channel — unrelated progress (e.g. download events arriving during
 * a reconcile wait) keeps pushing the deadline out. [idleMs] `<= 0` disables the
 * watchdog: it waits until an item arrives (a disconnect is delivered as an item, so
 * a dead socket still unblocks it).
 */
internal suspend fun <T> Channel<T>.receiveWithinIdle(
    clock: IdleClock,
    idleMs: Long,
): T? {
    if (idleMs <= 0) return receive()
    // An item already queued is activity, and taking it arms no timer: a busy stream's
    // replies are nearly always waiting, and a timeout per reply was most of its garbage.
    tryReceive().getOrNull()?.let { return it }
    while (true) {
        val remaining = idleMs - clock.elapsedMs()
        if (remaining <= 0) return null
        val item = withTimeoutOrNull(remaining) { receive() }
        if (item != null) return item
        // Timed out with nothing on this channel. If other activity bumped the clock
        // meanwhile, the next `remaining` is positive and we wait again; otherwise it
        // is <= 0 on the next iteration and we give up.
    }
}
