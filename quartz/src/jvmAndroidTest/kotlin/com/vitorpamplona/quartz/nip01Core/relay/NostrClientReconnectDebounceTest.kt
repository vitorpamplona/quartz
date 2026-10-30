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
import com.vitorpamplona.quartz.testing.InProcessRelays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * reconnect() requests are debounced. Coalescing must keep the strongest
 * request: a full reconnect (how hosts answer a network or Tor change)
 * followed within the debounce window by the routine onlyIfChanged one that
 * every subscribe/publish issues must still tear sockets down and redial.
 */
class NostrClientReconnectDebounceTest {
    private val hub = InProcessRelays()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        hub.close()
    }

    private class CountingBuilder(
        private val delegate: WebsocketBuilder,
    ) : WebsocketBuilder {
        val built = AtomicInteger(0)

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            built.incrementAndGet()
            return delegate.build(url, out)
        }
    }

    /**
     * A socket that dials and never answers, the state every relay of a fresh subscription is in,
     * and costs what OkHttp's dial costs the caller: a thread start each.
     */
    private class SilentBuilder : WebsocketBuilder {
        val built = AtomicInteger(0)

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            built.incrementAndGet()
            Thread.sleep(10)
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() {}

                override fun disconnect() {}

                override fun send(msg: String) = true
            }
        }
    }

    /**
     * A client starts with nothing to reconnect. Its first debounce tick used to be a full
     * reconnect, which tore down and redialed every socket a subscription made in the client's
     * first 200ms: a subscription over a whole relay fleet right after start dialed much of it
     * twice. 50 dials at 10ms each keep the subscribe call past that tick.
     */
    @Test
    fun aSubscriptionMadeAtStartIsDialedOnce() =
        runBlocking {
            val builder = SilentBuilder()
            val client = NostrClient(builder, scope)
            val relays = (1..50).map { RelayUrlNormalizer.normalize("wss://relay$it.example/") }
            client.subscribe("start", relays.associateWith { listOf(Filter(kinds = listOf(1))) }, null)
            delay(1_000)

            assertEquals(relays.size, builder.built.get(), "each relay dialed once")
            client.disconnect()
        }

    @Test
    fun aFullReconnectSurvivesARoutineOneInsideTheDebounceWindow() =
        runBlocking {
            val builder = CountingBuilder(hub)
            val client = NostrClient(builder, scope)
            client.keepConnected(InProcessRelays.DEFAULT_URL)
            withTimeout(5_000) { while (InProcessRelays.DEFAULT_URL !in client.connectedRelaysFlow().value) delay(20) }
            delay(500)
            val builtBefore = builder.built.get()

            client.reconnect(onlyIfChanged = false)
            client.reconnect(onlyIfChanged = true)
            delay(1_000)

            assertEquals(builtBefore + 1, builder.built.get(), "the full reconnect must redial the connected relay")
            client.disconnect()
        }
}
