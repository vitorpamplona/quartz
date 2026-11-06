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
package com.vitorpamplona.quartz.nip01Core.prodbench

import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CachingEventDecoder
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.Utf8WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.OkHttpBytesWebSocket
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import com.sun.management.ThreadMXBean as SunThreadMXBean

/**
 * Relay frames off a real socket into `Message`s, through [BasicRelayClient] and a
 * [CachingEventDecoder] as the app runs them: [BasicOkHttpWebSocket] (OkHttp builds each frame's
 * String, the decoder parses it) against [OkHttpBytesWebSocket] (frames read as bytes and parsed
 * there). A loopback server streams nostrJsonBench's startup corpus as EVENT frames, plain and
 * with permessage-deflate, unique and with duplicates. Time is until the last message reaches the
 * listener; allocation is every thread's but the server's.
 *
 * `KITE_UTF8_CORPUS=…/nostr-2024-startup.ndjson.gz ./gradlew :quartz:jvmTest --tests "*.SocketIngestBenchmark" -i`
 */
class SocketIngestBenchmark {
    companion object {
        private val mx = ManagementFactory.getThreadMXBean() as SunThreadMXBean
    }

    private val path = System.getenv("KITE_UTF8_CORPUS")

    private fun events(): List<ByteArray> =
        GZIPInputStream(File(path!!).inputStream()).use { it.readBytes() }.let { all ->
            val out = ArrayList<ByteArray>()
            var start = 0
            for (k in all.indices) {
                if (all[k] == '\n'.code.toByte()) {
                    if (k > start) out.add(all.copyOfRange(start, k))
                    start = k + 1
                }
            }
            out
        }

    /** The wire bytes of [frames] as server frames, compressed per message with context takeover when [deflate]. */
    private class Wire(
        val bytes: ByteArray,
        /** Where each frame ends in [bytes]. */
        val ends: IntArray,
    )

    private fun wire(
        frames: List<ByteArray>,
        deflate: Boolean,
    ): Wire {
        val ends = IntArray(frames.size)
        val out = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val buf = ByteArray(64 * 1024)
        for ((index, f) in frames.withIndex()) {
            var payload = f
            if (deflate) {
                deflater.setInput(f)
                val c = ByteArrayOutputStream()
                while (true) {
                    val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                    c.write(buf, 0, n)
                    if (n < buf.size) break
                }
                val all = c.toByteArray()
                payload = all.copyOf(all.size - 4)
            }
            out.write(0x80 or (if (deflate) 0x40 else 0) or 1)
            when {
                payload.size <= 125 -> out.write(payload.size)
                payload.size <= 0xFFFF -> {
                    out.write(126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    for (k in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * k)) and 0xFF).toInt())
                }
            }
            out.write(payload)
            ends[index] = out.size()
        }
        return Wire(out.toByteArray(), ends)
    }

    /** Accepts one client, answers the handshake, writes [wire], and holds the connection until closed. */
    private class BulkRelay(
        private val wire: Wire,
        private val deflate: Boolean,
        /** Frames written per millisecond, as a network delivers them; null floods. */
        private val perMs: Int? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val url = NormalizedRelayUrl("ws://127.0.0.1:${server.localPort}/")
        lateinit var serverThread: Thread
        private val release = CountDownLatch(1)

        init {
            serverThread =
                thread(isDaemon = true, name = "bulk-relay") {
                    runCatching {
                        server.accept().use { s ->
                            val input = s.getInputStream()
                            val output = s.getOutputStream()
                            var key: String? = null
                            val line = StringBuilder()
                            while (true) {
                                val c = input.read()
                                if (c == -1) return@use
                                if (c == '\n'.code) {
                                    val l = line.toString().trim()
                                    if (l.isEmpty()) break
                                    if (l.lowercase().startsWith("sec-websocket-key:")) key = l.substring(18).trim()
                                    line.setLength(0)
                                } else if (c != '\r'.code) {
                                    line.append(c.toChar())
                                }
                            }
                            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                            val ext = if (deflate) "Sec-WebSocket-Extensions: permessage-deflate\r\n" else ""
                            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n$ext\r\n".toByteArray(Charsets.ISO_8859_1))
                            val bytes = wire.bytes
                            if (perMs == null) {
                                var off = 0
                                while (off < bytes.size) {
                                    val n = minOf(64 * 1024, bytes.size - off)
                                    output.write(bytes, off, n)
                                    off += n
                                }
                            } else {
                                var off = 0
                                var k = 0
                                while (k < wire.ends.size) {
                                    val last = minOf(wire.ends.size, k + perMs) - 1
                                    output.write(bytes, off, wire.ends[last] - off)
                                    output.flush()
                                    off = wire.ends[last]
                                    k = last + 1
                                    Thread.sleep(1)
                                }
                            }
                            output.flush()
                            release.await(60, TimeUnit.SECONDS)
                        }
                    }
                }
        }

        override fun close() {
            release.countDown()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    private class Counter(
        val expected: Int,
    ) : RelayConnectionListener {
        val count = AtomicInteger(0)
        val done = CountDownLatch(1)

        private fun one() {
            if (count.incrementAndGet() == expected) done.countDown()
        }

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msgStr: String,
            msg: Message,
        ) = one()

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msg: Message,
            frameBytes: Int,
        ) = one()
    }

    private class Result(
        val ms: Double,
        val cpuNs: Long,
        val allocated: Long,
        val gcs: Long,
    )

    private fun cpuExcept(skip: Long): Long {
        val ids = mx.allThreadIds.filter { it != skip }.toLongArray()
        return mx.getThreadCpuTime(ids).filter { it > 0 }.sum()
    }

    private fun allocatedExcept(skip: Long): Long {
        val ids = mx.allThreadIds.filter { it != skip }.toLongArray()
        return mx.getThreadAllocatedBytes(ids).filter { it > 0 }.sum()
    }

    private fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) }

    private fun once(
        builder: WebsocketBuilder,
        wire: Wire,
        deflate: Boolean,
        frames: Int,
        perMs: Int? = null,
    ): Result {
        BulkRelay(wire, deflate, perMs).use { relay ->
            val counter = Counter(frames)
            val client = BasicRelayClient(relay.url, builder, counter, decoder = CachingEventDecoder())
            System.gc()
            val gc0 = gcCount()
            val a0 = allocatedExcept(relay.serverThread.id)
            val c0 = cpuExcept(relay.serverThread.id)
            val t0 = System.nanoTime()
            client.connect()
            assertTrue(counter.done.await(120, TimeUnit.SECONDS), "got ${counter.count.get()} of $frames")
            val t = System.nanoTime() - t0
            val a = allocatedExcept(relay.serverThread.id) - a0
            val c = cpuExcept(relay.serverThread.id) - c0
            val gcs = gcCount() - gc0
            client.disconnect()
            return Result(t / 1e6, c, a, gcs)
        }
    }

    /** Raw sockets, no relay client: which part of the pipeline costs what. Diagnostic, unique plain frames only. */
    @Test
    fun breakdown() {
        if (path == null || System.getenv("SOCKET_BREAKDOWN") == null) return
        val frames = events().map { """["EVENT","sub1",""".encodeToByteArray() + it + "]".encodeToByteArray() }
        val wire = wire(frames, false)
        val okhttp = OkHttpClient()
        val constant = NoticeMessage("x")

        class Plain(
            val parse: Boolean,
        ) : WebSocketListener {
            val count = AtomicInteger(0)
            val done = CountDownLatch(1)

            override fun onOpen(
                pingMillis: Int,
                compression: Boolean,
            ) {}

            override suspend fun onMessage(text: String) {
                if (parse) KiteReader.readMessage(text)
                if (count.incrementAndGet() == frames.size) done.countDown()
            }

            override fun onClosed(
                code: Int,
                reason: String,
            ) {}

            override fun onFailure(
                t: Throwable,
                code: Int?,
                response: String?,
            ) {}
        }

        class Bytes(
            val parse: Boolean,
        ) : Utf8WebSocketListener {
            val count = AtomicInteger(0)
            val done = CountDownLatch(1)

            override fun decodeUtf8(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Message = if (parse) KiteReader.readMessageUtf8(bytes, offset, length) else constant

            override suspend fun onDecodedMessage(
                msg: Message,
                frameBytes: Int,
            ) {
                if (count.incrementAndGet() == frames.size) done.countDown()
            }

            override fun onOpen(
                pingMillis: Int,
                compression: Boolean,
            ) {}

            override suspend fun onMessage(text: String) {}

            override fun onClosed(
                code: Int,
                reason: String,
            ) {}

            override fun onFailure(
                t: Throwable,
                code: Int?,
                response: String?,
            ) {}
        }

        fun run(
            name: String,
            make: (NormalizedRelayUrl) -> Pair<WebSocket, CountDownLatch>,
        ) {
            val cpus = ArrayList<Long>()
            val allocs = ArrayList<Long>()
            repeat(7) { r ->
                BulkRelay(wire, false).use { relay ->
                    val (socket, done) = make(relay.url)
                    val a0 = allocatedExcept(relay.serverThread.id)
                    val c0 = cpuExcept(relay.serverThread.id)
                    socket.connect()
                    assertTrue(done.await(120, TimeUnit.SECONDS))
                    if (r >= 2) {
                        cpus.add(cpuExcept(relay.serverThread.id) - c0)
                        allocs.add(allocatedExcept(relay.serverThread.id) - a0)
                    }
                    socket.disconnect()
                }
            }
            println("   %-58s CPU %6d ns/frame  %6d B/frame".format(name, cpus.sorted()[cpus.size / 2] / frames.size, allocs.sorted()[allocs.size / 2] / frames.size))
        }
        println("\n=== breakdown, ${frames.size} unique plain frames ===")
        repeat(2) {
            run("Basic: OkHttp framing + String, no parse") { u -> Plain(false).let { BasicOkHttpWebSocket(u, { okhttp }, it) to it.done } }
            run("Bytes: our framing + String (plain listener), no parse") { u -> Plain(false).let { OkHttpBytesWebSocket(u, { okhttp }, it) to it.done } }
            run("Bytes: our framing only (bytes listener, no parse)") { u -> Bytes(false).let { OkHttpBytesWebSocket(u, { okhttp }, it) to it.done } }
            run("Basic: OkHttp framing + String + readMessage") { u -> Plain(true).let { BasicOkHttpWebSocket(u, { okhttp }, it) to it.done } }
            run("Bytes: our framing + readMessageUtf8") { u -> Bytes(true).let { OkHttpBytesWebSocket(u, { okhttp }, it) to it.done } }
        }
        okhttp.dispatcher.executorService.shutdown()
    }

    @Test
    fun basicVersusBytes() {
        if (path == null) return
        val events = events()
        val unique = events.map { """["EVENT","sub1",""".encodeToByteArray() + it + "]".encodeToByteArray() }
        // Each run of 100 events arrives three times, as from three relays: 2 of every 3 are duplicates.
        val dupes = unique.chunked(100).flatMap { c -> c + c + c }
        val okhttp = OkHttpClient()
        val basic = BasicOkHttpWebSocket.Builder { okhttp }
        val bytes = OkHttpBytesWebSocket.Builder { okhttp }
        println("\n=== ${unique.size} unique frames, mean ${unique.sumOf { it.size } / unique.size} B ===")
        val workloads =
            listOf(
                Triple("unique", unique, null),
                Triple("3x duplicates", dupes, null),
                Triple("unique, paced 20 frames/ms", unique, 20),
            )
        for ((name, frames, perMs) in workloads) {
            for (deflate in listOf(false, true)) {
                val wire = wire(frames, deflate)
                val label = "$name, ${if (deflate) "permessage-deflate" else "plain"} (${frames.size} frames, ${wire.bytes.size / 1024} KiB on the wire)"
                println(" $label")
                val rounds = if (perMs == null) 5 else 3
                repeat(2) { once(basic, wire, deflate, frames.size, perMs) } // warm-up
                repeat(2) { once(bytes, wire, deflate, frames.size, perMs) }
                val results = mutableMapOf<String, MutableList<Result>>()
                repeat(rounds) {
                    results.getOrPut("BasicOkHttpWebSocket") { mutableListOf() }.add(once(basic, wire, deflate, frames.size, perMs))
                    results.getOrPut("OkHttpBytesWebSocket") { mutableListOf() }.add(once(bytes, wire, deflate, frames.size, perMs))
                }
                for ((socketName, rs) in results) {
                    val ms = rs.map { it.ms }.sorted()[rs.size / 2]
                    val alloc = rs.map { it.allocated }.sorted()[rs.size / 2]
                    val gcs = rs.map { it.gcs }.sorted()[rs.size / 2]
                    val cpu = rs.map { it.cpuNs }.sorted()[rs.size / 2]
                    println(
                        "   %-22s %8.1f ms wall %6.0f ns/frame | CPU, all threads: %6.0f ns/frame | %7d B alloc/frame  %4d GCs".format(
                            socketName,
                            ms,
                            ms * 1e6 / frames.size,
                            cpu.toDouble() / frames.size,
                            alloc / frames.size,
                            gcs,
                        ),
                    )
                }
            }
        }
        okhttp.dispatcher.executorService.shutdown()
    }
}
