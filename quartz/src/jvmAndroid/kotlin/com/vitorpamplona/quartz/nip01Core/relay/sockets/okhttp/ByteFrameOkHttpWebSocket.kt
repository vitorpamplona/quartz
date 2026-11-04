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
package com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp

import com.vitorpamplona.quartz.nip01Core.kite.KiteByteReader
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.acceptsDecoded
import com.vitorpamplona.quartz.nip01Core.relay.sockets.deliverDecoded
import com.vitorpamplona.quartz.nip01Core.relay.sockets.deliverMessage
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.Socket
import okio.buffer
import java.io.IOException
import java.net.ProtocolException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * EXPERIMENT: a [WebSocket] that reads the relay's frames itself. OkHttp dials, does TLS, the
 * proxy and the HTTP upgrade (a plain call with `Connection: Upgrade`, whose 101 response hands
 * back the connection as [Response.socket]); from there this reads each frame's payload into one
 * reused [ByteArray], instead of OkHttp's okio segments and a `String` per message.
 *
 * With [parseBytes], an EVENT frame is parsed from those bytes ([KiteByteReader]) on the read
 * thread and the parsed message is queued, so the frame never becomes a `String`; anything else
 * is decoded to a `String` and queued as text, as [BasicOkHttpWebSocket] does.
 *
 * Keeps [BasicOkHttpWebSocket]'s session contract (one terminal report, a synchronous
 * [disconnect], messages received before the relay's end delivered before it). Not yet:
 * permessage-deflate, client pings, a bound on message size.
 */
class ByteFrameOkHttpWebSocket(
    val url: NormalizedRelayUrl,
    val httpClient: (NormalizedRelayUrl) -> OkHttpClient,
    val out: WebSocketListener,
    val parseBytes: Boolean = true,
) : WebSocket {
    private val state = AtomicInteger(OPEN)

    @Volatile private var conn: Socket? = null

    @Volatile private var sink: BufferedSink? = null

    @Volatile private var call: Call? = null

    @Volatile private var dialing = false

    @Volatile private var incoming: Channel<Any>? = null

    @Volatile private var scope: CoroutineScope? = null

    private val writeLock = Any()

    override fun needsReconnect() =
        when (state.get()) {
            OPEN -> conn == null && !dialing
            DRAINING -> false
            else -> true
        }

    private fun endDelivery() {
        incoming?.cancel()
        scope?.cancel()
    }

    private class SessionEnd(
        val report: () -> Unit,
    )

    /** The relay (or the network) ended the session: deliver what was received, then the end. */
    private fun endedByRelay(report: () -> Unit) {
        if (!state.compareAndSet(OPEN, DRAINING)) return
        conn?.cancel()
        conn = null
        sink = null
        val ch = incoming ?: return
        ch.trySend(SessionEnd(report))
        ch.close()
    }

    override fun connect() {
        if (conn != null || dialing || state.get() != OPEN) return
        dialing = true

        val key = ByteArray(16).also { random.nextBytes(it) }.toByteString().base64()
        val request =
            Request
                .Builder()
                .url(url.url)
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Key", key)
                .header("Sec-WebSocket-Version", "13")
                .build()

        val newScope = CoroutineScope(Dispatchers.IO + BasicOkHttpWebSocket.exceptionHandler)
        val ch = Channel<Any>(Channel.UNLIMITED)
        scope = newScope
        incoming = ch
        newScope.launch {
            for (item in ch) {
                if (item is SessionEnd) {
                    if (state.compareAndSet(DRAINING, CLOSED)) item.report()
                    break
                }
                try {
                    if (item is String) out.deliverMessage(item) else out.deliverDecoded(item as Message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("ByteFrameWebSocket", "Listener failed on a message from ${url.url}", e)
                }
            }
            newScope.cancel()
        }

        try {
            val client = httpClient(url).newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
            val c = client.newCall(request)
            call = c
            c.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        dialing = false
                        endedByRelay { out.onFailure(e, null, null) }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        dialing = false
                        val socket = response.socket
                        val accept = (key + ACCEPT_GUID).encodeUtf8().sha1().base64()
                        if (response.code != 101 || socket == null || response.header("Sec-WebSocket-Accept") != accept) {
                            val code = response.code
                            val message = response.message
                            response.close()
                            endedByRelay { out.onFailure(ProtocolException("Expected a WebSocket upgrade, got $code $message"), code, message) }
                            return
                        }
                        if (state.get() != OPEN) {
                            socket.cancel()
                            return
                        }
                        // Pings, not a read timeout, keep a WebSocket honest; an idle relay is not a dead one.
                        socket.source.timeout().clearTimeout()
                        conn = socket
                        sink = socket.sink.buffer()
                        out.onOpen((response.receivedResponseAtMillis - response.sentRequestAtMillis).toInt(), false)
                        readLoop(socket.source.buffer(), ch, parseBytes && out.acceptsDecoded())
                    }
                },
            )
        } catch (e: Throwable) {
            dialing = false
            endDelivery()
            throw e
        }
    }

    private fun readLoop(
        src: BufferedSource,
        ch: Channel<Any>,
        decode: Boolean,
    ) {
        var msg = ByteArray(INITIAL_BUFFER)
        var msgLen = 0
        var msgOpcode = -1
        val control = ByteArray(125)
        try {
            while (state.get() == OPEN) {
                val b0 = src.readByte().toInt() and 0xff
                val b1 = src.readByte().toInt() and 0xff
                if (b0 and 0x70 != 0) throw ProtocolException("Reserved bits are set: no extension was negotiated")
                if (b1 and 0x80 != 0) throw ProtocolException("A server frame must not be masked")
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0f
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) {
                    len = (src.readShort().toInt() and 0xffff).toLong()
                } else if (len == 127L) {
                    len = src.readLong()
                    if (len < 0) throw ProtocolException("Frame length out of range")
                }

                if (opcode >= 8) {
                    if (!fin || len > 125) throw ProtocolException("Bad control frame")
                    readFully(src, control, 0, len.toInt())
                    when (opcode) {
                        OP_CLOSE -> {
                            val code = if (len >= 2) ((control[0].toInt() and 0xff) shl 8) or (control[1].toInt() and 0xff) else 1005
                            val reason = if (len > 2) control.decodeToString(2, len.toInt()) else ""
                            runCatching { writeFrame(OP_CLOSE, CLOSE_NORMAL, CLOSE_NORMAL.size) }
                            endedByRelay { out.onClosed(code, reason) }
                            return
                        }
                        OP_PING -> writeFrame(OP_PONG, control, len.toInt())
                        OP_PONG -> {}
                        else -> throw ProtocolException("Unknown control opcode $opcode")
                    }
                    continue
                }

                when (opcode) {
                    OP_CONTINUATION -> if (msgOpcode < 0) throw ProtocolException("Continuation without a message")
                    OP_TEXT, OP_BINARY -> if (msgOpcode >= 0) throw ProtocolException("Expected a continuation") else msgOpcode = opcode
                    else -> throw ProtocolException("Unknown opcode $opcode")
                }
                if (msgLen + len > Int.MAX_VALUE - 8) throw ProtocolException("Message too big")
                val need = msgLen + len.toInt()
                if (need > msg.size) msg = msg.copyOf(maxOf(need, msg.size * 2))
                readFully(src, msg, msgLen, len.toInt())
                msgLen = need

                if (fin) {
                    // Binary messages are dropped, as BasicOkHttpWebSocket drops them.
                    if (msgOpcode == OP_TEXT && state.get() == OPEN) {
                        val parsed = if (decode) KiteByteReader.readEventMessage(msg, msgLen) else null
                        ch.trySendBlocking(parsed ?: msg.decodeToString(0, msgLen))
                    }
                    msgLen = 0
                    msgOpcode = -1
                    // Don't let one huge message pin its buffer to the session.
                    if (msg.size > MAX_KEPT_BUFFER) msg = ByteArray(INITIAL_BUFFER)
                }
            }
        } catch (e: Throwable) {
            endedByRelay { out.onFailure(e, null, null) }
        }
    }

    private fun readFully(
        src: BufferedSource,
        into: ByteArray,
        offset: Int,
        count: Int,
    ) {
        var done = 0
        while (done < count) {
            val r = src.read(into, offset + done, count - done)
            if (r < 0) throw java.io.EOFException()
            done += r
        }
    }

    private fun writeFrame(
        opcode: Int,
        payload: ByteArray,
        len: Int,
    ) {
        val s = sink ?: throw IOException("Not connected")
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val masked = ByteArray(len)
        for (k in 0 until len) masked[k] = (payload[k].toInt() xor mask[k and 3].toInt()).toByte()
        synchronized(writeLock) {
            s.writeByte(0x80 or opcode)
            when {
                len <= 125 -> s.writeByte(0x80 or len)
                len <= 0xffff -> {
                    s.writeByte(0x80 or 126)
                    s.writeShort(len)
                }
                else -> {
                    s.writeByte(0x80 or 127)
                    s.writeLong(len.toLong())
                }
            }
            s.write(mask)
            s.write(masked)
            s.flush()
        }
    }

    override fun disconnect() {
        val previous = state.getAndSet(CLOSED)
        if (previous == CLOSED) return
        val closing = conn
        conn = null
        sink = null
        endDelivery()
        call?.cancel()
        closing?.cancel()
        if (closing != null || previous == DRAINING) out.onClosed(1000, "client disconnect")
    }

    override fun send(msg: String): Boolean {
        if (state.get() != OPEN || sink == null) return false
        val payload = msg.encodeToByteArray()
        return try {
            writeFrame(OP_TEXT, payload, payload.size)
            true
        } catch (e: IOException) {
            false
        }
    }

    class Builder(
        val httpClient: (NormalizedRelayUrl) -> OkHttpClient,
        val parseBytes: Boolean = true,
    ) : WebsocketBuilder {
        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket = ByteFrameOkHttpWebSocket(url, httpClient, out, parseBytes)
    }

    companion object {
        private const val OPEN = 0
        private const val DRAINING = 1
        private const val CLOSED = 2

        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        private const val INITIAL_BUFFER = 16 * 1024
        private const val MAX_KEPT_BUFFER = 1024 * 1024

        private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val CLOSE_NORMAL = byteArrayOf(0x03, 0xE8.toByte())

        private val random = SecureRandom()
    }
}
