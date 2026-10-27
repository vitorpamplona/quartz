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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip01Core.tags.publishedAt.PublishedAtProvider
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * One issue of a magazine (kind 39731): a PDF with its cover, pointing at its magazine (kind
 * 34609) with an `a` tag.
 *
 * **Not a merged NIP.** One of the three kinds in PressStr's "NIP-PressStr" draft, published on
 * nostr as a kind 30817 NIP (`30817:a8d1560d…7a08:pressstr`). Tags: `d`, `title`, `issue` (a
 * free-form label), `a`, `image` (cover), `url` (the PDF) and `alt` are required; `m` (default
 * `application/pdf`), `size`, `x`, `summary`, `published_at` and `t` are optional. The content is
 * empty or editorial notes. The PressStr client also writes Blossom mirror `url`s and a BUD-10
 * `blossom` URI, as it does for ebooks.
 *
 * Issues in the wild often outlive or precede their magazine on a given relay, so a reader cannot
 * count on the parent loading.
 */
@Immutable
class MagazineIssueEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressHintProvider,
    PublishedAtProvider,
    SearchableEvent {
    override fun addressHints() = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds() = listOfNotNull(magazine()?.toTag())

    override fun indexableContent() = listOfNotNull(title(), issue(), summary(), content).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        if (!visitor.visit(issue())) return
        if (!visitor.visit(summary())) return
        visitor.visit(content)
    }

    fun title() = tags.title()

    /** A display name, falling back to the `d` identifier when the title is missing. */
    fun titleOrIdentifier(): String = title()?.takeIf { it.isNotBlank() } ?: dTag()

    /** Which issue this is, as the publisher labeled it: `7`, `May 2024`, `Spring 2025`. */
    fun issue() = tags.issue()

    fun magazine() = tags.magazine()

    fun image() = tags.image()

    /** The primary copy of the file. */
    fun url() = tags.url()

    /** Every copy of the file, primary first, then the mirrors. */
    fun urls() = tags.urls()

    /** The file's type; the spec makes `application/pdf` the default when `m` is missing. */
    fun mimeType() = tags.mimeType() ?: MIME_PDF

    fun isPdf() = mimeType() == MIME_PDF

    fun summary() = tags.summary()

    /** File size in bytes. */
    fun size() = tags.size()

    /** SHA-256 of the file, hex. On Blossom this is also the file's name. */
    fun hash() = tags.hash()

    fun blossom() = tags.blossom()

    fun topics() = tags.hashtags()

    /** When the issue came out, which for a back issue is long before this event. Not capped at [createdAt]. */
    override fun publishedAt() = tags.publishedAt()

    companion object {
        const val KIND = 39731
        const val ALT_DESCRIPTION_PREFIX = "Magazine Issue: "
        const val MIME_PDF = "application/pdf"

        fun build(
            title: String,
            issue: String,
            magazine: ATag,
            url: String,
            image: String,
            dTag: String,
            mimeType: String = MIME_PDF,
            description: String = "",
            summary: String? = null,
            publishedAt: Long? = null,
            size: Long? = null,
            hash: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<MagazineIssueEvent>.() -> Unit = {},
        ): EventTemplate<MagazineIssueEvent> =
            eventTemplate(KIND, description, createdAt) {
                dTag(dTag)
                title(title)
                issue(issue)
                magazine(magazine)
                image(image)
                url(url)
                mimeType(mimeType)
                alt("$ALT_DESCRIPTION_PREFIX$title, Issue $issue")

                summary?.let { summary(it) }
                publishedAt?.let { publishedAt(it) }
                size?.let { size(it) }
                hash?.let { hash(it) }

                initializer()
            }
    }
}
