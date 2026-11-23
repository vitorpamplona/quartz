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
package com.vitorpamplona.quartz.nipC7Chats

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.appendTo
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNullTo
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.BaseNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.QAddressableTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.quote
import com.vitorpamplona.quartz.nip19Bech32.addressHintsTo
import com.vitorpamplona.quartz.nip19Bech32.addressIdsTo
import com.vitorpamplona.quartz.nip19Bech32.eventHintsTo
import com.vitorpamplona.quartz.nip19Bech32.eventIdsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHintsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeysTo
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.lastNotNullOfOrNull

@Immutable
class ChatEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseNoteEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    RootScope,
    EventHintProvider,
    AddressHintProvider,
    PubKeyHintProvider,
    SearchableEvent {
    override fun indexableContent() = content

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(content)
    }

    /** Events quoted with `q` tags (NIP-18), in tag order. The last one is the message replied to. */
    fun quotedEvents(): List<QEventTag> = tags.mapNotNull(QEventTag::parse)

    /** The ids [quotedEvents] quotes, without building the tags. */
    fun quotedEventIds(): List<HexKey> = tags.fastMapNotNull(QTag::parseEventId)

    /** Addressable events quoted with `q` tags (NIP-18), in tag order. */
    fun quotedAddresses(): List<QAddressableTag> = tags.mapNotNull(QAddressableTag::parse)

    /** The address ids [quotedAddresses] quotes, in canonical form (an `naddr1` decoded), without building the tags. */
    fun quotedAddressIds(): List<String> = tags.fastMapNotNull(QAddressableTag::parseAddressId)

    /** Users mentioned with `p` tags, in tag order. */
    fun mentions(): List<PTag> = tags.mapNotNull(PTag::parse)

    /** The keys of [mentions] (`p`), in tag order. */
    fun mentionKeys(): List<HexKey> = tags.fastMapNotNull(PTag::parseKey)

    /** NIP-C7: the message this one replies to, the last `q` that quotes an event. */
    fun replyingTo(): HexKey? = tags.lastNotNullOfOrNull(QTag::parseEventId)

    /**
     * Every event this message replies to: the plain NIP-10 `e` tags that Marmot clients
     * (WhiteNoise) thread kind 9 with, then the NIP-C7 `q` replies [quotedEvents] holds.
     */
    fun replyTargetIds(): List<HexKey> {
        val ids = tags.mapNotNullTo(ArrayList(), ETag::parseId)
        quotedEvents().mapTo(ids) { it.eventId }
        return ids
    }

    override fun eventHints(): List<EventIdHint> {
        var out: ArrayList<EventIdHint>? = null
        out = tags.fastMapNotNullTo(out, QTag::parseEventAsHint)
        out = citedNIP19().eventHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedEventIds(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = quotedEventIds().appendTo(out)
        out = citedNIP19().eventIdsTo(out)
        return out ?: emptyList()
    }

    // q tags may quote an addressable event ("kind:pubkey:dtag") instead of an id,
    // and the content may cite naddrs: both are address references.
    override fun addressHints(): List<AddressHint> {
        var out: ArrayList<AddressHint>? = null
        out = tags.fastMapNotNullTo(out, QTag::parseAddressAsHint)
        out = citedNIP19().addressHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedAddressIds(): List<String> {
        var out: ArrayList<String>? = null
        out = quotedAddressIds().appendTo(out)
        out = citedNIP19().addressIdsTo(out)
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

    companion object {
        const val KIND = 9

        fun build(
            message: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChatEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, message, createdAt) {
            initializer()
        }

        fun reply(
            message: String,
            replyTo: EventHintBundle<ChatEvent>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChatEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, message, createdAt) {
            quote(QEventTag(replyTo.event.id, replyTo.relay, replyTo.event.pubKey))
            initializer()
        }
    }
}
