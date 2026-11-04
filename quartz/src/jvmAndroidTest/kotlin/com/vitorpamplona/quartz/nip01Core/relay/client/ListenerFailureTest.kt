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
package com.vitorpamplona.quartz.nip01Core.relay.client

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetch
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.deliverMessage
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a relay client's listeners throw never ends delivery. Quartz's own sockets catch it in
 * their delivery loop, so the relay client hands them its unguarded path (no continuation per
 * message); any other socket gets [WebSocketListener.onMessage], which catches it itself, so a
 * socket that doesn't catch (a custom one, a Swift one over QuartzKit) keeps working.
 *
 * Also: the EVENT fast path, taken while no connection listener reads messages, gives way to
 * the full path the moment one is added, and comes back once it is removed.
 */
class ListenerFailureTest {
    private val signer = NostrSignerSync()
    private val kind1 = Filter(kinds = listOf(1))
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val hub = InProcessRelays()
    private val relay = InProcessRelays.DEFAULT_URL

    @AfterTest
    fun tearDown() {
        scope.cancel()
        hub.close()
    }

    private fun notes(count: Int): List<Event> = (1..count).map { signer.sign<Event>(1_000L + it, 1, emptyArray(), "note $it") }

    /** Reads every message and throws on each EVENT. */
    private class ThrowsOnEvents : RelayConnectionListener {
        val events = AtomicInteger(0)

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msgStr: String,
            msg: Message,
        ) {
            if (msg is EventMessage) {
                events.incrementAndGet()
                error("listener failed on ${msg.event.id}")
            }
        }
    }

    /** Counts the EVENTs it reads. */
    private class CountsEvents : RelayConnectionListener {
        val events = AtomicInteger(0)

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msgStr: String,
            msg: Message,
        ) {
            if (msg is EventMessage) events.incrementAndGet()
        }
    }

    /**
     * A relay whose socket delivers from a coroutine that does not catch: a throw out of
     * [WebSocketListener.onMessage] would end that coroutine, and every frame after it would be
     * lost. It answers each REQ with [events] and an EOSE.
     */
    private class SocketThatDoesNotCatch(
        private val events: List<Event>,
    ) : WebsocketBuilder {
        val escaped = CopyOnWriteArrayList<Throwable>()
        private val handler = CoroutineExceptionHandler { _, t -> escaped += t }
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket =
            object : WebSocket {
                private var open = false

                override fun needsReconnect() = !open

                override fun connect() {
                    open = true
                    out.onOpen(0, false)
                }

                override fun disconnect() {
                    if (!open) return
                    open = false
                    out.onClosed(1000, "bye")
                }

                override fun send(msg: String): Boolean {
                    val subId = REQ.find(msg)?.groupValues?.get(1) ?: return true
                    scope.launch {
                        for (e in events) out.onMessage("[\"EVENT\",\"$subId\",${e.toJson()}]")
                        out.onMessage("[\"EOSE\",\"$subId\"]")
                    }
                    return true
                }
            }

        fun close() = scope.cancel()

        companion object {
            private val REQ = Regex("^\\[\"REQ\",\"([^\"]+)\"")
        }
    }

    @Test
    fun aThrowingListenerDoesNotEndDeliveryOverQuartzsOwnSocket() =
        runBlocking<Unit> {
            val stored = notes(20)
            hub.getOrCreate(relay).preload(stored)
            val client = NostrClient(hub, scope)
            val failing = ThrowsOnEvents()
            client.addConnectionListener(failing)
            try {
                val delivered = withTimeout(5_000) { client.fetch(relay, kind1) { } }
                assertEquals(stored.size, delivered, "every event after a throw still reached the fetch")
                assertEquals(stored.size, failing.events.get(), "the listener saw each of them")
            } finally {
                client.disconnect()
            }
        }

    @Test
    fun aThrowingListenerDoesNotEndDeliveryOverASocketThatDoesNotCatch() =
        runBlocking<Unit> {
            val stored = notes(20)
            val sockets = SocketThatDoesNotCatch(stored)
            val client = NostrClient(sockets, scope)
            val failing = ThrowsOnEvents()
            client.addConnectionListener(failing)
            try {
                val delivered = withTimeout(5_000) { client.fetch(relay, kind1) { } }
                assertEquals(stored.size, delivered, "every event after a throw still reached the fetch")
                assertEquals(stored.size, failing.events.get(), "the listener saw each of them")
                assertTrue(sockets.escaped.isEmpty(), "nothing reached the socket: ${sockets.escaped}")
            } finally {
                client.disconnect()
                sockets.close()
            }
        }

    @Test
    fun onlyQuartzsSocketsGetTheUnguardedPath() {
        var socketListener: WebSocketListener? = null
        val builder =
            object : WebsocketBuilder {
                override fun build(
                    url: NormalizedRelayUrl,
                    out: WebSocketListener,
                ): WebSocket {
                    socketListener = out
                    return object : WebSocket {
                        override fun needsReconnect() = false

                        override fun connect() = out.onOpen(0, false)

                        override fun disconnect() = out.onClosed(1000, "bye")

                        override fun send(msg: String) = true
                    }
                }
            }
        val failing = ThrowsOnEvents()
        val client = BasicRelayClient(relay, builder, failing)
        client.connect()
        val out = checkNotNull(socketListener)
        val frame = "[\"EVENT\",\"sub\",${notes(1).single().toJson()}]"

        runBlocking { out.onMessage(frame) }
        assertEquals(1, failing.events.get(), "onMessage reached the listener and caught its throw")

        val thrown = assertFailsWith<IllegalStateException> { runBlocking { out.deliverMessage(frame) } }
        assertTrue(thrown.message!!.startsWith("listener failed on"))
        assertEquals(2, failing.events.get())
    }

    @Test
    fun aListenerAddedMidStreamReadsTheEventsAfterIt() =
        runBlocking<Unit> {
            val stored = notes(20)
            hub.getOrCreate(relay).preload(stored)
            val client = NostrClient(hub, scope)
            val reader = CountsEvents()
            try {
                // fetch's own listener ignores relay messages: the first EVENT takes the fast path.
                var seen = 0
                val delivered =
                    withTimeout(5_000) {
                        client.fetch(relay, kind1) { if (++seen == 5) client.addConnectionListener(reader) }
                    }
                assertEquals(stored.size, delivered)
                assertEquals(stored.size - 5, reader.events.get(), "every EVENT after the add, none before")

                client.removeConnectionListener(reader)
                assertEquals(stored.size, withTimeout(5_000) { client.fetch(relay, kind1) { } })
                assertEquals(stored.size - 5, reader.events.get(), "nothing once removed")
            } finally {
                client.disconnect()
            }
        }
}
