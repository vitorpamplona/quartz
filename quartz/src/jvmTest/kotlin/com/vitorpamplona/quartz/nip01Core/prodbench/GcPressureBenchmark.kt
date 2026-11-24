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
import com.vitorpamplona.quartz.nip01Core.core.AddressSerializer
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.RelayLogger
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.stats.RelayReqStats
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CachingEventDecoder
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MessageDecoder
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip50Search.EventSearchMatcher
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.SeenIds
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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

        val contactLists = events.filterIsInstance<ContactListEvent>()
        if (contactLists.isNotEmpty()) {
            val pTags = contactLists.sumOf { it.tags.size }
            row("ContactListEvent.follows() (per p tag)", threadAlloc(contactLists.size) { i -> contactLists[i].follows() }, per = pTags)
            row("ContactListEvent.linkedPubKeys() (per tag)", threadAlloc(contactLists.size) { i -> contactLists[i].linkedPubKeys() }, per = pTags)
        }
        val profiles = events.filterIsInstance<MetadataEvent>()
        if (profiles.isNotEmpty()) {
            row("MetadataEvent.contactMetaData() (kind 0)", threadAlloc(profiles.size) { i -> profiles[i].contactMetaData() }, per = profiles.size)
        }
        if (profiles.isNotEmpty()) {
            // A keystroke of a cache-wide search: a fresh matcher, every profile checked.
            row(
                "search \"vitor nostr\" over kind 0 (per profile)",
                threadAlloc(20) {
                    val matcher = EventSearchMatcher("vitor nostr")
                    var hits = 0
                    for (e in profiles) if (matcher.match(e)) hits++
                    hits
                },
                per = 20 * profiles.size,
            )
        }
        val searchable = events.filterIsInstance<SearchableEvent>()
        row("indexableContent() (searchable kinds)", threadAlloc(searchable.size) { i -> searchable[i].indexableContent() }, per = searchable.size)
        row(
            "AddressSerializer.assemble (30023 + d)",
            threadAlloc(n) { i -> AddressSerializer.assemble(30023, events[i].pubKey, "my-article-slug-20c") },
        )

        // Large events: every write past the builder's kept size used to regrow it from 1K chars.
        val big = events.filter { it.toJson().length > 16 * 1024 }
        if (big.isNotEmpty()) {
            val bigChars = big.sumOf { it.toJson().length.toLong() }
            val bytes = threadAlloc(big.size) { i -> big[i].toJson() }
            println("  %-40s %9d B/op  %6.2fx its JSON (%d events > 16K chars)".format("Event -> JSON, events over 16K chars", bytes / big.size, bytes.toDouble() / bigChars, big.size))
            val tagBytes = threadAlloc(big.size) { i -> OptimizedJsonMapper.toJson(big[i].tags) }
            println("  %-40s %9d B/op".format("tags -> JSON (store insert), same events", tagBytes / big.size))
        }

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

    /** The hint-provider row split by kind and by method, to show where its bytes go. */
    @Test
    fun hintProvidersByKindAndMethod() {
        if (!enabled) return
        val events = corpus()
        val byKind =
            events
                .groupBy { it.kind }
                .entries
                .sortedByDescending { it.value.size }
                .take(14)
        val methods =
            listOf<Pair<String, (Event) -> Any?>>(
                "eventHints" to { e -> (e as? EventHintProvider)?.eventHints() },
                "linkedEventIds" to { e -> (e as? EventHintProvider)?.linkedEventIds() },
                "addressHints" to { e -> (e as? AddressHintProvider)?.addressHints() },
                "linkedAddressIds" to { e -> (e as? AddressHintProvider)?.linkedAddressIds() },
                "pubKeyHints" to { e -> (e as? PubKeyHintProvider)?.pubKeyHints() },
                "linkedPubKeys" to { e -> (e as? PubKeyHintProvider)?.linkedPubKeys() },
            )
        println("\n=== hint providers, B/event by kind and method ===")
        println("  %-7s %6s %s".format("kind", "count", methods.joinToString(" ") { "%16s".format(it.first) }) + "   total")
        var grand = 0L
        for ((kind, list) in byKind) {
            val cells = methods.map { (_, call) -> threadAlloc(list.size) { i -> call(list[i]) } }
            grand += cells.sum()
            println("  %-7d %6d %s".format(kind, list.size, cells.joinToString(" ") { "%16d".format(it / list.size) }) + "   %5d".format(cells.sum() / list.size))
        }
        println("  all listed kinds, B/event over the whole corpus: ${grand / events.size}")
    }

    /**
     * Bytes per EVENT frame that a client listener adds, driven straight through its
     * `onIncomingMessage` with real frames and a reused continuation (the harness's own cost is
     * the same for every row, so compare rows rather than read them as absolutes).
     */
    @Test
    fun relayListenersPerFrame() {
        if (!enabled) return
        val events = corpus()
        val relayUrl = RelayUrlNormalizer.normalizeOrNull("wss://relay.damus.io")!!
        val relay =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IRelayClient::class.java)) { _, m, _ ->
                if (m.name == "getUrl") relayUrl else null
            } as IRelayClient
        var captured: RelayConnectionListener? = null
        val client =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(INostrClient::class.java)) { _, m, args ->
                if (m.name == "addConnectionListener") captured = args[0] as RelayConnectionListener
                null
            } as INostrClient
        val frames = events.mapIndexed { i, e -> EventMessage("sub-${i % 8}", e) }
        val texts = frames.map { it.event.toJson() }
        val cont =
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {}
            }

        fun measure(
            label: String,
            listener: RelayConnectionListener,
        ) {
            val call = RelayConnectionListener::class.java.getMethod("onIncomingMessage", IRelayClient::class.java, String::class.java, Message::class.java, Continuation::class.java)
            val baseline =
                threadAlloc(frames.size) { i -> call.invoke(NoOpListener, relay, texts[i], frames[i], cont) }
            val bytes = threadAlloc(frames.size) { i -> call.invoke(listener, relay, texts[i], frames[i], cont) }
            println("  %-40s %9d B/frame (harness %d B/frame subtracted)".format(label, (bytes - baseline) / frames.size, baseline / frames.size))
        }

        println("\n=== client listeners, per EVENT frame ===")
        RelayLogger(client)
        measure("RelayLogger (logging off)", captured!!)
        RelayReqStats(client)
        measure("RelayReqStats", captured!!)

        // More subscriptions receiving events than the stats keep: the LRU churns.
        for (active in listOf(500, 2_000)) {
            val spread = events.mapIndexed { i, e -> EventMessage("sub-${i % active}", e) }
            RelayReqStats(client)
            val listener = captured!!
            val call = RelayConnectionListener::class.java.getMethod("onIncomingMessage", IRelayClient::class.java, String::class.java, Message::class.java, Continuation::class.java)
            val baseline = threadAlloc(spread.size) { i -> call.invoke(NoOpListener, relay, texts[i], spread[i], cont) }
            val bytes = threadAlloc(spread.size) { i -> call.invoke(listener, relay, texts[i], spread[i], cont) }
            println("  %-40s %9d B/frame".format("RelayReqStats, $active active subs", (bytes - baseline) / spread.size))
        }
    }

    private object NoOpListener : RelayConnectionListener

    /**
     * Bytes per frame through [CachingEventDecoder] over the raw capture, duplicates and all (it
     * spans many relays), with a fresh decoder so new events and duplicates mix as on a cold start.
     */
    @Test
    fun cachingDecoderPerFrame() {
        if (!enabled) return
        val stream =
            javaClass.classLoader?.getResourceAsStream(CORPUS)
                ?: throw IllegalStateException("corpus $CORPUS missing from the test classpath")
        val all = GZIPInputStream(stream).use { OptimizedJsonMapper.fromJsonToEventList(it.readBytes().toString(Charsets.UTF_8)) }
        // Subscription ids as clients name them: random, one length (Amethyst's newSubId() is 10
        // chars), and more of them open than a small table could hold.
        val rnd = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val subIds = List(40) { String(CharArray(10) { alphabet[rnd.nextInt(alphabet.length)] }) }
        val frames = all.take(50_000).mapIndexed { i, e -> "[\"EVENT\",\"${subIds[(i * 7 + i / 3) % subIds.size]}\",${e.toJson()}]" }

        // warm the JIT on throwaway decoders
        repeat(3) {
            val warm = CachingEventDecoder()
            for (f in frames) keep = warm.decode(f)
        }
        val decoder = CachingEventDecoder()
        val id = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(id)
        for (f in frames) keep = decoder.decode(f)
        val after = threads.getThreadAllocatedBytes(id)

        val plainBefore = threads.getThreadAllocatedBytes(id)
        for (f in frames) keep = MessageDecoder.Default.decode(f)
        val plainAfter = threads.getThreadAllocatedBytes(id)

        println("\n=== CachingEventDecoder over ${frames.size} raw frames: ${decoder.parsedCount} parsed, ${decoder.reusedCount} reused ===")
        println("  %-40s %9d B/frame".format("CachingEventDecoder.decode", (after - before) / frames.size))
        println("  %-40s %9d B/frame".format("full parse of every frame (reference)", (plainAfter - plainBefore) / frames.size))
    }
}
