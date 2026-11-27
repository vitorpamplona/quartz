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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An idle [NostrClient] schedules no timers. Issue #166: the relay-set watcher sampled its
 * upstream every 300 ms, so every client woke the scheduler ~3 times a second for its whole
 * life, unchanged relay sets and [NostrClient.disconnect] notwithstanding.
 */
@OptIn(InternalCoroutinesApi::class)
class NostrClientIdleTimersTest {
    /** Runs on the test scheduler's virtual clock and counts every timer scheduled on it. */
    private class TimerCountingDispatcher(
        private val delegate: TestDispatcher,
    ) : CoroutineDispatcher(),
        Delay {
        var timers = 0

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = delegate.dispatch(context, block)

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>,
        ) {
            timers++
            delegate.scheduleResumeAfterDelay(timeMillis, continuation)
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle {
            timers++
            return delegate.invokeOnTimeout(timeMillis, block, context)
        }
    }

    /** A socket that dials and never answers, counting the dials. */
    private class SilentBuilder : WebsocketBuilder {
        var built = 0

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            built++
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() {}

                override fun disconnect() {}

                override fun send(msg: String) = true
            }
        }
    }

    private val builder = SilentBuilder()

    private fun withClient(block: (NostrClient, TestCoroutineScheduler, TimerCountingDispatcher) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = TimerCountingDispatcher(StandardTestDispatcher(scheduler))
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val client = NostrClient(builder, scope)
        try {
            block(client, scheduler, dispatcher)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    /** Timers scheduled over ten idle seconds of virtual time, after the client has settled. */
    private fun timersWhileIdle(
        scheduler: TestCoroutineScheduler,
        dispatcher: TimerCountingDispatcher,
    ): Int {
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        dispatcher.timers = 0
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        return dispatcher.timers
    }

    @Test
    fun anIdleConnectedClientSchedulesNoTimers() =
        withClient { client, scheduler, dispatcher ->
            val relay = RelayUrlNormalizer.normalize("wss://relay.example/")
            client.subscribe("idle", mapOf(relay to listOf(Filter(kinds = listOf(1)))), null)

            assertEquals(0, timersWhileIdle(scheduler, dispatcher), "timers scheduled while the relay sets stood still")
        }

    @Test
    fun aDisconnectedClientSchedulesNoTimers() =
        withClient { client, scheduler, dispatcher ->
            client.disconnect()

            assertEquals(0, timersWhileIdle(scheduler, dispatcher), "timers scheduled after disconnect()")
        }

    @Test
    fun aRelaySetChangeStillReachesThePool() =
        withClient { client, scheduler, _ ->
            val relay = RelayUrlNormalizer.normalize("wss://relay.example/")
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()

            client.subscribe("joins", mapOf(relay to listOf(Filter(kinds = listOf(1)))), null)
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            assertTrue(relay in client.availableRelaysFlow().value, "the subscription's relay joins the pool")

            client.unsubscribe("joins")
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            assertTrue(relay !in client.availableRelaysFlow().value, "the relay leaves once nothing wants it")
        }

    /**
     * A change waits out the coalescing window before it reaches the pool, as it did under the
     * sample: a relay unsubscribed and resubscribed inside it keeps its socket.
     */
    @Test
    fun aRelayWantedAgainInsideTheWindowIsNotRedialed() =
        withClient { client, scheduler, _ ->
            val relay = RelayUrlNormalizer.normalize("wss://relay.example/")
            val filters = mapOf(relay to listOf(Filter(kinds = listOf(1))))
            client.subscribe("first", filters, null)
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            assertEquals(1, builder.built, "the subscription dials its relay")

            client.unsubscribe("first")
            scheduler.advanceTimeBy(50)
            scheduler.runCurrent()
            client.subscribe("second", filters, null)
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()

            assertEquals(1, builder.built, "the relay kept its socket")
        }
}
