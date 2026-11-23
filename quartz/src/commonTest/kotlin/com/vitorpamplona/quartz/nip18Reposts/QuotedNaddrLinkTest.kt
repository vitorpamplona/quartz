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
package com.vitorpamplona.quartz.nip18Reposts

import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.QAddressableTag
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `q` may quote an addressable as an `naddr1…` instead of `kind:pubkey:d`. [QAddressableTag]
 * decodes it, so the linked-address readers built on it must keep linking it.
 */
class QuotedNaddrLinkTest {
    private val pk = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val address = "30023:$pk:my-article"
    private val naddr = NAddress.create(30023, pk, "my-article", null)
    private val id = "0".repeat(64)
    private val sig = "0".repeat(128)

    private val tags =
        arrayOf(
            arrayOf("q", naddr),
            arrayOf("q", "1:$pk:x"),
            arrayOf("q", "not-an-address"),
        )

    @Test
    fun parserDecodesTheNaddrLikeTheTag() {
        tags.forEach { tag ->
            assertEquals(QAddressableTag.parse(tag)?.address?.toValue(), QAddressableTag.parseAddressId(tag), tag[1])
        }
        assertEquals(address, QAddressableTag.parseAddressId(tags[0]))
    }

    @Test
    fun aNoteLinksTheAddressItQuotesByNaddr() {
        val note = TextNoteEvent(id, pk, 1, tags, "quoting", sig)
        assertEquals(listOf(address, "1:$pk:x"), note.quotedAddressIds())
        assertEquals(note.quotedAddresses().map { it.address.toValue() }, note.quotedAddressIds())
        assertEquals(listOf(address, "1:$pk:x"), note.linkedAddressIds())
    }

    @Test
    fun anArticleLinksTheAddressItQuotesByNaddr() {
        val article = LongFormContentEvent(id, pk, 1, tags + arrayOf(arrayOf("d", "self")), "", sig)
        assertEquals(listOf(address, "1:$pk:x"), article.linkedAddressIds())
    }

    @Test
    fun reportIdsMatchTheReportTags() {
        val report =
            ReportEvent(
                id,
                pk,
                1,
                arrayOf(arrayOf("e", "a".repeat(64), "spam"), arrayOf("p", pk, "spam"), arrayOf("a", address, "spam"), arrayOf("a", naddr, "spam")),
                "",
                sig,
            )
        assertEquals(report.reportedPost().map { it.eventId }, report.linkedEventIds())
        assertEquals(report.reportedAuthor().map { it.pubKey }, report.linkedPubKeys())
        assertEquals(report.reportedAddresses().map { it.address.toValue() }, report.linkedAddressIds())
        assertEquals(listOf(address, address), report.linkedAddressIds())
    }
}
