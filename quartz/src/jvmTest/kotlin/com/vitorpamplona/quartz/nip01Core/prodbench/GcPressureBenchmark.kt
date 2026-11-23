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

import com.sun.management.ThreadMXBean
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.utils.SeenIds
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Bytes allocated per event by each library stage the ingest-and-render path runs, measured
 * the same way as [IngestAllocationBenchmark] (`getThreadAllocatedBytes`, warm), but over the
 * stages that one leaves out: serialization, the id hash, filter matching, hint extraction,
 * the dedup set, REQ serialization and the SQLite store's insert and read paths.
 *
 * The store runs on its own threads, so its rows count every thread's allocation
 * (`getTotalThreadAllocatedBytes`), which includes whatever else the JVM did meanwhile: read
 * them as upper bounds.
 *
 * Opt-in: `./gradlew :quartz:jvmTest --tests "*.GcPressureBenchmark" -PgcBench=1 -i`
 */
class GcPressureBenchmark {
    companion object {
        const val CORPUS = "nostr_vitor_startup_data.json.gz"
        const val EVENTS = 20_000

        private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean

        var keep: Any? = null
    }

    private val enabled = System.getProperty("gcBench") != null

    private fun corpus(): List<Event> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(CORPUS)
                ?: throw IllegalStateException("corpus $CORPUS missing from the test classpath")
        val all = GZIPInputStream(stream).use { OptimizedJsonMapper.fromJsonToEventList(it.readBytes().toString(Charsets.UTF_8)) }
        val seen = HashSet<String>(EVENTS * 2)
        val out = ArrayList<Event>(EVENTS)
        for (e in all) {
            if (out.size >= EVENTS) break
            if (seen.add(e.id)) out.add(e)
        }
        return out
    }

    private fun threadAlloc(
        reps: Int,
        block: (Int) -> Any?,
    ): Long {
        var sink: Any? = null
        repeat(3) { for (i in 0 until reps) sink = block(i) }
        keep = sink
        val id = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(id)
        for (i in 0 until reps) sink = block(i)
        val after = threads.getThreadAllocatedBytes(id)
        keep = sink
        return after - before
    }

    private fun totalAlloc(block: () -> Unit): Long {
        val before = threads.totalThreadAllocatedBytes
        block()
        return threads.totalThreadAllocatedBytes - before
    }

    @Test
    fun allocationPerStage() {
        if (!enabled) return
        val events = corpus()
        val n = events.size
        val eventJson = events.map { it.toJson() }
        val wire = eventJson.sumOf { it.length.toLong() }
        println("\n=== corpus: $n events, mean ${wire / n} chars/event ===")

        fun row(
            label: String,
            bytes: Long,
            per: Int = n,
        ) = println("  %-40s %9d B/op  %6.2fx mean event".format(label, bytes / per, (bytes.toDouble() / per) / (wire.toDouble() / n)))

        row("Event -> JSON (toJson)", threadAlloc(n) { i -> events[i].toJson() })
        row(
            "id hash (hashIdBytes)",
            threadAlloc(n) { i ->
                val e = events[i]
                EventHasher.hashIdBytes(e.pubKey, e.createdAt, e.kind, e.tags, e.content)
            },
        )
        row(
            "id hash (hashId, hex)",
            threadAlloc(n) { i ->
                val e = events[i]
                EventHasher.hashId(e.pubKey, e.createdAt, e.kind, e.tags, e.content)
            },
        )

        // A follow feed: 500 authors, notes and reposts. And a notification filter: #p of one user.
        val authors = events.map { it.pubKey }.distinct().take(500)
        val me =
            events
                .flatMap { e -> e.tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] } }
                .groupingBy { it }
                .eachCount()
                .maxBy { it.value }
                .key
        val follow = Filter(authors = authors, kinds = listOf(1, 6, 16, 20, 30023))
        val notif = Filter(kinds = listOf(1, 6, 7, 9735, 1111), tags = mapOf("p" to listOf(me)))
        row("Filter.match (500 authors)", threadAlloc(n) { i -> follow.match(events[i]) })
        row("Filter.match (#p)", threadAlloc(n) { i -> notif.match(events[i]) })

        row(
            "hint providers (all three, typed)",
            threadAlloc(n) { i ->
                val e = events[i]
                var c = 0
                if (e is EventHintProvider) c += e.eventHints().size + e.linkedEventIds().size
                if (e is PubKeyHintProvider) c += e.pubKeyHints().size + e.linkedPubKeys().size
                if (e is AddressHintProvider) c += e.addressHints().size + e.linkedAddressIds().size
                c
            },
        )

        row(
            "SeenIds.add (fresh set per 20k)",
            threadAlloc(1) {
                val seen = SeenIds()
                for (e in events) seen.add(e.id)
                seen
            },
            per = n,
        )

        val req = ReqCmd("sub-1", listOf(follow, notif))
        row("ReqCmd(2 filters, 500 authors).toJson", threadAlloc(200) { OptimizedJsonMapper.toJson(req) }, per = 200)

        // ---- SQLite store, in memory ----
        val store = EventStore(dbName = null)
        try {
            val insert =
                totalAlloc {
                    runBlocking { for (chunk in events.chunked(500)) store.batchInsert(chunk) }
                }
            row("store.batchInsert (500/batch)", insert)

            val store2 = EventStore(dbName = null)
            try {
                val single = events.take(2000)
                val ins1 =
                    totalAlloc {
                        runBlocking {
                            for (e in single) {
                                // older replaceables are rejected with an exception; that path is part of the cost
                                try {
                                    store2.insert(e)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                row("store.insert (one by one)", ins1, per = single.size)
            } finally {
                store2.close()
            }

            var rows = 0
            val read =
                totalAlloc {
                    runBlocking {
                        repeat(20) { store.query<Event>(Filter(kinds = listOf(1), limit = 500)) { rows++ } }
                    }
                }
            row("store.query kinds=1 limit 500 (per row)", read, per = rows)

            rows = 0
            val readFollow =
                totalAlloc {
                    runBlocking { repeat(10) { store.query<Event>(follow.copy(limit = 500)) { rows++ } } }
                }
            row("store.query follow feed (per row)", readFollow, per = maxOf(rows, 1))

            rows = 0
            val readNotif =
                totalAlloc {
                    runBlocking { repeat(10) { store.query<Event>(notif.copy(limit = 500)) { rows++ } } }
                }
            row("store.query #p notifications (per row)", readNotif, per = maxOf(rows, 1))
        } finally {
            store.close()
        }
    }
}
