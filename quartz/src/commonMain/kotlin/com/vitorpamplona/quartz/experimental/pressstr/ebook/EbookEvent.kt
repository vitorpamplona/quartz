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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.experimental.publications.tags.AuthorTag
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.publishedAt.PublishedAtProvider
import com.vitorpamplona.quartz.nip10Notes.content.findNostrUris
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.addressHints
import com.vitorpamplona.quartz.nip19Bech32.addressIds
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.eventHints
import com.vitorpamplona.quartz.nip19Bech32.eventIds
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHints
import com.vitorpamplona.quartz.nip19Bech32.pubKeys
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A standalone ebook (kind 33953), a PDF or EPUB file with its cover and bibliographic data.
 *
 * **Not a merged NIP.** It is one of three kinds in PressStr's "NIP-PressStr" draft, published
 * on nostr as a kind 30817 NIP (`30817:a8d1560d…7a08:pressstr`) beside kind 34609 (magazine) and
 * 39731 (magazine issue). Tags, all single unless noted:
 *
 * - `d`, `title`, `image` (cover), `url` (the file), `m` (`application/pdf` or
 *   `application/epub+zip`) and `alt` are required.
 * - `author` (repeatable, plain names, not pubkeys), `summary`, `published_at`, `size` (bytes),
 *   `x` (SHA-256 of the file), `t` (repeatable), `l` (ISO-639-1) and `isbn` are optional.
 * - The content is empty or a blurb in Markdown.
 *
 * The PressStr client also writes more `url` tags for Blossom mirrors of the same file, plus a
 * BUD-10 `blossom` URI listing those servers, so [urls] returns every copy, primary first.
 */
@Immutable
class EbookEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    RootScope,
    PubKeyHintProvider,
    EventHintProvider,
    AddressHintProvider,
    PublishedAtProvider,
    SearchableEvent {
    override fun indexableContent() = (listOfNotNull(title(), summary(), content) + authors()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        if (!visitor.visit(summary())) return
        if (!visitor.visit(content)) return
        tags.fastForEach { tag ->
            val author = AuthorTag.parse(tag)
            if (author != null && !visitor.visit(author)) return
        }
    }

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var citedNIP19Cache: List<Entity>? = null

    /** NIP-19 entities cited as `nostr:` URIs in the Markdown blurb, parsed once. */
    fun citedNIP19(): List<Entity> = citedNIP19Cache ?: findNostrUris(content).also { citedNIP19Cache = it }

    // PressStr defines no `p`, `e` or `q` tags, but the body is Markdown that cites people and notes,
    // and clients add the matching tags when they do. A book's `author`s are plain names, not keys, so
    // they are not links.
    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint) + citedNIP19().pubKeyHints()

    // linked*() run on every relay copy of the event, and each is several tag scans plus
    // concatenations; the event is immutable, so they are built once per instance.
    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var linkedPubKeysCache: List<HexKey>? = null

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var linkedEventIdsCache: List<HexKey>? = null

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var linkedAddressIdsCache: List<String>? = null

    override fun linkedPubKeys(): List<HexKey> = linkedPubKeysCache ?: (tags.mapNotNull(PTag::parseKey) + citedNIP19().pubKeys()).also { linkedPubKeysCache = it }

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint) + tags.mapNotNull(QTag::parseEventAsHint) + citedNIP19().eventHints()

    override fun linkedEventIds(): List<HexKey> = linkedEventIdsCache ?: (tags.mapNotNull(ETag::parseId) + tags.mapNotNull(QTag::parseEventId) + citedNIP19().eventIds()).also { linkedEventIdsCache = it }

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint) + tags.mapNotNull(QTag::parseAddressAsHint) + citedNIP19().addressHints()

    override fun linkedAddressIds(): List<String> =
        linkedAddressIdsCache
            ?: (tags.mapNotNull(ATag::parseValidAddress) + tags.mapNotNull(QTag::parseValidAddress) + citedNIP19().addressIds()).also { linkedAddressIdsCache = it }

    fun title() = tags.title()

    /** A display name, falling back to the `d` identifier when the title is missing. */
    fun titleOrIdentifier(): String = title()?.takeIf { it.isNotBlank() } ?: dTag()

    fun image() = tags.image()

    /** The primary copy of the file. */
    fun url() = tags.url()

    /** Every copy of the file, primary first, then the mirrors. */
    fun urls() = tags.urls()

    fun mimeType() = tags.mimeType()

    fun isPdf() = mimeType() == MIME_PDF

    fun isEpub() = mimeType() == MIME_EPUB

    /** The authors' names, as plain strings: a book's author rarely has a nostr key. */
    fun authors() = tags.authors()

    fun summary() = tags.summary()

    /** File size in bytes. */
    fun size() = tags.size()

    /** SHA-256 of the file, hex. On Blossom this is also the file's name. */
    fun hash() = tags.hash()

    fun blossom() = tags.blossom()

    fun topics() = tags.hashtags()

    fun language() = tags.language()

    fun isbn() = tags.isbn()

    /**
     * When the book first came out, which for a reissue or an upload of an old work is long before
     * this event. Unlike an article's `published_at` it is not capped at [createdAt]: a book can
     * legitimately be announced ahead of its release date.
     */
    override fun publishedAt() = tags.publishedAt()

    companion object {
        const val KIND = 33953
        const val ALT_DESCRIPTION_PREFIX = "Ebook: "
        const val MIME_PDF = "application/pdf"
        const val MIME_EPUB = "application/epub+zip"

        fun build(
            title: String,
            url: String,
            mimeType: String,
            image: String,
            dTag: String,
            description: String = "",
            authors: List<String> = emptyList(),
            summary: String? = null,
            publishedAt: Long? = null,
            size: Long? = null,
            hash: String? = null,
            language: String? = null,
            isbn: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<EbookEvent>.() -> Unit = {},
        ): EventTemplate<EbookEvent> =
            eventTemplate(KIND, description, createdAt) {
                dTag(dTag)
                title(title)
                image(image)
                url(url)
                mimeType(mimeType)
                alt(ALT_DESCRIPTION_PREFIX + title)

                authors(authors)
                summary?.let { summary(it) }
                publishedAt?.let { publishedAt(it) }
                size?.let { size(it) }
                hash?.let { hash(it) }
                language?.let { language(it) }
                isbn?.let { isbn(it) }

                initializer()
            }
    }
}
