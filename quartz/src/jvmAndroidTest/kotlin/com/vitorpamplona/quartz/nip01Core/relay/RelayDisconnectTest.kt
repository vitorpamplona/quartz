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
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.NegentropySyncException
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.negentropySync
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.RelayDisconnect
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A relay client says how each of its socket sessions ended, and a negentropy sync that stops
 * says why, typed. Before, `onDisconnected` carried the relay alone and a dropped reconcile read
 * `closed: relay disconnected` whoever closed it and however.
 */
class RelayDisconnectTest {
    private val url = RelayUrlNormalizer.normalize("ws://127.0.0.1:7771/")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val hub = InProcessRelays()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        hub.close()
    }

    /** A socket that opens at once and reports its own close synchronously, as the contract asks. */
    private class FakeSocket(
        val out: WebSocketListener,
    ) : WebSocket {
        override fun needsReconnect() = false

        override fun connect() = out.onOpen(1, false)

        override fun disconnect() = out.onClosed(1000, "client disconnect")

        override fun send(msg: String) = true
    }

    /** Records the end each `onDisconnected` was told about, read inside the callback. */
    private class EndsSeen : RelayConnectionListener {
        val seen = mutableListOf<RelayDisconnect?>()

        override fun onDisconnected(relay: IRelayClient) {
            seen += relay.lastDisconnect
        }
    }

    private var socket: FakeSocket? = null
    private val ends = EndsSeen()
    private val client =
        BasicRelayClient(
            url,
            object : WebsocketBuilder {
                override fun build(
                    url: NormalizedRelayUrl,
                    out: WebSocketListener,
                ): WebSocket = FakeSocket(out).also { socket = it }
            },
            ends,
        )

    @Test
    fun aCloseFrameIsTheRelaysEndWithItsCodeAndTheFramesSent() {
        client.connect()
        client.sendIfConnected(ReqCmd("a", listOf(Filter(kinds = listOf(1)))))
        client.sendIfConnected(ReqCmd("b", listOf(Filter(kinds = listOf(1, 7, 30023), limit = 500))))
        socket!!.out.onClosed(1009, "message too big")

        val end = assertNotNull(ends.seen.single(), "the end is set before onDisconnected runs")
        assertSame(end, client.lastDisconnect)
        assertEquals(RelayDisconnect.Side.RELAY, end.side)
        assertEquals(1009, end.code)
        assertEquals("message too big", end.reason)
        assertNotNull(end.openForMillis, "the socket had opened")
        assertEquals(2, end.sent.count)
        assertEquals("REQ", end.sent.largestLabel)
        assertTrue(end.sent.largestBytes >= end.sent.lastBytes && end.sent.lastBytes > 0)
    }

    @Test
    fun ourOwnCloseIsTheClientsEndWithTheReasonGiven() {
        client.connect()
        client.disconnect("released")

        val end = assertNotNull(client.lastDisconnect)
        assertEquals(RelayDisconnect.Side.CLIENT, end.side, "the 1000 the socket reports for our own close is not the relay's")
        assertNull(end.code)
        assertEquals("released", end.reason)
        assertSame(end, ends.seen.single())
    }

    @Test
    fun aCloseWithNoReasonIsStillOurs() {
        client.connect()
        client.disconnect()

        assertEquals(RelayDisconnect.Side.CLIENT, client.lastDisconnect?.side)
        assertEquals(BasicRelayClient.DISCONNECT_REASON, client.lastDisconnect?.reason)
    }

    @Test
    fun aTransportFailureIsTheTransportsAndTheCleanupCloseAfterItDoesNotReplaceIt() {
        client.connect()
        // The client answers a failure by disconnecting the socket, whose close report must not win.
        socket!!.out.onFailure(EOFException("stream ended"), null, null)

        val end = assertNotNull(client.lastDisconnect)
        assertEquals(RelayDisconnect.Side.TRANSPORT, end.side)
        assertEquals("EOFException", end.errorType)
        assertEquals("stream ended", end.reason)
        assertEquals(end, ends.seen.first())
    }

    /** The in-process relay behind a socket that answers our NEG-OPEN with [onNegOpen] instead of forwarding it. */
    private fun negentropyClient(onNegOpen: (WebSocketListener, String) -> Unit) =
        NostrClient(
            object : WebsocketBuilder {
                override fun build(
                    url: NormalizedRelayUrl,
                    out: WebSocketListener,
                ): WebSocket {
                    val inner = hub.build(url, out)
                    return object : WebSocket by inner {
                        override fun send(msg: String): Boolean {
                            if (!msg.startsWith("[\"NEG-OPEN\"")) return inner.send(msg)
                            onNegOpen(out, msg)
                            return true
                        }
                    }
                }
            },
            scope,
        )

    @Test
    fun aReconcileTheRelayClosesIsDisconnectedWithTheRelaysCode() =
        runBlocking {
            hub.getOrCreate(url).preload(SyntheticEvents.batch(20, kind = 1))
            val nostr = negentropyClient { out, _ -> out.onClosed(1009, "too big") }

            val thrown =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1))) { } }
                }

            assertEquals(NegentropySyncException.Reason.UNAVAILABLE, thrown.reason)
            assertEquals(NegentropySyncException.Failure.DISCONNECTED, thrown.failure)
            val end = assertNotNull(thrown.disconnect, "the sync carries the end of the socket it lost")
            assertEquals(RelayDisconnect.Side.RELAY, end.side)
            assertEquals(1009, end.code)
            assertEquals("NEG-OPEN", end.sent.lastLabel)
            assertEquals(0, thrown.rounds, "dropped before any round")
        }

    @Test
    fun aReconcileTheRelayRefusesIsRefusedWithNoSocketEnd() =
        runBlocking {
            hub.getOrCreate(url).preload(SyntheticEvents.batch(20, kind = 1))
            val nostr =
                negentropyClient { out, msg ->
                    val subId = msg.substringAfter("[\"NEG-OPEN\",\"").substringBefore('"')
                    scope.launch { out.onMessage("[\"NEG-ERR\",\"$subId\",\"blocked: negentropy is off here\"]") }
                }

            val thrown =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1))) { } }
                }

            assertEquals(NegentropySyncException.Failure.REFUSED, thrown.failure)
            assertEquals("blocked: negentropy is off here", thrown.detail)
            assertNull(thrown.disconnect, "the socket stayed up")
        }
}
