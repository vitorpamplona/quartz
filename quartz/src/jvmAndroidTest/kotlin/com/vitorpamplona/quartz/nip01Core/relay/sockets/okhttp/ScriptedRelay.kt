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

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import kotlin.concurrent.thread

/**
 * A loopback WebSocket server that runs [script] against the first client it accepts, speaking
 * raw RFC 6455 from the server side: tests write exactly the frames they need (fragments,
 * control frames between them, compressed messages, bad frames) and read what the client sends.
 */
class ScriptedRelay(
    /** The handshake's status line; anything but 101 is a refused upgrade. */
    private val status: String = "101 Switching Protocols",
    /** The `Sec-WebSocket-Extensions` answer, if any. */
    private val extensions: String? = null,
    private val badAccept: Boolean = false,
    private val script: Session.() -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val url = NormalizedRelayUrl("ws://127.0.0.1:${server.localPort}/")
    val done = CountDownLatch(1)
    val error = AtomicReference<Throwable?>(null)
    private var socket: Socket? = null

    class Frame(
        val opcode: Int,
        val payload: ByteArray,
    ) {
        val text get() = payload.decodeToString()
        val closeCode get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    inner class Session(
        private val input: DataInputStream,
        private val output: OutputStream,
        val requestHeaders: List<String>,
    ) {
        private var deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)

        fun frame(
            opcode: Int,
            payload: ByteArray,
            fin: Boolean = true,
            rsv1: Boolean = false,
            masked: Boolean = false,
        ) {
            output.write((if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0) or opcode)
            val maskBit = if (masked) 0x80 else 0
            when {
                payload.size <= 125 -> output.write(maskBit or payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(maskBit or 126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(maskBit or 127)
                    for (k in 7 downTo 0) output.write(((payload.size.toLong() shr (8 * k)) and 0xFF).toInt())
                }
            }
            if (masked) {
                val key = byteArrayOf(1, 2, 3, 4)
                output.write(key)
                output.write(ByteArray(payload.size) { (payload[it].toInt() xor key[it and 3].toInt()).toByte() })
            } else {
                output.write(payload)
            }
            output.flush()
        }

        fun text(message: String) = frame(1, message.encodeToByteArray())

        /** Bytes as they are, for frames [frame] won't write (a truncated one). */
        fun raw(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        /** [message] compressed per RFC 7692, keeping the context unless [noContext]. */
        fun compressed(
            message: String,
            noContext: Boolean = false,
        ) = frame(1, deflate(message.encodeToByteArray(), noContext), rsv1 = true)

        fun deflate(
            bytes: ByteArray,
            noContext: Boolean = false,
        ): ByteArray {
            if (noContext) deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                out.write(buf, 0, n)
                if (n < buf.size) break
            }
            val all = out.toByteArray()
            // A sync flush ends with 00 00 ff ff, which the receiver puts back.
            return all.copyOf(all.size - 4)
        }

        fun close(
            code: Int,
            reason: String,
        ) = frame(8, byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.encodeToByteArray())

        /** The next frame the client sent, unmasked; client frames must be masked. */
        fun read(): Frame {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            check(b1 and 0x80 != 0) { "client frames must be masked" }
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = input.readUnsignedShort().toLong()
            } else if (len == 127L) {
                len = input.readLong()
            }
            val key = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(len.toInt()).also { input.readFully(it) }
            for (k in payload.indices) payload[k] = (payload[k].toInt() xor key[k and 3].toInt()).toByte()
            return Frame(b0 and 0x0F, payload)
        }

        /** Blocks until the client drops the connection. */
        fun awaitHangUp() {
            while (input.read() != -1) { }
        }
    }

    private val thread =
        thread(isDaemon = true, name = "scripted-relay") {
            try {
                val s = server.accept()
                socket = s
                val input = DataInputStream(s.getInputStream())
                val output = s.getOutputStream()
                val headers = handshake(input, output)
                if (status.startsWith("101")) Session(input, output, headers).script()
            } catch (e: Throwable) {
                error.set(e)
            } finally {
                done.countDown()
            }
        }

    private fun handshake(
        input: InputStream,
        output: OutputStream,
    ): List<String> {
        var key: String? = null
        val headers = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            check(c != -1) { "EOF during handshake" }
            if (c == '\n'.code) {
                val l = line.toString().trim()
                if (l.isEmpty()) break
                headers.add(l)
                if (l.lowercase().startsWith("sec-websocket-key:")) key = l.substring(18).trim()
                line.setLength(0)
            } else if (c != '\r'.code) {
                line.append(c.toChar())
            }
        }
        val accept =
            if (badAccept) {
                "nope"
            } else {
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            }
        val response =
            if (status.startsWith("101")) {
                "HTTP/1.1 $status\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
                    (extensions?.let { "Sec-WebSocket-Extensions: $it\r\n" } ?: "") + "\r\n"
            } else {
                "HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n"
            }
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return headers
    }

    fun awaitDone(seconds: Long = 10) = done.await(seconds, TimeUnit.SECONDS)

    override fun close() {
        runCatching { socket?.close() }
        runCatching { server.close() }
        thread.join(2_000)
    }
}
