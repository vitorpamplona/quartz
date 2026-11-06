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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import java.io.File
import java.lang.management.ManagementFactory
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.sun.management.ThreadMXBean as SunThreadMXBean

/**
 * Frame bytes → `Message`, the way a socket that hands over UTF-8 would feed Kite (the byte
 * reader, and the Latin-1 view), against today's path, which decodes the frame to a `String` first (OkHttp's `readUtf8`) and parses that.
 * Both are timed from the bytes, so the decode is counted.
 *
 * Corpus: nostrJsonBench's `corpus/nostr-2024-startup.ndjson.gz` (what Amethyst reads at
 * startup, one event per line, as the relays sent them), passed as a path:
 *
 * `KITE_UTF8_CORPUS=…/nostr-2024-startup.ndjson.gz ./gradlew :quartz:jvmTest --tests "*.KiteUtf8Benchmark" -i`
 *
 * Skipped without it, so it never runs in CI.
 */
class KiteUtf8Benchmark {
    companion object {
        var sink: Any? = null
        private val mx = ManagementFactory.getThreadMXBean() as SunThreadMXBean
    }

    private val path = System.getenv("KITE_UTF8_CORPUS")

    private fun lines(): List<ByteArray> =
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

    private fun describe(e: Event) = "${e.id}|${e.pubKey}|${e.createdAt}|${e.kind}|${e.tags.map { it.toList() }}|<${e.content}>|${e.sig}"

    private fun run(
        label: String,
        frames: List<ByteArray>,
        block: (ByteArray) -> Any,
    ) {
        repeat(5) { for (f in frames) sink = block(f) }
        val times = LongArray(9)
        val allocs = LongArray(9)
        for (r in times.indices) {
            val a0 = mx.currentThreadAllocatedBytes
            val t0 = System.nanoTime()
            for (f in frames) sink = block(f)
            times[r] = System.nanoTime() - t0
            allocs[r] = mx.currentThreadAllocatedBytes - a0
        }
        times.sort()
        allocs.sort()
        val bytes = frames.sumOf { it.size.toLong() }
        val ns = times[times.size / 2].toDouble()
        println(
            "  %-44s %7.0f ns/frame %6.0f MB/s %7d B alloc/frame".format(
                label,
                ns / frames.size,
                bytes / ns * 1000,
                allocs[allocs.size / 2] / frames.size,
            ),
        )
    }

    @Test
    fun utf8VersusDecodedString() {
        if (path == null) return
        val events = lines()
        val frames = events.map { """["EVENT","sub1",""".encodeToByteArray() + it + "]".encodeToByteArray() }

        // Same result for every event of the corpus, both ways.
        for (k in events.indices) {
            val expected = KiteReader.readEvent(events[k].decodeToString())
            for (actual in listOf(KiteReader.readEventUtf8(events[k]), KiteReader.readEventLatin1View(events[k]))) {
                assertEquals(describe(expected), describe(actual), "event $k")
                assertTrue(expected.tags.contentDeepEquals(actual.tags))
            }
            for (msg in listOf(KiteReader.readMessageUtf8(frames[k]), KiteReader.readMessageLatin1View(frames[k]))) {
                assertEquals(describe(expected), describe((msg as EventMessage).event), "frame $k")
            }
        }

        val nonAscii = frames.filter { f -> f.any { it < 0 } }
        val ascii = frames.filter { f -> f.none { it < 0 } }
        val large = frames.filter { it.size >= 4096 }
        val strings = frames.map { it.decodeToString() }
        println(
            "\n=== ${frames.size} frames, mean ${frames.sumOf { it.size } / frames.size} B; " +
                "${nonAscii.size} non-ASCII, ${large.size} >= 4 KiB; all parse the same both ways ===",
        )
        for (round in 1..2) {
            for ((name, set) in listOf("all" to frames, "ASCII only" to ascii, "non-ASCII" to nonAscii, ">= 4 KiB" to large)) {
                println(" $name (round $round)")
                run("decode only: String(bytes, UTF_8)", set) { it.decodeToString() }
                run("today: decode + readMessage(String)", set) { KiteReader.readMessage(it.decodeToString()) }
                run("Latin-1 view: readMessageLatin1View", set) { KiteReader.readMessageLatin1View(it) }
                run("byte reader (SWAR): readMessageUtf8", set) { KiteReader.readMessageUtf8(it) }
            }
            // The number nostrJsonBench reports: the String already built, parse only.
            var k = 0
            run("[ref] readMessage(prebuilt String), all", frames) { KiteReader.readMessage(strings[k++ % strings.size]) }
        }
    }
}
