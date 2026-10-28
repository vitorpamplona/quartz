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
package com.vitorpamplona.quartz.drss.provenance

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.content.findNostrUris
import com.vitorpamplona.quartz.nip19Bech32.addressHints
import com.vitorpamplona.quartz.nip19Bech32.addressIds
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.eventHints
import com.vitorpamplona.quartz.nip19Bech32.eventIds
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHints
import com.vitorpamplona.quartz.nip19Bech32.pubKeys
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelNamespaceTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A drss.io provenance record (kind 31985): where the reader found a feed, and why they kept it.
 * Labelled with the NIP-32 namespace [NAMESPACE] (`L`, and `l` = [LABEL]):
 *
 * - `d` and `p` = the feed bot the record is about;
 * - `a` = the article the reader found it through, when there is one;
 * - `i` = the source, a URL or other identifier, and `k` = its type (`web`);
 * - `content` = the reader's note, which may cite events and people as `nostr:` URIs.
 *
 * Records are grouped by [DrssProvenanceFolderEvent]s (kind 30104). Searchable by the note, with the
 * source as its website.
 *
 * **Kind 31985 is shared** with Ditto's ratings (`k`, `rating`) and a game log. `EventFactory`
 * builds this class for [isProvenance] tags and [UnrecognizedKind31985Event] for the rest.
 */
@Immutable
class DrssProvenanceEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    PubKeyHintProvider,
    EventHintProvider,
    AddressHintProvider,
    SearchableEvent {
    /** The feed bot this record is about (`p`). */
    fun feed(): PTag? = tags.feed()

    /** The key of [feed] (`p`). */
    fun feedKey(): HexKey? = tags.feedKey()

    /** The article the reader found the feed through (`a`). */
    fun foundThrough(): ATag? = tags.foundThrough()

    /** The address of [foundThrough], as `kind:pubkey:d`. */
    fun foundThroughAddressId(): String? = foundThrough()?.toTag()

    /** Where the feed was found: a URL or other identifier (`i`). */
    fun source(): String? = tags.source()

    /** The type of [source] (`k`), such as `web`. */
    fun sourceType(): String? = tags.sourceType()

    /** The reader's note. */
    fun note(): String = content

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var citedNIP19Cache: List<Entity>? = null

    /** NIP-19 entities cited as `nostr:` URIs in the note, parsed once. */
    fun citedNIP19(): List<Entity> = citedNIP19Cache ?: findNostrUris(content).also { citedNIP19Cache = it }

    override fun indexableContent() = listOfNotNull(content.ifBlank { null }, source()).joinToString("\n")

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(content.ifBlank { null })) return
        visitor.visit(source())
    }

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint) + citedNIP19().pubKeyHints()

    /** `LABELED`: the feed bot (`p`); `MENTION`: people cited in the note. */
    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(feedKey()) + citedNIP19().pubKeys()

    override fun eventHints(): List<EventIdHint> = citedNIP19().eventHints()

    /** `MENTION`: events cited in the note. */
    override fun linkedEventIds(): List<HexKey> = citedNIP19().eventIds()

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint) + citedNIP19().addressHints()

    /** `LABELED`: the article the feed was found through (`a`); `MENTION`: addresses cited in the note. */
    override fun linkedAddressIds(): List<String> = listOfNotNull(foundThroughAddressId()) + citedNIP19().addressIds()

    companion object {
        const val KIND = 31985

        const val NAMESPACE = "systems.buildtall.provenance"
        const val LABEL = "provenance"

        /** True for drss's shape: the [NAMESPACE] label namespace (`L`). */
        fun isProvenance(tags: TagArray): Boolean = tags.fastAny { LabelNamespaceTag.isTagged(it, NAMESPACE) }

        /** A record that the reader found [feed] at [source], with the reader's [note]. */
        fun build(
            feed: PTag,
            source: String,
            sourceType: String,
            note: String,
            foundThrough: ATag? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssProvenanceEvent>.() -> Unit = {},
        ) = eventTemplate<DrssProvenanceEvent>(KIND, note, createdAt) {
            dTag(feed.pubKey)
            provenanceLabel()
            feed(feed)
            foundThrough?.let { foundThrough(it) }
            source(source)
            sourceType(sourceType)
            initializer()
        }
    }
}
