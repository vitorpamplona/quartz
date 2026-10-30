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
package com.vitorpamplona.quartz.nip50Search

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.fastAny

/**
 * Matches an event against a NIP-50 `search` string, in memory, without an index.
 *
 * Built once per query and reused across every event of a scan: the string is parsed with
 * [SearchQuery.parse] here, not per event, and `match` allocates nothing at all.
 *
 * ## What it matches
 *
 * Everything [SearchQuery] parses, against the event's indexable fields — the same text a store's
 * full-text index holds ([SearchableEvent.forEachIndexableField] is the read side of
 * [SearchableEvent.indexableContent]), so local search and a store or relay agree on what an event
 * can be found by. A tag value is searched only when the kind indexes it: a hashtag, an id, a URL
 * or a data label the kind leaves out of its index is left out here too. A kind that is not a
 * [SearchableEvent] has no indexable fields, so it falls back to its tag values and content.
 *
 * - **Terms** ([SearchQuery.terms]) are ANDed, and each is a case-insensitive SUBSTRING of some
 *   field. Substring rather than token because that is what Amethyst's local search has always done
 *   (`content.contains(text, true)`), and switching to tokens would silently stop matching
 *   mid-word queries; AND rather than one literal phrase because that is what a relay does with
 *   the same string.
 * - **Phrases** (`"nostr apps"`, [SearchQuery.phrases]) are required too, each as a WORD RUN in one
 *   field: its words adjacent and in order, case-insensitive, whatever punctuation or whitespace
 *   sits between them (`Nostr-Apps` holds `"nostr apps"`), each word whole (`"nostr app"` is not in
 *   `nostr apps`). A word is a run of letters and digits. A phrase with no word in it (`"⚡"`) is
 *   matched as its literal text instead.
 * - **Exclusions** (`-spam`, `-"hello world"`, [SearchQuery.notTerms], [SearchQuery.notPhrases])
 *   drop an event when any field holds them as a word run, by the phrase rule: `-art` drops a note
 *   about art, not one about a start or an artist, and `-don't` drops the run `don t`. An
 *   exclusions-only search is everything minus the excluded words.
 *
 * That is the reading a token index gives the same grammar (vespa-eventstore's live gate judges
 * phrases and exclusions the same way), minus any one engine's tokenizer: a word here is simply a
 * letter/digit run.
 *
 * Unsupported NIP-50 extensions are ignored rather than treated as terms, per the spec, so a
 * search that is nothing but extensions matches everything rather than nothing.
 *
 * ## What it does not do
 *
 * No relevance score. A relay ranks bm25-first; this answers only yes or no, and any ordering is
 * the caller's to apply afterwards.
 *
 * ## Thread confinement
 *
 * An instance reuses one visitor across every event it is asked about, which is what keeps the
 * scan allocation-free — a lambda written inline would capture the term and allocate once per
 * event, defeating the whole point. That reuse makes an instance **single-threaded**: build one
 * per scan, do not share it between coroutines.
 */
class EventSearchMatcher(
    search: String?,
    /**
     * For a kind that is not a [SearchableEvent]: tag names whose values are not worth searching —
     * `p`/`e`/`a` hold hex ids that only ever match by accident, `client` and `alt` hold text the
     * author did not write.
     */
    private val exceptTagNames: Set<String> = DEFAULT_EXCLUDED_TAGS,
) {
    private val query = SearchQuery.parse(search)

    private val terms: Array<String> = splitTerms(query.terms)

    private val phrases: Array<WordRun> = Array(query.phrases.size) { WordRun.of(query.phrases[it]) }

    private val exclusions: Array<WordRun> =
        (query.notTerms.map(WordRun::of) + query.notPhrases.map(WordRun::of)).toTypedArray()

    /** True when this matcher constrains nothing, so callers can skip the walk entirely. */
    val isEmpty: Boolean get() = terms.isEmpty() && phrases.isEmpty() && exclusions.isEmpty()

    fun match(event: Event): Boolean {
        for (i in terms.indices) {
            if (!holds(event, terms[i], null)) return false
        }
        for (i in phrases.indices) {
            if (!holds(event, null, phrases[i])) return false
        }
        for (i in exclusions.indices) {
            if (holds(event, null, exclusions[i])) return false
        }
        return true
    }

    /** Reused for every field of every event; see the thread-confinement note on the class. */
    private val visitor = FieldVisitor()

    /** Whether any searched field of [event] holds [term] as a substring, or [run] as a word run. */
    private fun holds(
        event: Event,
        term: String?,
        run: WordRun?,
    ): Boolean {
        if (event is SearchableEvent) return visitor.matches(event, term, run)
        return fieldHolds(event.content, term, run) ||
            event.tags.fastAny { it.size > 1 && it[0] !in exceptTagNames && fieldHolds(it[1], term, run) }
    }

    /**
     * Carries the term or run into the walk on a field rather than in a closure, so no lambda is
     * allocated per event, and stops the walk at the first field that holds it.
     */
    private class FieldVisitor : IndexableFieldVisitor {
        private var term: String? = null
        private var run: WordRun? = null
        private var found = false

        fun matches(
            event: SearchableEvent,
            term: String?,
            run: WordRun?,
        ): Boolean {
            this.term = term
            this.run = run
            found = false
            event.forEachIndexableField(this)
            return found
        }

        override fun visit(field: String?): Boolean {
            if (field != null && fieldHolds(field, term, run)) {
                found = true
                return false
            }
            return true
        }
    }

    /**
     * A phrase or an exclusion as the words it must find adjacent and in order; [literal] when it
     * has no word at all (an emoji), which is then matched as a case-insensitive substring.
     */
    private class WordRun(
        val words: Array<String>,
        val literal: String,
    ) {
        companion object {
            fun of(text: String): WordRun = WordRun(words(text), text)

            private fun words(text: String): Array<String> {
                val out = ArrayList<String>()
                var i = 0
                while (i < text.length) {
                    while (i < text.length && !text[i].isLetterOrDigit()) i++
                    val start = i
                    while (i < text.length && text[i].isLetterOrDigit()) i++
                    if (i > start) out.add(text.substring(start, i))
                }
                return out.toTypedArray()
            }
        }
    }

    companion object {
        val DEFAULT_EXCLUDED_TAGS = setOf("client", "p", "e", "a", "alt")

        private fun fieldHolds(
            field: String,
            term: String?,
            run: WordRun?,
        ): Boolean =
            when {
                term != null -> field.contains(term, true)
                run == null -> false
                run.words.isEmpty() -> field.contains(run.literal, true)
                else -> containsWordRun(field, run.words)
            }

        /**
         * True when [field] holds [run] as consecutive whole words (letter/digit runs), compared
         * case-insensitively, with anything that is not a letter or digit between them. By index,
         * without allocating: it runs per field per event of a scan.
         */
        private fun containsWordRun(
            field: String,
            run: Array<String>,
        ): Boolean {
            var i = 0
            val n = field.length
            while (i < n) {
                while (i < n && !field[i].isLetterOrDigit()) i++
                if (i >= n) return false
                if (runStartsAt(field, i, run)) return true
                while (i < n && field[i].isLetterOrDigit()) i++
            }
            return false
        }

        private fun runStartsAt(
            field: String,
            start: Int,
            run: Array<String>,
        ): Boolean {
            val n = field.length
            var p = start
            for (k in run.indices) {
                if (k > 0) {
                    while (p < n && !field[p].isLetterOrDigit()) p++
                }
                val word = run[k]
                if (!field.regionMatches(p, word, 0, word.length, ignoreCase = true)) return false
                p += word.length
                if (p < n && field[p].isLetterOrDigit()) return false
            }
            return true
        }

        /**
         * The search string as the terms to require: whitespace-separated, except inside double
         * quotes, where the span is one term with the quotes removed. The matcher itself now reads
         * [SearchQuery.parse] (so `-word` is an exclusion and a quoted span a word run) and calls
         * this only on the loose [SearchQuery.terms], which hold no quotes.
         *
         * A naive split turned `"hello world"` into `"hello` and `world"` — two terms carrying a
         * quote character, so a phrase search could never match anything. An unterminated quote
         * runs to the end of the string, which is how a lexer reads it and how relays read it.
         */
        fun splitTerms(search: String?): Array<String> {
            val s = search ?: return emptyArray()
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            for (c in s) {
                when {
                    c == '"' -> {
                        // A closing quote ends the term even when empty-adjacent, so `a""b` is `a`, `b`.
                        if (quoted && current.isNotEmpty()) {
                            out.add(current.toString())
                            current.clear()
                        }
                        quoted = !quoted
                    }

                    !quoted && c.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            out.add(current.toString())
                            current.clear()
                        }
                    }

                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
            return out.toTypedArray()
        }
    }
}
