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
package com.vitorpamplona.quartz.testing.relay

import com.vitorpamplona.quartz.nip01Core.relay.server.SessionSink
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Serves an [InMemoryRelay] over a real `ws://` socket on localhost, for the
 * tests that need the OkHttp transport and TCP backpressure in the loop rather
 * than [InProcessRelays]' in-memory bridge.
 *
 * ```
 * val server = TestWebSocketRelay(relay, port = 0).start()
 * val client = NostrClient(BasicOkHttpWebSocket.Builder { ... })
 * ...server.url...
 * server.stop()
 * ```
 */
class TestWebSocketRelay(
    val relay: InMemoryRelay,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val maxFrameSize: Long = 1024 * 1024,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var resolvedPort: Int = -1

    val url: String
        get() {
            check(resolvedPort != -1) { "Server not started" }
            return "ws://$host:$resolvedPort/"
        }

    fun start(): TestWebSocketRelay {
        val bindHost = host
        val bindPort = port
        val nostr = relay.server
        val embedded =
            embeddedServer(CIO, host = bindHost, port = bindPort) {
                install(WebSockets) { maxFrameSize = this@TestWebSocketRelay.maxFrameSize }
                routing {
                    webSocket("/") {
                        val out = Channel<String>(Channel.UNLIMITED)
                        val writer =
                            launch {
                                try {
                                    for (json in out) outgoing.send(Frame.Text(json))
                                } catch (_: ClosedSendChannelException) {
                                    // socket closed; teardown below
                                }
                            }
                        val session = nostr.connect(SessionSink.of { json -> out.trySend(json) })
                        try {
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) session.receive(frame.readText())
                            }
                        } finally {
                            out.close()
                            writer.cancel()
                            session.close()
                        }
                    }
                }
            }
        embedded.start(wait = false)
        server = embedded
        resolvedPort = runBlocking { embedded.engine.resolvedConnectors().first().port }
        return this
    }

    fun stop(
        gracePeriodMillis: Long = 1_000,
        timeoutMillis: Long = 5_000,
    ) {
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
        resolvedPort = -1
    }
}
