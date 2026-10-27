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
package com.vitorpamplona.quartz.experimental.pressstr.ebook

import com.vitorpamplona.quartz.experimental.pressstr.ebook.tags.IsbnTag
import com.vitorpamplona.quartz.experimental.pressstr.tags.BlossomTag
import com.vitorpamplona.quartz.experimental.pressstr.tags.LanguageTag
import com.vitorpamplona.quartz.experimental.pressstr.tags.SizeTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EbookEventTest {
    // Published by PressStr (pressstr.shakespeare.wtf).
    private val pressstrEbook =
        """{"id":"03b191ef807fd64fb90f78dd9fe73f3554b9cd137be36634ebfe37c7da033066","pubkey":"a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08","created_at":1781620832,"kind":33953,"tags":[["d","silent-feeds-unbroken"],["title","Silent Feeds Unbroken"],["image","https://blossom.primal.net/056de3a1210a2bf0e215f79c9552067bc07fc302746144b07d2fc83b38f0c551.jpg"],["url","https://blossom.primal.net/fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87.pdf"],["m","application/pdf"],["alt","Ebook: Silent Feeds Unbroken"],["author","ling-2.6.-1t"],["summary","A story created as a reaction to social media ban in the UK"],["published_at","1781568000"],["size","1905250"],["x","fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87"],["url","https://cdn.nostrcheck.me/fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87.pdf"],["url","https://blossom.ditto.pub/fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87.pdf"],["blossom","blossom:fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87.pdf?xs=blossom.primal.net&xs=cdn.nostrcheck.me&xs=blossom.ditto.pub&as=a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08&sz=1905250"],["l","en"],["client","pressstr.shakespeare.wtf"]],"content":"Liora, fifteen and tracked by slate at her wrist, moves through the Academy of Glass and Iron as bells carve dawn into the Citadel District. With the Innocence Edict barring youth from shimmering feeds, silence stands guard while frozen fountains and scanners lock down streets gilded in obedience. Rumors pass through dead drops, light, and vibration as officials hunt breaches. When quiet itself becomes a weapon and every glance a courier, what will Liora sacrifice to keep the pulse alive without letting it break her?","sig":"4371457b19246eb3f26bb4a6119eea703039fcdbc232e621cc06067919d4deab5e35f0f8c683e52768c749f8ae5080b7a70b8443ce20e54086cb128f4dcd31ad"}"""

    private val hash = "fbc38e4cf57049d09e9493c3a9373454c15f9faf9a53430e534b150e13a72f87"

    @Test
    fun parsesPressstrEbook() {
        val event = Event.fromJson(pressstrEbook)
        assertIs<EbookEvent>(event)
        assertTrue(event.verify())

        assertEquals("silent-feeds-unbroken", event.dTag())
        assertEquals("Silent Feeds Unbroken", event.title())
        assertEquals("https://blossom.primal.net/056de3a1210a2bf0e215f79c9552067bc07fc302746144b07d2fc83b38f0c551.jpg", event.image())
        assertEquals("https://blossom.primal.net/$hash.pdf", event.url())
        assertEquals(
            listOf(
                "https://blossom.primal.net/$hash.pdf",
                "https://cdn.nostrcheck.me/$hash.pdf",
                "https://blossom.ditto.pub/$hash.pdf",
            ),
            event.urls(),
        )
        assertEquals(EbookEvent.MIME_PDF, event.mimeType())
        assertTrue(event.isPdf())
        assertEquals(listOf("ling-2.6.-1t"), event.authors())
        assertEquals("A story created as a reaction to social media ban in the UK", event.summary())
        assertEquals(1781568000L, event.publishedAt())
        assertEquals(1905250L, event.size())
        assertEquals(hash, event.hash())
        assertEquals("en", event.language())
        assertNull(event.isbn())

        val blossom = event.blossom()
        assertEquals(hash, blossom?.sha256)
        assertEquals("pdf", blossom?.extension)
        assertEquals(listOf("blossom.primal.net", "cdn.nostrcheck.me", "blossom.ditto.pub"), blossom?.servers)
        assertEquals(1905250L, blossom?.size)
    }

    @Test
    fun isKnownAndSearchable() {
        assertTrue(EventFactory.isKnownKind(EbookEvent.KIND))

        val event = Event.fromJson(pressstrEbook)
        assertIs<SearchableEvent>(event)
        val indexed = event.indexableContent()
        assertTrue("Silent Feeds Unbroken" in indexed)
        assertTrue("ling-2.6.-1t" in indexed)
        assertTrue("Liora" in indexed)
    }

    @Test
    fun buildsSpecShapedEbook() {
        val template =
            EbookEvent.build(
                title = "The Bitcoin Standard",
                url = "https://blossom.example.com/bitcoin-standard.epub",
                mimeType = EbookEvent.MIME_EPUB,
                image = "https://blossom.example.com/bitcoin-standard-cover.webp",
                dTag = "the-bitcoin-standard",
                description = "A look at the history of money.",
                authors = listOf("Saifedean Ammous"),
                summary = "The decentralized alternative to central banking.",
                publishedAt = 1525132800L,
                language = "en",
                isbn = "978-1119473862",
            )

        val event = NostrSignerSync().sign(template)
        assertIs<EbookEvent>(EventFactory.create<Event>(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig))

        assertEquals("the-bitcoin-standard", event.dTag())
        assertEquals("The Bitcoin Standard", event.title())
        assertTrue(event.isEpub())
        assertEquals(listOf("Saifedean Ammous"), event.authors())
        assertEquals("978-1119473862", event.isbn())
        assertEquals(1525132800L, event.publishedAt())
        assertEquals("Ebook: The Bitcoin Standard", event.alt())
        assertEquals("A look at the history of money.", event.content)
    }

    @Test
    fun writesRepeatableTagsInOrder() {
        val template =
            EbookEvent.build(
                title = "Mirrored",
                url = "https://a.example/$hash.pdf",
                mimeType = EbookEvent.MIME_PDF,
                image = "https://a.example/cover.jpg",
                dTag = "mirrored",
                authors = listOf("First Author", "Second Author"),
            ) {
                urls(listOf("https://b.example/$hash.pdf", "https://c.example/$hash.pdf"))
            }

        assertEquals(listOf("First Author", "Second Author"), template.tags.authors())
        assertEquals(
            listOf("https://a.example/$hash.pdf", "https://b.example/$hash.pdf", "https://c.example/$hash.pdf"),
            template.tags.urls(),
        )
        assertEquals("https://a.example/$hash.pdf", template.tags.url())
        assertEquals("Mirrored", template.tags.title())
    }

    @Test
    fun tagCodecsRecognizeTheirTags() {
        assertTrue(IsbnTag.isTag(arrayOf("isbn", "978-1119473862")))
        assertTrue(LanguageTag.isTag(arrayOf("l", "en")))
        assertTrue(SizeTag.isTag(arrayOf("size", "1905250")))
        assertTrue(BlossomTag.isTag(arrayOf("blossom", "blossom:$hash.pdf")))
        assertFalse(IsbnTag.isTag(arrayOf("isbn", "")))
        assertFalse(SizeTag.isTag(arrayOf("x", hash)))
    }
}
