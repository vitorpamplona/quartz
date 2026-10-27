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
package com.vitorpamplona.quartz.experimental.pressstr.issue

import com.vitorpamplona.quartz.experimental.pressstr.issue.tags.IssueTag
import com.vitorpamplona.quartz.experimental.pressstr.magazine.MagazineEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagazineIssueEventTest {
    // Published by PressStr (pressstr.shakespeare.wtf).
    private val pressstrIssue =
        """{"id":"448c73c0a103ea6cc460386c424b8049e68616c757afdb9eae9f89e2c8a76dcf","pubkey":"a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08","created_at":1789937077,"kind":39731,"tags":[["d","issue-7"],["title","Issue 7"],["issue","7"],["url","https://blossom.ditto.pub/f84b1756382647a6b89a455eafb1f63711f9d151bd6d826707a1b213c2c69348.pdf"],["m","application/pdf"],["alt","Magazine Issue: Issue 7, Issue 7"],["a","34609:a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08:stl1988-s-personal-mag"],["x","f84b1756382647a6b89a455eafb1f63711f9d151bd6d826707a1b213c2c69348"],["size","158857667"],["image","https://blossom.ditto.pub/bbb044c87925da9c254f3168d24122a61bf8fd2b8ace1fa9f574b6cb675c10d2.jpeg"],["summary","Derek Ross special issue"],["published_at","1789862400"],["client","pressstr.shakespeare.wtf"]],"content":"","sig":"6ee78b45557fd54986f4e7c5e2e2a666ff51573a64844c5c198c9846a1b41a1ed50d9d7714877b52436f20ede988bdb2a108be3328c328f7c66d8de51a060c73"}"""

    private val publisher = "a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08"
    private val hash = "f84b1756382647a6b89a455eafb1f63711f9d151bd6d826707a1b213c2c69348"

    @Test
    fun parsesPressstrIssue() {
        val event = Event.fromJson(pressstrIssue)
        assertIs<MagazineIssueEvent>(event)
        assertTrue(event.verify())

        assertEquals("issue-7", event.dTag())
        assertEquals("Issue 7", event.title())
        assertEquals("7", event.issue())
        assertEquals("https://blossom.ditto.pub/$hash.pdf", event.url())
        assertEquals(MagazineIssueEvent.MIME_PDF, event.mimeType())
        assertEquals(hash, event.hash())
        assertEquals(158857667L, event.size())
        assertEquals("https://blossom.ditto.pub/bbb044c87925da9c254f3168d24122a61bf8fd2b8ace1fa9f574b6cb675c10d2.jpeg", event.image())
        assertEquals("Derek Ross special issue", event.summary())
        assertEquals(1789862400L, event.publishedAt())

        val magazine = event.magazine()
        assertEquals(MagazineEvent.KIND, magazine?.kind)
        assertEquals(publisher, magazine?.pubKeyHex)
        assertEquals("stl1988-s-personal-mag", magazine?.dTag)
        assertEquals(listOf("34609:$publisher:stl1988-s-personal-mag"), event.linkedAddressIds())
    }

    @Test
    fun isKnownAndSearchable() {
        assertTrue(EventFactory.isKnownKind(MagazineIssueEvent.KIND))

        val event = Event.fromJson(pressstrIssue)
        assertIs<SearchableEvent>(event)
        assertTrue("Derek Ross special issue" in event.indexableContent())
    }

    @Test
    fun magazineIgnoresAddressesOfOtherKinds() {
        val tags =
            arrayOf(
                arrayOf("a", "30023:$publisher:an-article"),
                arrayOf("a", "34609:$publisher:the-mag"),
            )
        assertEquals("the-mag", tags.magazine()?.dTag)
        assertNull(arrayOf(arrayOf("a", "30023:$publisher:an-article")).magazine())
    }

    @Test
    fun mimeTypeDefaultsToPdf() {
        val event = MagazineIssueEvent(hash, publisher, 0L, arrayOf(arrayOf("issue", "1")), "", "")
        assertEquals(MagazineIssueEvent.MIME_PDF, event.mimeType())
        assertTrue(event.isPdf())
    }

    @Test
    fun buildsSpecShapedIssue() {
        val template =
            MagazineIssueEvent.build(
                title = "Bitcoin Magazine — May 2024",
                issue = "May 2024",
                magazine = ATag(MagazineEvent.KIND, publisher, "bitcoin-magazine", null),
                url = "https://blossom.example.com/btcmag-2024-05.pdf",
                image = "https://blossom.example.com/cover-2024-05.webp",
                dTag = "2024-05",
                summary = "Lightning Network 5th anniversary special issue.",
                publishedAt = 1714521600L,
                size = 14285714L,
            ) {
                urls(listOf("https://mirror.example.com/btcmag-2024-05.pdf"))
            }

        val event = NostrSignerSync().sign(template)
        assertIs<MagazineIssueEvent>(EventFactory.create<Event>(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig))

        assertEquals("May 2024", event.issue())
        assertEquals("bitcoin-magazine", event.magazine()?.dTag)
        assertEquals(2, event.urls().size)
        assertEquals(14285714L, event.size())
        assertEquals("Magazine Issue: Bitcoin Magazine — May 2024, Issue May 2024", event.alt())
    }

    @Test
    fun issueTagRecognizesItsTag() {
        assertTrue(IssueTag.isTag(arrayOf("issue", "7")))
        assertFalse(IssueTag.isTag(arrayOf("issue", "")))
        assertFalse(IssueTag.isTag(arrayOf("title", "7")))
    }
}
