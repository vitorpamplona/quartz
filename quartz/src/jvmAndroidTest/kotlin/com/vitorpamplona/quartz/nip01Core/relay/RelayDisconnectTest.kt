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
import com.vitorpamplona.quartz.nip77Negentropy.INegentropyListener
import com.vitorpamplona.quartz.nip77Negentropy.NegentropyManager
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.quartz.testing.InProcessRelays
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
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

    /**
     * A socket that opens at once and reports its own close synchronously, as the contract asks.
     * With [peerEnded] set, the relay has already ended it and [disconnect] reports that end.
     */
    private class FakeSocket(
        val out: WebSocketListener,
    ) : WebSocket {
        var peerEnded: Pair<Int, String>? = null

        override fun needsReconnect() = false

        override fun connect() = out.onOpen(1, false)

        override fun disconnect() {
            val peer = peerEnded
            if (peer != null) out.onClosed(peer.first, peer.second) else out.onClosed(1000, "client disconnect")
        }

        override fun send(msg: String) = true

        override fun endedByPeer() = peerEnded != null
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
        assertTrue(end.sent.lastChars > 0)
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

    /**
     * The in-process [relays] behind a socket that answers a frame starting with [verb] with [onFrame]
     * instead of forwarding it. [canDial] gates the dial, as a transport that is not ready does.
     */
    private fun negentropyClient(
        relays: InProcessRelays = hub,
        verb: String = "NEG-OPEN",
        canDial: () -> Boolean = { true },
        onFrame: (WebSocketListener, String) -> Unit,
    ) = NostrClient(
        object : WebsocketBuilder {
            override fun build(
                url: NormalizedRelayUrl,
                out: WebSocketListener,
            ): WebSocket {
                val inner = relays.build(url, out)
                return object : WebSocket by inner {
                    override fun send(msg: String): Boolean {
                        if (!msg.startsWith("[\"$verb\"")) return inner.send(msg)
                        onFrame(out, msg)
                        return true
                    }
                }
            }

            override fun canConnect(url: NormalizedRelayUrl) = canDial()
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

    @Test
    fun aRelayCloseStillDrainingStaysTheRelaysWhenWeDisconnect() {
        client.connect()
        // The relay closed the session; its last messages are still being delivered when we let go.
        socket!!.peerEnded = 1009 to "message too big"
        client.disconnect("released")

        val end = assertNotNull(client.lastDisconnect)
        assertEquals(RelayDisconnect.Side.RELAY, end.side)
        assertEquals(1009, end.code)
    }

    @Test
    fun aFailureBeforeTheSocketIsHandedBackIsTheTransportsNotOurs() {
        val early =
            BasicRelayClient(
                url,
                object : WebsocketBuilder {
                    override fun build(
                        url: NormalizedRelayUrl,
                        out: WebSocketListener,
                    ): WebSocket {
                        // A transport that fails inside the dial, before the client holds the socket.
                        out.onFailure(SocketException("network is unreachable"), null, null)
                        return FakeSocket(out)
                    }
                },
                ends,
            )
        early.connect()

        val end = assertNotNull(early.lastDisconnect)
        assertEquals(RelayDisconnect.Side.TRANSPORT, end.side)
        assertEquals("SocketException", end.errorType)
    }

    @Test
    fun theLargestFrameIsFoundWithoutScanningEveryFrame() {
        client.connect()
        val ascii = ReqCmd("a", listOf(Filter(kinds = listOf(1), search = "a".repeat(1_000))))
        val small = ReqCmd("b", listOf(Filter(kinds = listOf(1))))
        // Fewer chars than the first, more bytes: only a byte count can rank it.
        val wide = ReqCmd("c", listOf(Filter(kinds = listOf(1), search = "\u00e9".repeat(700))))
        listOf(ascii, small, wide).forEach { client.sendIfConnected(it) }

        val sent = assertNotNull(client.sentFrames, "a live session reports what it has sent")
        assertEquals(3, sent.count)
        assertEquals(wide.toJson().encodeToByteArray().size, sent.largestBytes)
        assertEquals(wide.toJson().length, sent.lastChars)

        client.disconnect()
        assertNull(client.sentFrames, "no session, nothing in flight")
        assertEquals(3, client.lastDisconnect?.sent?.count, "the end keeps the session's frames")
    }

    @Test
    fun aDropAfterSomeRoundsSaysHowManyCompleted() =
        runBlocking {
            // A small frame limit makes the relay answer in several rounds, each one acked by a NEG-MSG of ours.
            val chunked = InProcessRelays(negentropySettings = NegentropySettings(frameSizeLimit = 4_096))
            try {
                chunked.getOrCreate(url).preload(SyntheticEvents.batch(3_000, kind = 1))
                val nostr = negentropyClient(chunked, verb = "NEG-MSG") { out, _ -> out.onClosed(1001, "going away") }

                val thrown =
                    assertFailsWith<NegentropySyncException> {
                        withTimeout(30_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1))) { } }
                    }

                assertEquals(NegentropySyncException.Failure.DISCONNECTED, thrown.failure)
                assertEquals(1, thrown.rounds, "the relay's first answer was processed; our ack was what dropped")
                assertEquals("NEG-MSG", thrown.disconnect?.sent?.lastLabel)
            } finally {
                chunked.close()
            }
        }

    @Test
    fun aSilentRelayStillShowsWhatWeSent() =
        runBlocking {
            hub.getOrCreate(url).preload(SyntheticEvents.batch(20, kind = 1))
            // Swallows the NEG-OPEN: the relay never answers it.
            val nostr = negentropyClient { _, _ -> }

            val thrown =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1)), idleTimeoutMs = 500) { } }
                }

            assertEquals(NegentropySyncException.Failure.SILENT, thrown.failure)
            assertNull(thrown.disconnect)
            assertEquals("NEG-OPEN", thrown.sent?.lastLabel, "the live socket's frames ride on a failure that left it up")
        }

    @Test
    fun aConnectTimeoutCarriesTheFailedDialButNotAnOlderEnd() =
        runBlocking {
            var dial = true
            val nostr = negentropyClient(canDial = { dial }) { out, _ -> out.onClosed(1009, "too big") }
            val first =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1))) { } }
                }
            assertEquals(1009, first.disconnect?.code)

            // No dial happens now, so no end belongs to this attempt, however recent the last one is.
            dial = false
            val second =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1)), idleTimeoutMs = 300) { } }
                }
            assertEquals(NegentropySyncException.Failure.CONNECT_TIMEOUT, second.failure)
            assertNull(second.disconnect, "the 1009 belongs to the earlier attempt")
        }

    @Test
    fun aConnectTimeoutWhoseDialFailedCarriesThatFailure() =
        runBlocking {
            val nostr =
                NostrClient(
                    object : WebsocketBuilder {
                        override fun build(
                            url: NormalizedRelayUrl,
                            out: WebSocketListener,
                        ): WebSocket =
                            object : WebSocket {
                                override fun needsReconnect() = true

                                override fun connect() = out.onFailure(ConnectException("Connection refused"), null, null)

                                override fun disconnect() = Unit

                                override fun send(msg: String) = false
                            }
                    },
                    scope,
                )

            val thrown =
                assertFailsWith<NegentropySyncException> {
                    withTimeout(20_000) { nostr.negentropySync(relay = url, filter = Filter(kinds = listOf(1)), idleTimeoutMs = 300) { } }
                }

            assertEquals(NegentropySyncException.Failure.CONNECT_TIMEOUT, thrown.failure)
            assertEquals(RelayDisconnect.Side.TRANSPORT, thrown.disconnect?.side)
            assertEquals("ConnectException", thrown.disconnect?.errorType)
        }

    @Test
    fun aRelayThePoolDropsIsClosedWithThePoolsReason() =
        runBlocking {
            val nostr = NostrClient(hub, scope)
            val seen = EndsSeen()
            nostr.addConnectionListener(seen)
            nostr.keepConnected(url)
            withTimeout(10_000) { while (url !in nostr.connectedRelaysFlow().value) delay(10) }

            nostr.releaseConnection(url)
            withTimeout(10_000) { while (seen.seen.isEmpty()) delay(10) }

            assertEquals(RelayDisconnect.Side.CLIENT, seen.seen.first()?.side)
            assertEquals("removed from the pool", seen.seen.first()?.reason)
        }

    @Test
    fun aNegentropyManagerSessionHearsHowItsRelayDisconnected() {
        val heard = mutableListOf<RelayDisconnect?>()
        val errors = mutableListOf<String>()
        val manager =
            NegentropyManager(
                object : INegentropyListener {
                    override fun onHaveIds(
                        relay: NormalizedRelayUrl,
                        subId: String,
                        haveIds: List<String>,
                    ) = Unit

                    override fun onNeedIds(
                        relay: NormalizedRelayUrl,
                        subId: String,
                        needIds: List<String>,
                    ) = Unit

                    override fun onComplete(
                        relay: NormalizedRelayUrl,
                        subId: String,
                    ) = Unit

                    override fun onError(
                        relay: NormalizedRelayUrl,
                        subId: String,
                        reason: String,
                    ) {
                        errors += reason
                    }

                    override fun onRelayDisconnected(
                        relay: NormalizedRelayUrl,
                        subId: String,
                        disconnect: RelayDisconnect?,
                    ) {
                        heard += disconnect
                    }
                },
            )
        var relaySide: FakeSocket? = null
        val relay =
            BasicRelayClient(
                url,
                object : WebsocketBuilder {
                    override fun build(
                        url: NormalizedRelayUrl,
                        out: WebSocketListener,
                    ): WebSocket = FakeSocket(out).also { relaySide = it }
                },
                manager,
            )
        relay.connect()
        manager.startSync(relay, "sync", Filter(kinds = listOf(1)), emptyList())
        relaySide!!.out.onClosed(1009, "too big")

        assertEquals(1009, heard.single()?.code)
        assertTrue(errors.isEmpty(), "the typed callback replaced the bare sentence")
    }
}
