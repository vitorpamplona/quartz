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
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnects respect each relay's backoff: a client coming back to the foreground, a reconnect
 * queued just before the host disconnected, and a wait for the transport neither dial early nor
 * grow the backoff, which stays under five minutes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NostrClientReconnectBackoffTest {
    private val url = NormalizedRelayUrl("wss://audit.example.com/")
    private val other = NormalizedRelayUrl("wss://other.example.com/")
    private val signer = NostrSignerSync()

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, emptyArray(), content)

    /** Each socket it builds, with what was sent on it. [failOnConnect] makes every dial fail at once. */
    private class RecordingBuilder(
        val failOnConnect: Boolean = false,
    ) : WebsocketBuilder {
        val sent = mutableListOf<MutableList<String>>()
        var builds = 0
        lateinit var lastListener: WebSocketListener

        @Volatile var transportReady = true

        override fun canConnect(url: NormalizedRelayUrl) = transportReady

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            builds++
            lastListener = out
            val mine = mutableListOf<String>().also { sent += it }
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() {
                    if (failOnConnect) out.onFailure(IOException("Connection refused"), null, null)
                }

                override fun disconnect() {}

                override fun send(msg: String): Boolean {
                    mine += msg
                    return true
                }
            }
        }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun deliver(
        builder: RecordingBuilder,
        msg: String,
    ) = runBlocking { builder.lastListener.onMessage(msg) }

    private val idRegex = Regex("\"id\":\"([0-9a-f]{64})\"")

    @Test
    fun aForegroundAfterAFailedDialRespectsTheBackoff() =
        runTest {
            val builder = RecordingBuilder(failOnConnect = true)
            val client = NostrClient(builder, this)
            try {
                settle()
                client.subscribe("feed", mapOf(url to listOf(Filter(kinds = listOf(1)))))
                runCurrent()
                assertEquals(1, builder.builds, "the subscription dials once, and the dial fails")
                // The app backgrounds and comes back within the backoff.
                client.disconnect()
                client.connect()
                runCurrent()
                assertEquals(1, builder.builds, "a relay that never connected is not redialed inside its backoff")
            } finally {
                client.close()
            }
        }

    @Test
    fun aReconnectQueuedBeforeDisconnectDoesNotDialAfterIt() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            try {
                settle()
                client.subscribe("feed", mapOf(url to listOf(Filter(kinds = listOf(1)))))
                runCurrent()
                val dials = builder.builds
                // subscribe() queued a debounced reconnect; the host disconnects inside its 200 ms.
                client.disconnect()
                settle()
                assertEquals(dials, builder.builds, "an inactive client does not dial")
            } finally {
                client.close()
            }
        }

    private class NoOpListener : RelayConnectionListener

    @Test
    fun waitingForTheTransportDoesNotGrowTheBackoff() {
        val builder = RecordingBuilder(failOnConnect = true)
        var now = 1_000L
        val relay = BasicRelayClient(url, builder, NoOpListener(), nowInSeconds = { now })
        builder.transportReady = false
        repeat(10) {
            relay.connectAndSyncFiltersIfDisconnected()
            now += 1
        }
        assertEquals(0, builder.builds, "no dial while the transport is not ready")
        builder.transportReady = true
        relay.connectAndSyncFiltersIfDisconnected()
        assertEquals(1, builder.builds)
        assertTrue(relay.secondsUntilReconnect() <= 3, "one failed dial backs off a couple of seconds, not ${relay.secondsUntilReconnect()}")
    }

    @Test
    fun theBackoffNeverExceedsFiveMinutes() {
        val builder = RecordingBuilder(failOnConnect = true)
        var now = 1_000L
        val relay = BasicRelayClient(url, builder, NoOpListener(), nowInSeconds = { now })
        repeat(15) {
            relay.connectAndSyncFiltersIfDisconnected()
            now += 1_000
        }
        assertTrue(relay.secondsUntilReconnect() <= 300 + 1, "capped at five minutes, got ${relay.secondsUntilReconnect()}")
    }
}
