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

import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
 * The client side of RFC 6455 framing, for [OkHttpBytesWebSocket]: OkHttp dials and upgrades the
 * connection, and these read and write the frames on the socket it hands back, so a text frame
 * reaches the parser as bytes instead of as a String.
 */

internal object WsOpcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

/** A protocol violation, with the close code the RFC gives it. */
internal class WsProtocolException(
    message: String,
    val closeCode: Int = 1002,
) : ProtocolException(message)

/**
 * The scratch a frame is read into, shared by every connection. A buffer is only held while one
 * message is read and parsed on its connection's reader thread, so what is kept follows how many
 * connections are reading at once, not how many are open: a buffer per connection would pin
 * 16 KiB for each of thousands of mostly idle relays.
 */
internal object WsBufferPool {
    const val SIZE = 16 * 1024
    private const val MAX_POOLED = 64

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val pooled = AtomicInteger(0)

    /** A buffer of at least [min] bytes: a pooled one when [min] fits [SIZE]. */
    fun acquire(min: Int = SIZE): ByteArray {
        if (min <= SIZE) {
            val b = pool.poll()
            if (b != null) {
                pooled.decrementAndGet()
                return b
            }
            return ByteArray(SIZE)
        }
        return ByteArray(min)
    }

    /** Gives [b] back; only [SIZE] buffers are kept, up to [MAX_POOLED]. */
    fun release(b: ByteArray) {
        if (b.size != SIZE) return
        if (pooled.incrementAndGet() > MAX_POOLED) {
            pooled.decrementAndGet()
            return
        }
        pool.offer(b)
    }

    /** [b] with room for [needed] bytes, the first [used] kept; [b] is released when replaced. */
    fun grow(
        b: ByteArray,
        used: Int,
        needed: Int,
    ): ByteArray {
        if (needed <= b.size) return b
        val bigger = ByteArray(maxOf(needed, b.size * 2))
        b.copyInto(bigger, 0, 0, used)
        release(b)
        return bigger
    }
}

/** What the server agreed to in `Sec-WebSocket-Extensions`: only permessage-deflate is offered. */
internal class WsExtensions(
    val perMessageDeflate: Boolean,
    val serverNoContextTakeover: Boolean,
) {
    companion object {
        val NONE = WsExtensions(false, false)

        /**
         * The server's answer to our `permessage-deflate` offer (RFC 7692). Anything we did not
         * offer, or a parameter we don't know, fails the session, as OkHttp does. We never
         * compress what we send, so the client_* parameters only need to be valid.
         */
        fun parse(headerValues: List<String>): WsExtensions {
            var deflate = false
            var serverNoContext = false
            for (header in headerValues) {
                for (extension in header.split(',')) {
                    if (extension.isBlank()) continue
                    val parts = extension.split(';').map { it.trim() }
                    if (!parts[0].equals("permessage-deflate", ignoreCase = true)) {
                        throw WsProtocolException("Unexpected Sec-WebSocket-Extensions: ${parts[0]}", 1010)
                    }
                    if (deflate) throw WsProtocolException("permessage-deflate negotiated twice", 1010)
                    deflate = true
                    for (k in 1 until parts.size) {
                        val param = parts[k]
                        if (param.isEmpty()) continue
                        val name = param.substringBefore('=').trim().lowercase()
                        val value = if ('=' in param) param.substringAfter('=').trim().removeSurrounding("\"") else null
                        when (name) {
                            "server_no_context_takeover" -> serverNoContext = true
                            "client_no_context_takeover" -> {}
                            "server_max_window_bits" -> {
                                val bits = value?.toIntOrNull()
                                if (bits == null || bits !in 8..15) throw WsProtocolException("Bad server_max_window_bits: $value", 1010)
                            }
                            "client_max_window_bits" -> {
                                if (value != null && value.toIntOrNull()?.let { it in 8..15 } != true) {
                                    throw WsProtocolException("Bad client_max_window_bits: $value", 1010)
                                }
                            }
                            else -> throw WsProtocolException("Unexpected permessage-deflate parameter: $param", 1010)
                        }
                    }
                }
            }
            return if (deflate) WsExtensions(true, serverNoContext) else NONE
        }
    }
}

/**
 * Reads the server's frames: control frames are handed to [Callback] as they come, even between
 * the fragments of a message, and a data message once it is whole (and inflated, when
 * compressed). A text message's bytes are valid only during [Callback.onText].
 */
internal class WsFrameReader(
    private val source: BufferedSource,
    private val extensions: WsExtensions,
    private val maxMessageBytes: Int,
) : AutoCloseable {
    interface Callback {
        /** A whole text message: [length] UTF-8 bytes of [bytes], valid only during the call. */
        fun onText(
            bytes: ByteArray,
            length: Int,
        )

        fun onPing(payload: ByteString)

        fun onPong()

        fun onClose(
            code: Int,
            reason: String,
        )

        /**
         * The next read has to wait for the network: what was read so far should be handed on
         * now, not after the rest of a frame that may take long to arrive.
         */
        fun onWouldBlock() {}
    }

    private var inflater: Inflater? = null
    private var receivedClose = false

    // The frame being read.
    private var opcode = 0
    private var fin = false
    private var compressed = false
    private var length = 0L

    /** Reads frames until one message, or one close, has been handed to [callback]. */
    fun readMessage(callback: Callback) {
        if (receivedClose) throw IOException("closed")
        while (true) {
            readHeader(callback)
            if (opcode >= WsOpcode.CLOSE) {
                readControlFrame(callback)
                if (receivedClose) return
                continue
            }
            readDataMessage(callback)
            return
        }
    }

    /** Calls [Callback.onWouldBlock] when fewer than [bytes] are buffered. */
    private fun need(
        bytes: Long,
        callback: Callback,
    ) {
        if (source.buffer.size < bytes) callback.onWouldBlock()
    }

    private fun readHeader(callback: Callback) {
        need(2, callback)
        // Waiting for the next frame is not a read timeout: an idle relay is fine (pings check
        // liveness). The payload that follows is read under the timeout again.
        val timeout = source.timeout()
        val before = timeout.timeoutNanos()
        timeout.clearTimeout()
        val b0: Int
        try {
            b0 = source.readByte().toInt() and 0xFF
        } finally {
            timeout.timeout(before, TimeUnit.NANOSECONDS)
        }
        opcode = b0 and 0x0F
        fin = b0 and 0x80 != 0
        val rsv1 = b0 and 0x40 != 0
        if (b0 and 0x30 != 0) throw WsProtocolException("Unexpected rsv2/rsv3 flag")
        val isControl = opcode >= WsOpcode.CLOSE
        if (isControl && !fin) throw WsProtocolException("Control frames must be final.")
        when {
            isControl -> if (rsv1) throw WsProtocolException("Unexpected rsv1 flag on a control frame")
            opcode == WsOpcode.CONTINUATION -> if (rsv1) throw WsProtocolException("Unexpected rsv1 flag on a continuation")
            opcode == WsOpcode.TEXT || opcode == WsOpcode.BINARY -> {
                if (rsv1 && !extensions.perMessageDeflate) throw WsProtocolException("Unexpected rsv1 flag")
                compressed = rsv1
            }
            else -> throw WsProtocolException("Unknown opcode: ${opcode.toString(16)}")
        }
        if (isControl && opcode != WsOpcode.CLOSE && opcode != WsOpcode.PING && opcode != WsOpcode.PONG) {
            throw WsProtocolException("Unknown control opcode: ${opcode.toString(16)}")
        }

        val b1 = source.readByte().toInt() and 0xFF
        if (b1 and 0x80 != 0) throw WsProtocolException("Server-sent frames must not be masked.")
        length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            need(2, callback)
            length = (source.readShort().toInt() and 0xFFFF).toLong()
        } else if (length == 127L) {
            need(8, callback)
            length = source.readLong()
            if (length < 0) throw WsProtocolException("Frame length 0x${length.toULong().toString(16)} > 0x7FFFFFFFFFFFFFFF")
        }
        if (isControl && length > 125) throw WsProtocolException("Control frame must be less than 125B.")
    }

    private fun readControlFrame(callback: Callback) {
        need(length, callback)
        val payload = if (length > 0) source.readByteString(length) else ByteString.EMPTY
        when (opcode) {
            WsOpcode.PING -> callback.onPing(payload)
            WsOpcode.PONG -> callback.onPong()
            WsOpcode.CLOSE -> {
                var code = 1005
                var reason = ""
                if (payload.size == 1) throw WsProtocolException("Malformed close payload length of 1.")
                if (payload.size >= 2) {
                    code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    reason = payload.substring(2).utf8()
                    closeCodeError(code)?.let { throw WsProtocolException(it) }
                }
                receivedClose = true
                callback.onClose(code, reason)
            }
        }
    }

    /** One data message, its first frame's header just read; continuations are read until fin. */
    private fun readDataMessage(callback: Callback) {
        if (opcode == WsOpcode.CONTINUATION) throw WsProtocolException("Unexpected continuation frame")
        val messageOpcode = opcode
        val messageCompressed = compressed
        var buf = WsBufferPool.acquire()
        var len = 0
        try {
            while (true) {
                if (length > maxMessageBytes - len) throw WsProtocolException("Message larger than $maxMessageBytes bytes", 1009)
                val n = length.toInt()
                buf = WsBufferPool.grow(buf, len, len + n + if (messageCompressed) 4 else 0)
                readFully(buf, len, n, callback)
                len += n
                if (fin) break
                // Control frames may come between the fragments.
                while (true) {
                    readHeader(callback)
                    if (opcode < WsOpcode.CLOSE) break
                    readControlFrame(callback)
                    if (receivedClose) return
                }
                if (opcode != WsOpcode.CONTINUATION) throw WsProtocolException("Expected continuation opcode. Got: ${opcode.toString(16)}")
            }
            if (messageOpcode != WsOpcode.TEXT) return // Nostr is text: binary messages are dropped, as before.
            if (messageCompressed) {
                val inflated = inflate(buf, len)
                try {
                    callback.onText(inflated.bytes, inflated.length)
                } finally {
                    WsBufferPool.release(inflated.bytes)
                }
            } else {
                callback.onText(buf, len)
            }
        } finally {
            WsBufferPool.release(buf)
        }
    }

    private class Inflated(
        val bytes: ByteArray,
        val length: Int,
    )

    /** RFC 7692 §7.2.2: the message plus `00 00 ff ff`, inflated into a pooled buffer. */
    private fun inflate(
        input: ByteArray,
        inputLength: Int,
    ): Inflated {
        val inflater = inflater ?: Inflater(true).also { inflater = it }
        if (extensions.serverNoContextTakeover) inflater.reset()
        input[inputLength] = 0
        input[inputLength + 1] = 0
        input[inputLength + 2] = 0xFF.toByte()
        input[inputLength + 3] = 0xFF.toByte()
        inflater.setInput(input, 0, inputLength + 4)
        var out = WsBufferPool.acquire()
        var len = 0
        try {
            while (true) {
                if (len == out.size) {
                    if (len >= maxMessageBytes) throw WsProtocolException("Message larger than $maxMessageBytes bytes", 1009)
                    out = WsBufferPool.grow(out, len, minOf(maxMessageBytes, out.size * 2))
                }
                val n =
                    try {
                        inflater.inflate(out, len, out.size - len)
                    } catch (e: DataFormatException) {
                        throw WsProtocolException("Bad compressed message: ${e.message}", 1007)
                    }
                len += n
                if (n == 0) {
                    if (inflater.finished()) {
                        // The server ended its stream (BFINAL): the next message starts a new one.
                        inflater.reset()
                        break
                    }
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw WsProtocolException("Compressed message needs a dictionary", 1007)
                }
            }
            return Inflated(out, len)
        } catch (e: Throwable) {
            WsBufferPool.release(out)
            throw e
        }
    }

    private fun readFully(
        dst: ByteArray,
        offset: Int,
        count: Int,
        callback: Callback,
    ) {
        var done = 0
        while (done < count) {
            if (source.buffer.size == 0L) callback.onWouldBlock()
            val r = source.read(dst, offset + done, count - done)
            if (r < 0) throw EOFException()
            done += r
        }
    }

    override fun close() {
        inflater?.end()
        inflater = null
    }
}

/** Why a close [code] is invalid, as OkHttp's `WebSocketProtocol.closeCodeExceptionMessage`, or null. */
internal fun closeCodeError(code: Int): String? =
    when {
        code < 1000 || code >= 5000 -> "Code must be in range [1000,5000): $code"
        code in 1004..1006 || code in 1015..2999 -> "Code $code is reserved and may not be used."
        else -> null
    }

/**
 * Writes the client's frames, each masked with a fresh key (RFC 6455 §5.3). Never compresses:
 * what a client sends is a few REQs. Callers serialize through the instance's lock.
 */
internal class WsFrameWriter(
    private val sink: BufferedSink,
) {
    private val maskKey = ByteArray(4)

    @Synchronized
    fun writeText(payload: ByteArray) = writeFrame(WsOpcode.TEXT, payload, payload.size)

    @Synchronized
    fun writeControl(
        opcode: Int,
        payload: ByteArray,
    ) = writeFrame(opcode, payload, payload.size)

    @Synchronized
    fun writeClose(
        code: Int,
        reason: String?,
    ) {
        val reasonBytes = reason?.encodeToByteArray() ?: ByteArray(0)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = (code shr 8).toByte()
        payload[1] = code.toByte()
        reasonBytes.copyInto(payload, 2)
        writeFrame(WsOpcode.CLOSE, payload, payload.size)
    }

    /** Masks [payload] in place: callers hand over arrays they no longer need. */
    private fun writeFrame(
        opcode: Int,
        payload: ByteArray,
        length: Int,
    ) {
        sink.writeByte(0x80 or opcode)
        when {
            length <= 125 -> sink.writeByte(0x80 or length)
            length <= 0xFFFF -> {
                sink.writeByte(0x80 or 126)
                sink.writeShort(length)
            }
            else -> {
                sink.writeByte(0x80 or 127)
                sink.writeLong(length.toLong())
            }
        }
        RANDOM.nextBytes(maskKey)
        sink.write(maskKey)
        for (k in 0 until length) payload[k] = (payload[k].toInt() xor maskKey[k and 3].toInt()).toByte()
        sink.write(payload, 0, length)
        sink.flush()
    }

    companion object {
        private val RANDOM = SecureRandom()
    }
}
