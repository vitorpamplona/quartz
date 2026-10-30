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
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventSearchMatcherTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val id = "98b574c3527f0ffb30b7271084e3f07480733c7289f8de424d29eae82e36c758"

    private fun note(
        content: String,
        tags: Array<Array<String>> = emptyArray(),
        kind: Int = 1,
    ): Event = EventFactory.create(id, pubkey, 1683596206, kind, tags, content, "")

    private fun matches(
        search: String?,
        event: Event,
    ) = EventSearchMatcher(search).match(event)

    @Test
    fun anEmptySearchConstrainsNothing() {
        assertTrue(EventSearchMatcher(null).isEmpty)
        assertTrue(EventSearchMatcher("   ").isEmpty)
        assertTrue(matches(null, note("anything")))
        assertTrue(matches("", note("anything")))
    }

    @Test
    fun aTermIsACaseInsensitiveSubstring() {
        // Substring, not token: Amethyst's local search has always matched mid-word, and
        // switching to tokens would silently stop finding these.
        assertTrue(matches("itcoi", note("Bitcoin is money")))
        assertTrue(matches("BITCOIN", note("bitcoin is money")))
        assertFalse(matches("ethereum", note("bitcoin is money")))
    }

    @Test
    fun termsAreAnded() {
        // What a relay does with the same string; a single-word query is unaffected either way.
        assertTrue(matches("bitcoin money", note("bitcoin is money")))
        assertFalse(matches("bitcoin gold", note("bitcoin is money")))
    }

    @Test
    fun aSearchableKindMatchesWhatItIndexesAndNothingElse() {
        // Kind 1 indexes its subject and content, as a store's full-text index does; a hashtag
        // the kind leaves out of its index is left out here too, so local and store search agree.
        assertTrue(matches("subj", note("body", arrayOf(arrayOf("subject", "a subject")))))
        assertFalse(matches("bitcoin", note("body", arrayOf(arrayOf("t", "bitcoin")))))
        assertFalse(matches("amethyst", note("body", arrayOf(arrayOf("client", "amethyst")))))
        assertFalse(matches("deadbeef", note("body", arrayOf(arrayOf("p", "deadbeef".repeat(8))))))
    }

    @Test
    fun aDecentralizedListItemMatchesItsNaturalLanguageTagsButNotItsData() {
        val book =
            note(
                "",
                arrayOf(
                    arrayOf("d", "ol-ol1141875w"),
                    arrayOf("title", "Herfsttij der Middeleeuwen"),
                    arrayOf("author", "Johan Huizinga"),
                    arrayOf("subject", "Middle Ages"),
                    arrayOf("medium", "music"),
                    arrayOf("cover", "https://covers.openlibrary.org/b/id/7245546-L.jpg"),
                ),
                kind = 39999,
            )
        assertTrue(matches("huizinga", book))
        assertTrue(matches("middle ages", book))
        assertFalse(matches("music", book))
        assertFalse(matches("openlibrary", book))
        assertFalse(matches("ol1141875w", book))
    }

    @Test
    fun aKindWithNoIndexableFieldsFallsBackToItsTagsAndContent() {
        assertTrue(matches("bitcoin", note("+", arrayOf(arrayOf("t", "bitcoin")), kind = 7)))
        assertTrue(matches("+", note("+", kind = 7)))
        // `p`/`e`/`a` hold hex ids, `client` and `alt` hold text the author did not write.
        assertFalse(matches("amethyst", note("+", arrayOf(arrayOf("client", "amethyst")), kind = 7)))
        assertFalse(matches("deadbeef", note("+", arrayOf(arrayOf("p", "deadbeef".repeat(8))), kind = 7)))
    }

    @Test
    fun indexableFieldsBeyondContentAreSearched() {
        // A long-form title and summary live in tags; they reach the matcher through the visitor.
        val article = note("the body", arrayOf(arrayOf("title", "Lightning"), arrayOf("summary", "a summary")), kind = 30023)
        assertTrue(matches("Lightning", article))
        assertTrue(matches("summary", article))
        assertTrue(matches("body", article))
    }

    @Test
    fun unsupportedExtensionsAreIgnoredRatherThanTreatedAsTerms() {
        // NIP-50: a relay ignores extensions it does not implement. An extensions-only search
        // must therefore match everything, never nothing.
        assertTrue(EventSearchMatcher("domain:nostr.com").isEmpty)
        assertTrue(matches("domain:nostr.com", note("anything")))
        assertTrue(matches("bitcoin language:en", note("bitcoin is money")))
        assertFalse(matches("gold language:en", note("bitcoin is money")))
    }

    @Test
    fun aQuotedSpanIsOnePhraseNotTwoTermsCarryingQuotes() {
        // Splitting on whitespace alone turned `"hello world"` into `"hello` and `world"`, so a
        // phrase search could never match anything at all.
        assertTrue(matches("\"hello world\"", note("say hello world today")))
        assertFalse(matches("\"hello world\"", note("hello there, world")))
        // The quotes are a delimiter, never part of what has to be present in the event.
        assertTrue(matches("\"hello world\"", note("hello world")))
    }

    @Test
    fun anUnterminatedQuoteRunsToTheEnd() {
        assertTrue(matches("\"hello world", note("say hello world today")))
    }

    @Test
    fun quotedAndBareTermsMix() {
        assertTrue(matches("\"hello world\" bitcoin", note("hello world and bitcoin")))
        assertFalse(matches("\"hello world\" bitcoin", note("hello world only")))
    }

    @Test
    fun oneMatcherAnswersManyEventsConsistently() {
        // The visitor is reused across events; a stale term or hit flag would show up here.
        val matcher = EventSearchMatcher("lightning")
        val hit = note("about lightning", kind = 30023)
        val miss = note("about bitcoin", kind = 30023)
        repeat(5) {
            assertTrue(matcher.match(hit))
            assertFalse(matcher.match(miss))
        }
    }

    @Test
    fun aDashWordIsAnExclusionNotARequiredTerm() {
        // SearchQuery's grammar: `-word` excludes. Read as a term it required the text "-spam".
        assertTrue(matches("bitcoin -spam", note("bitcoin is money")))
        assertFalse(matches("bitcoin -spam", note("bitcoin spam here")))
        assertFalse(matches("bitcoin -SPAM", note("Bitcoin Spam")))
        assertFalse(EventSearchMatcher("-spam").isEmpty)
        // An exclusions-only search is recall minus the excluded word.
        assertTrue(matches("-spam", note("bitcoin is money")))
        assertFalse(matches("-spam", note("this is spam")))
    }

    @Test
    fun anExclusionIsAWholeWord() {
        // `-art` drops a note about art, not one about a start or an artist.
        assertTrue(matches("-art", note("a fresh start for the artist")))
        assertFalse(matches("-art", note("modern art, again")))
        // An exclusion that tokenizes into several words drops the run, as the store reads it.
        assertFalse(matches("-don't", note("I don't know")))
        assertTrue(matches("-don't", note("don know t")))
    }

    @Test
    fun anExcludedPhraseDropsOnlyTheRun() {
        assertFalse(matches("bitcoin -\"hello world\"", note("bitcoin: hello world")))
        assertTrue(matches("bitcoin -\"hello world\"", note("bitcoin: hello there, world")))
    }

    @Test
    fun anExclusionInAnyIndexedFieldDrops() {
        val article = note("the body", arrayOf(arrayOf("title", "Lightning spam")), kind = 30023)
        assertFalse(matches("body -spam", article))
        // Text the kind does not index cannot exclude it either.
        assertTrue(matches("body -bitcoin", note("the body", arrayOf(arrayOf("t", "bitcoin")))))
    }

    @Test
    fun aPhraseIsAWordRunAcrossPunctuationAndCase() {
        // Words adjacent and in order, as a token index reads the phrase; punctuation and case
        // between them do not matter, a word that only starts with the last one does.
        assertTrue(matches("\"nostr apps\"", note("Best Nostr-Apps of 2026")))
        assertFalse(matches("\"nostr app\"", note("best nostr apps")))
        assertFalse(matches("\"apps nostr\"", note("best nostr apps")))
        // A phrase with no word characters falls back to its literal text.
        assertTrue(matches("\"\u26a1\"", note("zap \u26a1 me")))
        assertFalse(matches("\"\u26a1\"", note("zap me")))
    }
}
