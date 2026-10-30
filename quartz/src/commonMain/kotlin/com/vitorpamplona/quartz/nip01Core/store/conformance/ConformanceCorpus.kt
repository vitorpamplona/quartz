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
package com.vitorpamplona.quartz.nip01Core.store.conformance

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.random.Random

/**
 * A reproducible, relay-shaped corpus for [StoreConformance]: every store under comparison is fed
 * the SAME [Event] instances in the same order, so a difference in what they answer is the
 * store's.
 *
 * Deterministic for a given [Config] (the seed fixes every id, author, timestamp and kind), and
 * built to run the write paths that differ between implementations, not just a flat stream of
 * inserts:
 *
 * - replaceable (0, 3, 10002) and addressable (30023) kinds revisit earlier coordinates, so
 *   supersession runs (STORE-W01, W02);
 * - kind 5s delete a prior event of the same author by id (D01), a prior article by address
 *   (D02), or a prior kind 5, which must have no effect (D05);
 * - earlier events are re-offered, duplicates and deleted ones alike (D04, W08);
 * - mixed-case hashtags keep byte-exact tag matching honest (F03).
 *
 * `created_at` advances by at least one second per event, so newest-first is a total order and an
 * ORDER mismatch is never a same-second tie (F08). Events go through [EventFactory], so each is its
 * typed subclass, and carry a 128-hex but fake signature: stores never verify (that is ingress's
 * job), and a signed-shaped event reads back the same from every engine.
 */
object ConformanceCorpus {
    class Config(
        val size: Int,
        val authorCount: Int = (size / 20).coerceIn(10, 50_000),
        val seed: Long = 42L,
        /** The first `created_at`; each event advances the clock by 1–7 seconds. */
        val startTime: Long = 1_700_000_000L,
        /** High nibble of every event id: vary it to mint an id-disjoint second corpus. */
        val idBand: Long = 0x1L,
    )

    private enum class Shape(
        val weight: Int,
    ) {
        NOTE(50),
        REACTION(25),
        REPOST(4),
        METADATA(4),
        CONTACTS(3),
        DELETION(4),
        ADDRESS_DELETION(1),
        DELETION_OF_DELETION(1),
        REOFFER(2),
        RELAY_LIST(2),
        LONG_FORM(3),
    }

    private val TOTAL_WEIGHT = Shape.entries.sumOf { it.weight }

    /** Words in note and article content, so a search has real terms to match. */
    private val VOCAB =
        (
            "nostr bitcoin lightning coffee freedom relay zap note purple ostrich " +
                "signal privacy sovereign mesh garden protocol client vespa quartz search " +
                "trust graph web ranking payload consensus block mempool node wallet key"
        ).split(" ")

    /** [Config.size] events, deterministic for [config]. Re-offers repeat an earlier instance. */
    fun generate(config: Config): List<Event> {
        val rnd = Random(config.seed)
        val authors = List(config.authorCount) { hex64(0xAL, it.toLong()) }
        val recentByAuthor = HashMap<HexKey, ArrayDeque<HexKey>>()
        val deletionsByAuthor = HashMap<HexKey, ArrayDeque<HexKey>>()
        val slugsByAuthor = HashMap<HexKey, MutableList<String>>()
        val recent = ArrayDeque<Event>()

        val out = ArrayList<Event>(config.size)
        var clock = config.startTime
        var counter = 0L

        while (out.size < config.size) {
            clock += rnd.nextInt(1, 8)
            val author = authors[rnd.nextInt(authors.size)]
            val id = hex64(config.idBand, counter++)
            val shape = pick(rnd)

            if (shape == Shape.REOFFER && recent.isNotEmpty()) {
                out += recent.elementAt(rnd.nextInt(recent.size))
                continue
            }

            val (kind, tags, content) =
                when (shape) {
                    Shape.REACTION -> {
                        Triple(7, targetTags(rnd, recent), if (rnd.nextBoolean()) "+" else "-")
                    }

                    Shape.REPOST -> {
                        Triple(6, targetTags(rnd, recent), "")
                    }

                    Shape.METADATA -> {
                        Triple(0, emptyArray(), "{\"name\":\"${VOCAB[rnd.nextInt(VOCAB.size)]}${rnd.nextInt(1000)}\",\"about\":\"${sentence(rnd, 4, 10)}\"}")
                    }

                    Shape.CONTACTS -> {
                        Triple(3, Array(5 + rnd.nextInt(40)) { arrayOf("p", authors[rnd.nextInt(authors.size)]) }, "")
                    }

                    Shape.RELAY_LIST -> {
                        Triple(10002, arrayOf(arrayOf("r", "wss://relay.example.com"), arrayOf("r", "wss://nos.lol")), "")
                    }

                    Shape.LONG_FORM -> {
                        val slugs = slugsByAuthor.getOrPut(author) { ArrayList() }
                        val d =
                            if (slugs.isNotEmpty() && rnd.nextInt(100) < 40) {
                                slugs[rnd.nextInt(slugs.size)]
                            } else {
                                "slug-${VOCAB[rnd.nextInt(VOCAB.size)]}-${rnd.nextInt(10_000)}".also { slugs += it }
                            }
                        Triple(30023, arrayOf(arrayOf("d", d), arrayOf("title", sentence(rnd, 2, 5))), sentence(rnd, 40, 120))
                    }

                    Shape.DELETION -> {
                        recentByAuthor[author]?.lastOrNull()?.let { Triple(5, arrayOf(arrayOf("e", it)), "deleted") } ?: note(rnd, recent)
                    }

                    Shape.ADDRESS_DELETION -> {
                        slugsByAuthor[author]?.lastOrNull()?.let { Triple(5, arrayOf(arrayOf("a", "30023:$author:$it")), "deleted") } ?: note(rnd, recent)
                    }

                    Shape.DELETION_OF_DELETION -> {
                        deletionsByAuthor[author]?.lastOrNull()?.let { Triple(5, arrayOf(arrayOf("e", it)), "undo") } ?: note(rnd, recent)
                    }

                    Shape.NOTE, Shape.REOFFER -> {
                        note(rnd, recent)
                    }
                }

            val event = EventFactory.create<Event>(id, author, clock, kind, tags, content, id + id)
            out += event

            val history = if (kind == 5) deletionsByAuthor else recentByAuthor
            val queue = history.getOrPut(author) { ArrayDeque() }
            queue.addLast(id)
            if (queue.size > 8) queue.removeFirst()
            recent.addLast(event)
            if (recent.size > 4096) recent.removeFirst()
        }
        return out
    }

    private fun note(
        rnd: Random,
        recent: ArrayDeque<Event>,
    ): Triple<Int, Array<Array<String>>, String> {
        val tags = ArrayList<Array<String>>()
        // ~30% are replies: e-tag a prior event and p-tag its author.
        if (rnd.nextInt(100) < 30 && recent.isNotEmpty()) {
            val target = recent.elementAt(rnd.nextInt(recent.size))
            tags += arrayOf("e", target.id)
            tags += arrayOf("p", target.pubKey)
        }
        // ~20% carry a Capitalized hashtag whose lowercase form never occurs: tag matching is
        // byte-exact, so a lowercased filter must match nothing on every store.
        if (rnd.nextInt(100) < 20) {
            tags += arrayOf("t", VOCAB[rnd.nextInt(VOCAB.size)].replaceFirstChar { it.uppercaseChar() })
        }
        return Triple(1, tags.toTypedArray(), sentence(rnd, 6, 24))
    }

    private fun targetTags(
        rnd: Random,
        recent: ArrayDeque<Event>,
    ): Array<Array<String>> {
        if (recent.isEmpty()) return emptyArray()
        val target = recent.elementAt(rnd.nextInt(recent.size))
        return arrayOf(arrayOf("e", target.id), arrayOf("p", target.pubKey))
    }

    private fun pick(rnd: Random): Shape {
        var r = rnd.nextInt(TOTAL_WEIGHT)
        for (shape in Shape.entries) {
            r -= shape.weight
            if (r < 0) return shape
        }
        return Shape.NOTE
    }

    private fun sentence(
        rnd: Random,
        min: Int,
        max: Int,
    ): String {
        val n = min + rnd.nextInt((max - min).coerceAtLeast(1))
        return (0 until n).joinToString(" ") { VOCAB[rnd.nextInt(VOCAB.size)] }
    }

    private fun hex64(
        band: Long,
        n: Long,
    ): String {
        val tail = n.toString(16)
        val head = band.toString(16)
        return head + "0".repeat(64 - head.length - tail.length) + tail
    }
}
