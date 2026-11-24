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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NEmbed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `nembed1…` is gunzipped and parsed on ingest, so how many are decoded per content is capped.
 * Its id is a citation like an `nevent1`'s, verified or not: a citation is a reference anyone can
 * write, and whoever renders or stores the embedded event verifies it.
 */
class NEmbedCitationTest {
    private val author = NostrSignerSync(KeyPair())

    private fun note(content: String): TextNoteEvent = author.sign(TextNoteEvent.build(content))

    @Test
    fun anEmbedCitesItsIdLikeAnNevent() {
        val real = note("embedded")
        val unverified = Event("ab".repeat(32), real.pubKey, real.createdAt, real.kind, real.tags, real.content, real.sig)
        val citing = note("look nostr:${NEmbed.create(unverified)} and nostr:${NEmbed.create(real)}")

        assertTrue(unverified.id in citing.findCitations())
        assertTrue(unverified.id in citing.linkedEventIds())
        assertTrue(real.id in citing.findCitations())
        assertTrue(real.id in citing.linkedEventIds())
    }

    @Test
    fun embedsDecodedPerContentAreCapped() {
        val embeds = (0 until 40).joinToString(" ") { "nostr:" + NEmbed.create(note("e$it")) }
        val parsed = Nip19Parser.parseAll(embeds).count { it is NEmbed }
        // Each costs a gunzip of up to 256 KB and a JSON parse, on ingest, per relay copy.
        assertEquals(8, parsed, "decoded $parsed embeds")
    }
}
