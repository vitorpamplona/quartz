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
package com.vitorpamplona.quartz.experimental.nipsOnNostr

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.experimental.forks.IForkableEvent
import com.vitorpamplona.quartz.experimental.forks.parseForkedAddress
import com.vitorpamplona.quartz.experimental.forks.parseForkedEventId
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.AddressSerializer
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.appendTo
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNullTo
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.kinds
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.BaseNoteEvent
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QAddressableTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.addressHintsTo
import com.vitorpamplona.quartz.nip19Bech32.addressIdsTo
import com.vitorpamplona.quartz.nip19Bech32.eventHintsTo
import com.vitorpamplona.quartz.nip19Bech32.eventIdsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHintsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeysTo
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip23LongContent.tags.TitleTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
class NipTextEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseNoteEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressableEvent,
    RootScope,
    EventHintProvider,
    AddressHintProvider,
    PubKeyHintProvider,
    IForkableEvent,
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(title(), content).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        visitor.visit(content)
    }

    override fun dTag() = tags.dTag()

    override fun address() = Address(kind, pubKey, dTag())

    override fun addressTag() = Address.assemble(kind, pubKey, dTag())

    // Plain `e` tags (the fork marker's version pin) as well as quotes and content citations,
    // the TextNoteEvent pattern.
    override fun eventHints(): List<EventIdHint> {
        var out: ArrayList<EventIdHint>? = null
        out = tags.fastMapNotNullTo(out, MarkedETag::parseAsHint)
        out = tags.fastMapNotNullTo(out, QTag::parseEventAsHint)
        out = citedNIP19().eventHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedEventIds(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = referencedEvents().appendTo(out)
        out = quotedEventIds().appendTo(out)
        out = citedNIP19().eventIdsTo(out)
        return out ?: emptyList()
    }

    override fun pubKeyHints(): List<PubKeyHint> {
        var out: ArrayList<PubKeyHint>? = null
        out = tags.fastMapNotNullTo(out, PTag::parseAsHint)
        out = citedNIP19().pubKeyHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedPubKeys(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = mentionKeys().appendTo(out)
        out = citedNIP19().pubKeysTo(out)
        return out ?: emptyList()
    }

    override fun addressHints(): List<AddressHint> {
        var out: ArrayList<AddressHint>? = null
        out = tags.fastMapNotNullTo(out, ATag::parseAsHint)
        out = tags.fastMapNotNullTo(out, QTag::parseAddressAsHint)
        out = citedNIP19().addressHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedAddressIds(): List<String> {
        var out: ArrayList<String>? = null
        out = referencedAddresses().appendTo(out)
        out = quotedAddressIds().appendTo(out)
        out = citedNIP19().addressIdsTo(out)
        return out ?: emptyList()
    }

    /** Users this NIP mentions (`p`), in tag order. Same shape as [com.vitorpamplona.quartz.nip10Notes.BaseThreadedEvent.mentions]. */
    fun mentions(): List<PTag> = tags.mapNotNull(PTag::parse)

    /** The keys of [mentions], in tag order. */
    fun mentionKeys(): List<HexKey> = tags.fastMapNotNull(PTag::parseKey)

    /** NIP-18 quotes (`q`) of regular events, in tag order. */
    fun quotedEvents(): List<QEventTag> = tags.mapNotNull(QEventTag::parse)

    /** The ids [quotedEvents] quotes, without building the tags. */
    fun quotedEventIds(): List<HexKey> = tags.fastMapNotNull(QTag::parseEventId)

    /** NIP-18 quotes (`q`) of addressable events, in tag order. */
    fun quotedAddresses(): List<QAddressableTag> = tags.mapNotNull(QAddressableTag::parse)

    /** The address ids [quotedAddresses] quotes, in canonical form, without building the tags. */
    fun quotedAddressIds(): List<String> = tags.fastMapNotNull(QTag::parseValidAddress)

    /** Every `e` this NIP carries (the [forkFromVersion] pin among them), as ids in tag order. */
    fun referencedEvents(): List<HexKey> = tags.fastMapNotNull(MarkedETag::parseId)

    /** Every `a` this NIP carries (the [forkFromAddress] among them), as shape-checked address ids in tag order. */
    fun referencedAddresses(): List<String> = tags.fastMapNotNull { ATag.parseAddressId(it)?.takeIf(AddressSerializer::isAddressShape) }

    override fun isAFork() = forkFromVersion() != null || forkFromAddress() != null

    override fun forkFromAddress() = tags.firstNotNullOfOrNull(::parseForkedAddress)

    override fun forkFromVersion() = tags.firstNotNullOfOrNull(MarkedETag::parseForkedEventId)

    fun title() = tags.firstNotNullOfOrNull(TitleTag::parse)

    fun summary() =
        content.lineSequence().drop(1).firstNotNullOfOrNull {
            if (it.isBlank() ||
                it.startsWith("---") ||
                it.startsWith("`draft") ||
                it.startsWith("#")
            ) {
                null
            } else {
                it
            }
        }

    companion object {
        const val KIND = 30817
        const val KIND_STR = "30817"

        @OptIn(ExperimentalUuidApi::class)
        fun build(
            description: String,
            title: String,
            kinds: List<Int>,
            dTag: String = Uuid.random().toString(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NipTextEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, description, createdAt) {
            dTag(dTag)

            title(title)
            kinds(kinds)

            initializer()
        }
    }
}
