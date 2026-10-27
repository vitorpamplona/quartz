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
package com.vitorpamplona.quartz.experimental.pressstr.magazine

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
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
 * A magazine or periodical (kind 34609): the parent record its issues (kind 39731) point at.
 *
 * **Not a merged NIP.** One of the three kinds in PressStr's "NIP-PressStr" draft, published on
 * nostr as a kind 30817 NIP (`30817:a8d1560d…7a08:pressstr`). Tags: `d`, `title` and `alt` are
 * required; `image` (logo or masthead), `summary`, `t` (repeatable), `l` (ISO-639-1) and `url`
 * (the magazine's website, not a file) are optional. The content is empty or an about text.
 *
 * Issues are found by their `a` tag: `{"kinds": [39731], "#a": ["34609:<pubkey>:<d>"]}`.
 */
@Immutable
class MagazineEvent(
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
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(title(), summary(), content).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        if (!visitor.visit(summary())) return
        visitor.visit(content)
    }

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var citedNIP19Cache: List<Entity>? = null

    /** NIP-19 entities cited as `nostr:` URIs in the Markdown about text, parsed once. */
    fun citedNIP19(): List<Entity> = citedNIP19Cache ?: findNostrUris(content).also { citedNIP19Cache = it }

    // PressStr defines no `p`, `e` or `q` tags, but the body is Markdown that cites people and notes,
    // and clients add the matching tags when they do. Its issues point at it, not the other way
    // around, so a magazine links none of them.
    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint) + citedNIP19().pubKeyHints()

    override fun linkedPubKeys(): List<HexKey> = tags.mapNotNull(PTag::parseKey) + citedNIP19().pubKeys()

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint) + tags.mapNotNull(QTag::parseEventAsHint) + citedNIP19().eventHints()

    override fun linkedEventIds(): List<HexKey> = tags.mapNotNull(ETag::parseId) + tags.mapNotNull(QTag::parseEventId) + citedNIP19().eventIds()

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint) + tags.mapNotNull(QTag::parseAddressAsHint) + citedNIP19().addressHints()

    override fun linkedAddressIds(): List<String> = tags.mapNotNull(ATag::parseValidAddress) + tags.mapNotNull(QTag::parseValidAddress) + citedNIP19().addressIds()

    fun title() = tags.title()

    /** A display name, falling back to the `d` identifier when the title is missing. */
    fun titleOrIdentifier(): String = title()?.takeIf { it.isNotBlank() } ?: dTag()

    fun image() = tags.image()

    fun summary() = tags.summary()

    fun website() = tags.website()

    fun topics() = tags.hashtags()

    fun language() = tags.language()

    companion object {
        const val KIND = 34609
        const val ALT_DESCRIPTION_PREFIX = "Magazine: "

        fun build(
            title: String,
            dTag: String,
            description: String = "",
            image: String? = null,
            summary: String? = null,
            website: String? = null,
            language: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<MagazineEvent>.() -> Unit = {},
        ): EventTemplate<MagazineEvent> =
            eventTemplate(KIND, description, createdAt) {
                dTag(dTag)
                title(title)
                alt(ALT_DESCRIPTION_PREFIX + title)

                image?.let { image(it) }
                summary?.let { summary(it) }
                website?.let { website(it) }
                language?.let { language(it) }

                initializer()
            }
    }
}
