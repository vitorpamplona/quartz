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
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import kotlin.random.Random

/**
 * A conformance battery for [IEventStore] implementations: load the same corpus into a reference
 * store (Quartz's SQLite `EventStore`) and a candidate (the filesystem store, geode's, a Vespa- or
 * Postgres-backed one), run every check against both, and diff what they answer: the result SET,
 * the newest-first ORDER, and NIP-45 COUNTs.
 *
 * Every check names the STORE-* rules it exercises (the `event-store-semantics` skill's
 * `store-rules.md`), so a mismatch reads as "this store diverges from STORE-D05", not as a bare
 * failed assertion. Nothing here depends on a test framework: [compare] returns a [Report], and
 * the caller decides whether a mismatch fails a test, a CI gate or a benchmark run.
 *
 * ```
 * val corpus = ConformanceCorpus.generate(ConformanceCorpus.Config(size = 5_000))
 * StoreConformance.load(reference, corpus)
 * StoreConformance.load(candidate, corpus)
 * val report = StoreConformance.compare(reference, candidate, StoreConformance.battery(corpus))
 * check(report.ok) { report.summary() }
 * ```
 *
 * NIP-50 `search` is not in the battery: NIP-50 orders hits "by relevance", which is the engine's
 * call, so two conforming stores legitimately rank (and typo-recall) differently. Everything here
 * is plain NIP-01 recall, where conforming stores answer identically.
 */
object StoreConformance {
    /** One check: the [filters] of a single REQ (or COUNT, when [countOnly]), and the rules it exercises. */
    class Check(
        val name: String,
        val filters: List<Filter>,
        val rules: List<String>,
        /** Compare only the NIP-45 count, not the events. */
        val countOnly: Boolean = false,
    ) {
        constructor(name: String, filter: Filter, rules: List<String>, countOnly: Boolean = false) :
            this(name, listOf(filter), rules, countOnly)
    }

    enum class MismatchKind {
        /** The two stores returned different events. */
        SET,

        /** The same events in a different order. */
        ORDER,

        /** Different NIP-45 counts. */
        COUNT,
    }

    class Mismatch(
        val check: Check,
        val kind: MismatchKind,
        val detail: String,
    ) {
        override fun toString() = "${kind.name.lowercase()}[${check.name}] (${check.rules.joinToString()}): $detail"
    }

    class Report(
        val checks: Int,
        val mismatches: List<Mismatch>,
    ) {
        val ok: Boolean get() = mismatches.isEmpty()

        /** The rules at least one mismatching check exercises: where to look first. */
        val suspectRules: Set<String> get() = mismatches.flatMapTo(LinkedHashSet()) { it.check.rules }

        /** A pass/fail line, then up to [limit] mismatches, one per line. */
        fun summary(limit: Int = 40): String =
            buildString {
                append(if (ok) "PASS" else "FAIL")
                    .append(": ")
                    .append(checks - mismatches.size)
                    .append('/')
                    .append(checks)
                    .append(" checks agree")
                if (!ok) append("; suspect rules: ").append(suspectRules.joinToString())
                mismatches.take(limit).forEach { append("\n  ").append(it) }
                if (mismatches.size > limit) append("\n  … ").append(mismatches.size - limit).append(" more")
            }
    }

    /**
     * Feeds [corpus] to [store] in order, [chunk] events per [IEventStore.batchInsert]. Outcomes
     * are not checked: a rejection (duplicate, deleted, superseded) is part of what the battery
     * then compares.
     */
    suspend fun load(
        store: IEventStore,
        corpus: List<Event>,
        chunk: Int = 500,
    ) {
        for (start in corpus.indices step chunk) {
            store.batchInsert(corpus.subList(start, minOf(start + chunk, corpus.size)))
        }
    }

    /** Runs every check of [checks] against [reference] and [candidate], already loaded alike. */
    suspend fun compare(
        reference: IEventStore,
        candidate: IEventStore,
        checks: List<Check>,
    ): Report {
        val mismatches = ArrayList<Mismatch>()
        for (check in checks) {
            if (check.countOnly) {
                val expected = reference.count(check.filters)
                val actual = candidate.count(check.filters)
                if (expected != actual) mismatches += Mismatch(check, MismatchKind.COUNT, "reference=$expected candidate=$actual")
                continue
            }
            val expected = reference.query<Event>(check.filters).map { it.id }
            val actual = candidate.query<Event>(check.filters).map { it.id }
            val expectedSet = expected.toSet()
            val actualSet = actual.toSet()
            if (expectedSet != actualSet) {
                val missing = expectedSet - actualSet
                val extra = actualSet - expectedSet
                mismatches +=
                    Mismatch(
                        check,
                        MismatchKind.SET,
                        "sizes reference=${expected.size} candidate=${actual.size}; missing ${missing.size} (${missing.take(3).joinToString()}), extra ${extra.size} (${extra.take(3).joinToString()})",
                    )
            } else if (expected != actual) {
                val at = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) } ?: -1
                mismatches += Mismatch(check, MismatchKind.ORDER, "same ${expected.size} events, first difference at index $at")
            }
        }
        return Report(checks.size, mismatches)
    }

    /**
     * The NIP-01 battery, drawn from [corpus] so it hits real authors, ids, tag values and time
     * ranges. Deterministic for a given corpus.
     */
    fun battery(corpus: List<Event>): List<Check> {
        if (corpus.isEmpty()) return emptyList()
        val authors = corpus.map { it.pubKey }.distinct().shuffled(Random(7))
        val ids = corpus.map { it.id }.distinct().shuffled(Random(8))
        val times = corpus.map { it.createdAt }.sorted()
        val tLo = times[times.size / 4]
        val tMid = times[times.size / 2]
        val tHi = times[times.size * 3 / 4]
        val pValues = tagValues(corpus, "p").shuffled(Random(9))
        val eValues = tagValues(corpus, "e").shuffled(Random(10))
        val kinds = listOf(0, 1, 3, 5, 6, 7, 10002, 30023)

        val checks = ArrayList<Check>()

        // Kind scans: recall, newest-first order and per-filter limits.
        for (k in kinds) {
            val rules = listOf("STORE-F08", "STORE-F12") + kindRules(k)
            checks += Check("kind=$k", Filter(kinds = listOf(k), limit = 500), rules)
            checks += Check("kind=$k limit=10", Filter(kinds = listOf(k), limit = 10), rules)
            checks += Check("count kind=$k", Filter(kinds = listOf(k)), listOf("STORE-C01") + kindRules(k), countOnly = true)
        }
        checks += Check("kinds=[1,7]", Filter(kinds = listOf(1, 7), limit = 300), listOf("STORE-F08"))
        checks += Check("limit=0", Filter(kinds = listOf(1), limit = 0), listOf("STORE-F12"))

        // Author timelines; at most one live version per replaceable coordinate.
        for (a in authors.take(15)) {
            checks += Check("author", Filter(authors = listOf(a), limit = 100), listOf("STORE-F02", "STORE-F08"))
            checks += Check("author+kind=1", Filter(authors = listOf(a), kinds = listOf(1), limit = 50), listOf("STORE-F02", "STORE-D01"))
            checks += Check("author+kind=0", Filter(authors = listOf(a), kinds = listOf(0)), listOf("STORE-W01"))
            checks += Check("author+kind=30023", Filter(authors = listOf(a), kinds = listOf(30023)), listOf("STORE-W02", "STORE-D02"))
            checks += Check("count author", Filter(authors = listOf(a)), listOf("STORE-C01"), countOnly = true)
        }
        checks += Check("authors x5", Filter(authors = authors.take(5), limit = 200), listOf("STORE-F02"))
        checks += Check("follow-feed authors x300", Filter(authors = authors.take(300), kinds = listOf(1, 6, 7), limit = 500), listOf("STORE-F02", "STORE-F08"))
        checks += Check("contact-sync authors x100", Filter(authors = authors.take(100), kinds = listOf(0, 3, 10002), limit = 300), listOf("STORE-W01"))
        checks += Check("count follow-feed", Filter(authors = authors.take(300), kinds = listOf(1, 6, 7)), listOf("STORE-C01"), countOnly = true)

        // Ids: point lookups (deleted and superseded ones included) and sets.
        for (id in ids.take(30)) checks += Check("id", Filter(ids = listOf(id)), listOf("STORE-F02", "STORE-D01", "STORE-D04", "STORE-W01"))
        checks += Check("ids x25", Filter(ids = ids.take(25)), listOf("STORE-F02"))
        checks += Check("ids x100", Filter(ids = ids.take(100)), listOf("STORE-F02"))

        // Tags: single values, value lists (OR), tagsAll (AND), byte-exact case.
        pValues.firstOrNull()?.let { checks += Check("#p", Filter(kinds = listOf(1), tags = mapOf("p" to listOf(it)), limit = 100), listOf("STORE-F03")) }
        eValues.firstOrNull()?.let { checks += Check("#e", Filter(tags = mapOf("e" to listOf(it)), limit = 100), listOf("STORE-F03")) }
        if (pValues.size >= 2) checks += Check("#p x${pValues.take(100).size}", Filter(tags = mapOf("p" to pValues.take(100)), limit = 300), listOf("STORE-F03"))
        if (eValues.size >= 2) checks += Check("#e x${eValues.take(50).size}", Filter(tags = mapOf("e" to eValues.take(50)), limit = 200), listOf("STORE-F03"))
        corpus.firstOrNull { e -> e.kind == 3 && e.tags.count { it.size > 1 && it[0] == "p" } >= 2 }?.let { list ->
            val both =
                list.tags
                    .filter { it.size > 1 && it[0] == "p" }
                    .map { it[1] }
                    .distinct()
                    .take(2)
            if (both.size == 2) checks += Check("tagsAll #p x2", Filter(kinds = listOf(3), tagsAll = mapOf("p" to both)), listOf("STORE-F03"))
        }
        tagValues(corpus, "t").firstOrNull { it != it.lowercase() }?.let {
            checks += Check("#t exact case", Filter(tags = mapOf("t" to listOf(it)), limit = 100), listOf("STORE-F03", "STORE-F06"))
            checks += Check("#t wrong case matches nothing", Filter(tags = mapOf("t" to listOf(it.lowercase())), limit = 100), listOf("STORE-F03", "STORE-F06"))
        }
        tagValues(corpus, "d").firstOrNull()?.let {
            checks += Check("#d", Filter(kinds = listOf(30023), tags = mapOf("d" to listOf(it))), listOf("STORE-F05", "STORE-W02"))
        }

        // Time windows, both bounds inclusive at an exact event timestamp.
        checks += Check("since", Filter(kinds = listOf(1), since = tMid, limit = 200), listOf("STORE-F01"))
        checks += Check("until", Filter(kinds = listOf(1), until = tMid, limit = 200), listOf("STORE-F01"))
        checks += Check("since=until", Filter(since = tMid, until = tMid), listOf("STORE-F01"))
        checks += Check("since+until window", Filter(since = tLo, until = tHi, limit = 300), listOf("STORE-F01"))
        checks += Check("authors since", Filter(authors = authors.take(3), since = tMid, limit = 100), listOf("STORE-F01", "STORE-F02"))

        // Multi-filter REQs: a deduplicated union, limits per filter.
        checks +=
            Check(
                "union kind=1 limit=20 + kind=7 limit=20",
                listOf(Filter(kinds = listOf(1), limit = 20), Filter(kinds = listOf(7), limit = 20)),
                listOf("STORE-F07"),
            )
        checks +=
            Check(
                "union overlapping",
                listOf(Filter(authors = authors.take(5), limit = 50), Filter(kinds = listOf(1), since = tMid, limit = 50)),
                listOf("STORE-F07"),
            )
        checks +=
            Check(
                "count union overlapping",
                listOf(Filter(authors = authors.take(5)), Filter(kinds = listOf(1), since = tMid)),
                listOf("STORE-C01", "STORE-F07"),
                countOnly = true,
            )

        // Aggregates: everything a deletion, supersession or rejected re-offer left behind.
        checks += Check("count all", Filter(), listOf("STORE-C01", "STORE-F10", "STORE-W01", "STORE-W02", "STORE-D01", "STORE-D05"), countOnly = true)
        checks += Check("count kind=1 limit=10", Filter(kinds = listOf(1), limit = 10), listOf("STORE-C01"), countOnly = true)
        return checks
    }

    private fun kindRules(kind: Int): List<String> =
        when (kind) {
            0, 3, 10002 -> listOf("STORE-W01")
            30023 -> listOf("STORE-W02", "STORE-D02")
            5 -> listOf("STORE-D01", "STORE-D05")
            else -> listOf("STORE-D01")
        }

    private fun tagValues(
        corpus: List<Event>,
        name: String,
    ): List<String> {
        val out = LinkedHashSet<String>()
        corpus.forEach { e -> e.tags.forEach { if (it.size > 1 && it[0] == name) out += it[1] } }
        return out.toList()
    }
}
