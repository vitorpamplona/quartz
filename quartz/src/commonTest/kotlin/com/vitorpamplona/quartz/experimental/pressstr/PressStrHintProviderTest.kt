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
package com.vitorpamplona.quartz.experimental.pressstr

import com.vitorpamplona.quartz.experimental.pressstr.ebook.EbookEvent
import com.vitorpamplona.quartz.experimental.pressstr.issue.MagazineIssueEvent
import com.vitorpamplona.quartz.experimental.pressstr.magazine.MagazineEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The links all three PressStr kinds share: `p`, `e`, `a` and `q` tags, and `nostr:` citations in the body. */
class PressStrHintProviderTest {
    private val author = "a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08"
    private val other = "99bb5591c9116600f845107d31f9b59e2f7c7e09a1ff802e84f1d43da557ca64"
    private val eventId = "43575072239da152afe3d7b5c70ed2beb48db2b10e60c60da45229c09c877d2a"
    private val quotedId = "c3d3a1a5d6d4f7c9f0e3b1a2c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7"
    private val citedId = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val relay = RelayUrlNormalizer.normalizeOrNull("wss://relay.damus.io/")!!
    private val relay2 = RelayUrlNormalizer.normalizeOrNull("wss://nos.lol/")!!
    private val magazine = "34609:$author:the-mag"

    private val content =
        "With an interview of nostr:${NProfile.create(other, relay2)}, about " +
            "nostr:${NEvent.create(citedId, null, null, relay2)} and nostr:${NAddress.create(30023, other, "review", relay2)}"

    private fun referenceTags(vararg first: Array<String>): TagArray =
        arrayOf(
            arrayOf("d", "slug"),
            *first,
            arrayOf("p", other, relay.url),
            arrayOf("e", eventId, relay.url),
            arrayOf("q", quotedId, relay.url),
            arrayOf("q", "30023:$other:post"),
        )

    private fun assertLinksEverything(
        event: Any,
        addressTags: List<String>,
        addressTagHints: List<AddressHint>,
    ) {
        assertIs<RootScope>(event)

        assertIs<PubKeyHintProvider>(event)
        assertEquals(listOf(other, other), event.linkedPubKeys())
        assertEquals(listOf(PubKeyHint(other, relay), PubKeyHint(other, relay2)), event.pubKeyHints())

        assertIs<EventHintProvider>(event)
        assertEquals(listOf(eventId, quotedId, citedId), event.linkedEventIds())
        assertEquals(listOf(EventIdHint(eventId, relay), EventIdHint(quotedId, relay), EventIdHint(citedId, relay2)), event.eventHints())

        assertIs<AddressHintProvider>(event)
        assertEquals(addressTags + listOf("30023:$other:post", "30023:$other:review"), event.linkedAddressIds())
        assertEquals(addressTagHints + AddressHint("30023:$other:review", relay2), event.addressHints())
    }

    @Test
    fun ebookLinksItsTagsAndCitations() {
        val event = EbookEvent(eventId, author, 1700000000, referenceTags(arrayOf("author", "Plain Name")), content, "")
        assertLinksEverything(event, emptyList(), emptyList())
    }

    @Test
    fun magazineLinksItsTagsAndCitations() {
        val event = MagazineEvent(eventId, author, 1700000000, referenceTags(), content, "")
        assertLinksEverything(event, emptyList(), emptyList())
    }

    @Test
    fun issueLinksItsMagazineFirstWithItsHint() {
        val event = MagazineIssueEvent(eventId, author, 1700000000, referenceTags(arrayOf("a", magazine, relay.url)), content, "")
        assertLinksEverything(event, listOf(magazine), listOf(AddressHint(magazine, relay)))
        assertEquals("the-mag", event.magazine()?.dTag)
    }

    @Test
    fun issueWithoutRelayHintStillLinksItsMagazine() {
        val event = MagazineIssueEvent(eventId, author, 1700000000, arrayOf(arrayOf("a", magazine)), "", "")
        assertEquals(listOf(magazine), event.linkedAddressIds())
        assertTrue(event.addressHints().isEmpty())
    }

    @Test
    fun noReferencesMeansNoLinks() {
        val event = EbookEvent(eventId, author, 1700000000, arrayOf(arrayOf("d", "slug"), arrayOf("author", "Plain Name")), "Just a blurb.", "")
        assertTrue(event.linkedPubKeys().isEmpty())
        assertTrue(event.linkedEventIds().isEmpty())
        assertTrue(event.linkedAddressIds().isEmpty())
    }

    private fun visitedFields(event: SearchableEvent): List<String> {
        val fields = mutableListOf<String>()
        event.forEachIndexableField { field ->
            field?.let { fields.add(it) }
            true
        }
        return fields
    }

    @Test
    fun searchVisitorWalksExactlyWhatIsIndexed() {
        val tags =
            arrayOf(
                arrayOf("d", "slug"),
                arrayOf("title", "The Title"),
                arrayOf("summary", "The Summary"),
                arrayOf("issue", "Spring 2025"),
                arrayOf("author", "First Author"),
                arrayOf("author", "Second Author"),
            )
        val events: List<SearchableEvent> =
            listOf(
                EbookEvent(eventId, author, 1700000000, tags, "The body.", ""),
                MagazineEvent(eventId, author, 1700000000, tags, "The body.", ""),
                MagazineIssueEvent(eventId, author, 1700000000, tags, "The body.", ""),
            )

        events.forEach { event ->
            assertEquals(event.indexableContent(), visitedFields(event).joinToString("\n"), event::class.simpleName)
        }

        assertEquals(listOf("The Title", "The Summary", "The body.", "First Author", "Second Author"), visitedFields(events[0]))
        assertEquals(listOf("The Title", "The Summary", "The body."), visitedFields(events[1]))
        assertEquals(listOf("The Title", "Spring 2025", "The Summary", "The body."), visitedFields(events[2]))
    }

    @Test
    fun tagReadersDoNotLeakIntoLinks() {
        // An ebook's file mirrors and cover are URLs, not nostr references.
        val event =
            EbookEvent(
                eventId,
                author,
                1700000000,
                arrayOf(arrayOf("url", "https://a.example/x.pdf"), arrayOf("image", "https://a.example/c.jpg"), arrayOf("x", quotedId)),
                "",
                "",
            )
        assertEquals(emptyList<HexKey>(), event.linkedEventIds())
        assertEquals(emptyList<String>(), event.linkedAddressIds())
    }
}
