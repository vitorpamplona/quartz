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
package com.vitorpamplona.quartz.nip28PublicChat.message

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.AddressSerializer
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.appendTo
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNullTo
import com.vitorpamplona.quartz.nip01Core.core.tagArray
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.BaseThreadedEvent
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip10Notes.tags.markedETag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.addressHintsTo
import com.vitorpamplona.quartz.nip19Bech32.addressIdsTo
import com.vitorpamplona.quartz.nip19Bech32.eventHintsTo
import com.vitorpamplona.quartz.nip19Bech32.eventIdsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHintsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeysTo
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelCreateEvent
import com.vitorpamplona.quartz.nip28PublicChat.base.IsInPublicChatChannel
import com.vitorpamplona.quartz.nip28PublicChat.base.channel
import com.vitorpamplona.quartz.nip28PublicChat.base.reply
import com.vitorpamplona.quartz.nip37Drafts.ExposeInDraft
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class ChannelMessageEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseThreadedEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    IsInPublicChatChannel,
    ExposeInDraft,
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

    override fun eventHints(): List<EventIdHint> {
        var out: ArrayList<EventIdHint>? = null
        out = tags.fastMapNotNullTo(out, MarkedETag::parseAsHint)
        out = tags.fastMapNotNullTo(out, QTag::parseEventAsHint)
        out = citedNIP19().eventHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedEventIds(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = threadEventIds().appendTo(out)
        out = quotedEventIds().appendTo(out)
        out = citedNIP19().eventIdsTo(out)
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

    /** The addressables this message references (`a`), as address ids in tag order. */
    fun referencedAddresses(): List<String> = tags.fastMapNotNull { ATag.parseAddressId(it)?.takeIf(AddressSerializer::isAddressShape) }

    override fun channel() = markedRoot() ?: unmarkedRoot()

    override fun channelId() = channel()?.eventId

    override fun markedReplyTos() = super.markedReplyTos().filter { it != channelId() }

    override fun unmarkedReplyTos() = super.unmarkedReplyTos().filter { it != channelId() }

    override fun exposeInDraft() =
        tagArray<ChannelMessageEvent> {
            channel()?.let { markedETag(it) }
            reply()?.let { markedETag(it) }
        }

    companion object {
        const val KIND = 42
        const val ALT = "Public chat message"

        fun reply(
            post: String,
            replyingTo: EventHintBundle<ChannelMessageEvent>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChannelMessageEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, post, createdAt) {
            replyingTo.event.channel()?.let { channel(it) }
            reply(replyingTo)
            initializer()
        }

        fun message(
            post: String,
            channel: EventHintBundle<ChannelCreateEvent>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChannelMessageEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, post, createdAt) {
            channel(channel)
            initializer()
        }

        fun message(
            post: String,
            channel: ETag,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChannelMessageEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, post, createdAt) {
            channel(channel)
            initializer()
        }
    }
}
