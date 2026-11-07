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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.Utf8WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.OkHttpBytesWebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.ScriptedRelay
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
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

    /**
     * A relay that answers the handshake, writes [wire] (as fast as the socket takes it, or
     * [perMs] frames per millisecond, as a network delivers them), then holds the connection
     * until the client hangs up.
     */
    private fun bulkRelay(
        wire: Wire,
        deflate: Boolean,
        perMs: Int? = null,
    ) = ScriptedRelay(extensions = if (deflate) "permessage-deflate" else null) {
        val bytes = wire.bytes
        if (perMs == null) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(64 * 1024, bytes.size - off)
                raw(bytes, off, n)
                off += n
            }
        } else {
            var off = 0
            var k = 0
            while (k < wire.ends.size) {
                val last = minOf(wire.ends.size, k + perMs) - 1
                raw(bytes, off, wire.ends[last] - off)
                off = wire.ends[last]
                k = last + 1
                Thread.sleep(1)
            }
        }
        awaitHangUp()
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

    private fun gcMillis() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionTime) }

    private fun cpuExcept(skip: Set<Long>): Long {
        val ids = mx.allThreadIds.filter { it !in skip }.toLongArray()
        return mx.getThreadCpuTime(ids).filter { it > 0 }.sum()
    }

    private fun allocatedExcept(skip: Set<Long>): Long {
        val ids = mx.allThreadIds.filter { it !in skip }.toLongArray()
        return mx.getThreadAllocatedBytes(ids).filter { it > 0 }.sum()
    }

    private val sockets: List<Pair<String, (OkHttpClient) -> WebsocketBuilder>> =
        listOf(
            "BasicOkHttpWebSocket" to { okhttp -> BasicOkHttpWebSocket.Builder { okhttp } },
            "OkHttpBytesWebSocket" to { okhttp -> OkHttpBytesWebSocket.Builder { okhttp } },
        )

    private fun corpusFrames(subId: (Int) -> String = { "sub1" }): List<ByteArray> = events().mapIndexed { i, e -> """["EVENT","${subId(i)}",""".encodeToByteArray() + e + "]".encodeToByteArray() }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    /**
     * A multi-relay startup: [relays] relays each send the same corpus at once to one client per
     * relay, sharing one [CachingEventDecoder], so (relays - 1) of every relays copies are
     * duplicates, caught by whichever copy was parsed first. Flooded and paced, plain and
     * deflate. CPU and allocation leave out every server thread.
     */
    @Test
    fun manyRelays() {
        if (path == null || System.getenv("SOCKET_DECISION") == null) return
        val relays = 8
        val frames = corpusFrames()
        val okhttp = OkHttpClient()
        println("\n=== $relays relays x ${frames.size} frames (the same events), shared CachingEventDecoder ===")
        for (perMs in listOf<Int?>(null, 20)) {
            for (deflate in listOf(false, true)) {
                val wire = wire(frames, deflate)
                println(" ${if (perMs == null) "flooded" else "paced $perMs frames/ms per relay"}, ${if (deflate) "permessage-deflate" else "plain"}")
                val results = sockets.associate { it.first to ArrayList<DoubleArray>() }
                repeat(if (perMs == null) 6 else 4) { round ->
                    for ((name, builder) in sockets) {
                        val servers = List(relays) { bulkRelay(wire, deflate, perMs) }
                        try {
                            val skip = servers.map { it.serverThread.id }.toSet()
                            val counter = Counter(relays * frames.size)
                            val decoder = CachingEventDecoder()
                            val clients = servers.map { BasicRelayClient(it.url, builder(okhttp), counter, decoder = decoder) }
                            System.gc()
                            val gc0 = gcCount()
                            val gt0 = gcMillis()
                            val a0 = allocatedExcept(skip)
                            val c0 = cpuExcept(skip)
                            val t0 = System.nanoTime()
                            clients.forEach { it.connect() }
                            assertTrue(counter.done.await(300, TimeUnit.SECONDS), "got ${counter.count.get()}")
                            val t = System.nanoTime() - t0
                            val total = (relays * frames.size).toDouble()
                            val row =
                                doubleArrayOf(
                                    t / 1e6,
                                    (cpuExcept(skip) - c0) / total,
                                    (allocatedExcept(skip) - a0) / total,
                                    (gcCount() - gc0).toDouble(),
                                    (gcMillis() - gt0).toDouble(),
                                    decoder.reusedCount * 100.0 / total,
                                )
                            if (round >= 1) results.getValue(name).add(row) // the first round warms up
                            clients.forEach { it.disconnect() }
                        } finally {
                            servers.forEach { it.close() }
                        }
                    }
                }
                for ((name, rows) in results) {
                    fun col(k: Int) = median(rows.map { it[k] })
                    println(
                        "   %-22s %8.0f ms wall | CPU %6.0f ns/frame | %6.0f B alloc/frame | %3.0f GCs, %4.0f ms GC | %4.1f%% reused".format(
                            name,
                            col(0),
                            col(1),
                            col(2),
                            col(3),
                            col(4),
                            col(5),
                        ),
                    )
                }
            }
        }
        okhttp.dispatcher.executorService.shutdown()
    }

    /**
     * Delivery latency: one relay paces frames at 20 per ms; for each frame, the time from the
     * relay's write to the listener's call. The batching of [OkHttpBytesWebSocket] must not add to it.
     */
    @Test
    fun latency() {
        if (path == null || System.getenv("SOCKET_DECISION") == null) return
        val frames = corpusFrames { "s$it" }
        val okhttp = OkHttpClient()
        println("\n=== delivery latency, ${frames.size} frames paced at 20/ms, relay write -> listener ===")
        for (deflate in listOf(false, true)) {
            val wire = wire(frames, deflate)
            println(" ${if (deflate) "permessage-deflate" else "plain"}")
            val results = sockets.associate { it.first to ArrayList<DoubleArray>() }
            repeat(4) { round ->
                for ((name, builder) in sockets) {
                    val sentAt = LongArray(frames.size)
                    val gotAt = LongArray(frames.size)
                    val relay =
                        ScriptedRelay(extensions = if (deflate) "permessage-deflate" else null) {
                            var off = 0
                            var k = 0
                            while (k < wire.ends.size) {
                                val last = minOf(wire.ends.size, k + 20) - 1
                                raw(wire.bytes, off, wire.ends[last] - off)
                                val now = System.nanoTime()
                                for (x in k..last) sentAt[x] = now
                                off = wire.ends[last]
                                k = last + 1
                                Thread.sleep(1)
                            }
                            awaitHangUp()
                        }
                    relay.use {
                        val done = CountDownLatch(frames.size)
                        val listener =
                            object : RelayConnectionListener {
                                fun got(msg: Message) {
                                    if (msg is EventMessage) {
                                        gotAt[msg.subId.substring(1).toInt()] = System.nanoTime()
                                        done.countDown()
                                    }
                                }

                                override suspend fun onIncomingMessage(
                                    relay: IRelayClient,
                                    msgStr: String,
                                    msg: Message,
                                ) = got(msg)

                                override suspend fun onIncomingMessage(
                                    relay: IRelayClient,
                                    msg: Message,
                                    frameBytes: Int,
                                ) = got(msg)
                            }
                        val client = BasicRelayClient(relay.url, builder(okhttp), listener, decoder = CachingEventDecoder())
                        client.connect()
                        assertTrue(done.await(120, TimeUnit.SECONDS))
                        client.disconnect()
                        val lat = LongArray(frames.size) { gotAt[it] - sentAt[it] }.sorted()
                        if (round >= 1) {
                            results.getValue(name).add(
                                doubleArrayOf(lat[lat.size / 2] / 1e3, lat[lat.size * 99 / 100] / 1e3, lat[lat.size * 999 / 1000] / 1e3, lat.last() / 1e3),
                            )
                        }
                    }
                }
            }
            for ((name, rows) in results) {
                fun col(k: Int) = median(rows.map { it[k] })
                println("   %-22s p50 %6.0f us | p99 %6.0f us | p99.9 %6.0f us | max %7.0f us".format(name, col(0), col(1), col(2), col(3)))
            }
        }
        okhttp.dispatcher.executorService.shutdown()
    }

    /**
     * What a backlog holds: the listener is stuck on the first message while the relay sends the
     * whole corpus, so every frame waits in the socket's delivery queue. BasicOkHttpWebSocket
     * queues each frame's String, OkHttpBytesWebSocket each parsed message. Heap used after a GC,
     * against before the connect, per frame.
     */
    @Test
    fun backlog() {
        if (path == null || System.getenv("SOCKET_DECISION") == null) return
        val frames = corpusFrames()
        val okhttp = OkHttpClient()
        val memory = ManagementFactory.getMemoryMXBean()

        fun heapAfterGc(): Long {
            repeat(3) {
                System.gc()
                Thread.sleep(100)
            }
            return memory.heapMemoryUsage.used
        }
        println("\n=== backlog: ${frames.size} frames queued behind a stuck listener ===")
        for (deflate in listOf(false, true)) {
            val wire = wire(frames, deflate)
            println(" ${if (deflate) "permessage-deflate" else "plain"}")
            val results = sockets.associate { it.first to ArrayList<Double>() }
            repeat(4) { round ->
                for ((name, builder) in sockets) {
                    val written = CountDownLatch(1)
                    val relay =
                        ScriptedRelay(extensions = if (deflate) "permessage-deflate" else null) {
                            raw(wire.bytes)
                            written.countDown()
                            awaitHangUp()
                        }
                    relay.use {
                        val release = CountDownLatch(1)
                        val counter = Counter(frames.size)
                        val stuck =
                            object : RelayConnectionListener {
                                override suspend fun onIncomingMessage(
                                    relay: IRelayClient,
                                    msgStr: String,
                                    msg: Message,
                                ) {
                                    release.await()
                                    counter.onIncomingMessage(relay, msgStr, msg)
                                }

                                override suspend fun onIncomingMessage(
                                    relay: IRelayClient,
                                    msg: Message,
                                    frameBytes: Int,
                                ) {
                                    release.await()
                                    counter.onIncomingMessage(relay, msg, frameBytes)
                                }
                            }
                        val decoder = CachingEventDecoder(capacity = 1) // no cache to keep events alive
                        val client = BasicRelayClient(relay.url, builder(okhttp), stuck, decoder = decoder)
                        val before = heapAfterGc()
                        client.connect()
                        assertTrue(written.await(60, TimeUnit.SECONDS))
                        Thread.sleep(2_000) // let the client read what the relay wrote
                        val held = heapAfterGc() - before
                        release.countDown()
                        assertTrue(counter.done.await(120, TimeUnit.SECONDS))
                        client.disconnect()
                        if (round >= 1) results.getValue(name).add(held.toDouble() / frames.size)
                    }
                }
            }
            for ((name, rows) in results) println("   %-22s %6.0f B held per queued frame".format(name, median(rows)))
        }
        okhttp.dispatcher.executorService.shutdown()
    }

    private fun once(
        builder: WebsocketBuilder,
        wire: Wire,
        deflate: Boolean,
        frames: Int,
        perMs: Int? = null,
    ): Result {
        bulkRelay(wire, deflate, perMs).use { relay ->
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
                bulkRelay(wire, false).use { relay ->
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
