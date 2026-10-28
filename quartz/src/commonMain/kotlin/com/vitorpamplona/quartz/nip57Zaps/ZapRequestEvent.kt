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
package com.vitorpamplona.quartz.nip57Zaps

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.events.eTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.pTag
import com.vitorpamplona.quartz.nip29RelayGroups.groupId
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class ZapRequestEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    AddressHintProvider,
    PubKeyHintProvider,
    SearchableEvent {
    // content is the public zap comment. Private-zap messages live encrypted in
    // the `anon` tag, not in content, so they are naturally excluded.
    override fun indexableContent() = content

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(content)
    }

    override fun pubKeyHints() = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys() = zappedAuthor()

    override fun eventHints() = tags.mapNotNull(ETag::parseAsHint)

    override fun linkedEventIds() = zappedPost()

    override fun addressHints() = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds() = zappedAddresses()

    fun zappedPost() = tags.mapNotNull(ETag::parseId)

    fun zappedAuthor() = tags.mapNotNull(PTag::parseKey)

    /** The zapped addressables (`a`), as validated address ids. */
    fun zappedAddresses(): List<String> = tags.mapNotNull(ATag::parseValidAddress)

    fun isPrivateZap() = tags.any { t -> t.size >= 2 && t[0] == "anon" && t[1].isNotBlank() }

    /**
     * True when the request carries an `anon` tag — a private zap (encrypted payload)
     * or an anonymous zap (blank value). In both cases [pubKey] is an ephemeral
     * throwaway key, not the sender's real identity.
     */
    fun hasAnonTag() = tags.any { t -> t.isNotEmpty() && t[0] == "anon" }

    fun getAnonTag(): String {
        val anonTag = tags.firstOrNull { t -> t.size >= 2 && t[0] == "anon" }
        if (anonTag != null) {
            val encNote = anonTag[1]
            if (encNote.isNotBlank()) {
                return encNote
            } else {
                throw IllegalStateException("Anon tag is empty.")
            }
        } else {
            throw IllegalStateException("This is not a private zap.")
        }
    }

    companion object {
        const val KIND = 9734

        /**
         * A zap request for [zappedEvent], paying [recipient] (its author when null), with the
         * receipt published to [relays]. [zapType] writes the `anon` marker: an ANONYMOUS request
         * must be signed by a throwaway key, a PRIVATE one goes through PrivateZapRequestBuilder.
         */
        fun build(
            zappedEvent: Event,
            relays: Set<NormalizedRelayUrl>,
            message: String,
            zapType: ZapReceiptEvent.ZapType,
            recipient: PTag? = null,
            pollOption: Int? = null,
            amountMillisats: Long? = null,
            lnurl: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ZapRequestEvent>.() -> Unit = {},
        ): EventTemplate<ZapRequestEvent> {
            require(zapType != ZapReceiptEvent.ZapType.NONZAP) { "Invalid zap type" }
            return eventTemplate(KIND, message, createdAt) {
                eTag(ETag(zappedEvent.id))
                pTag(recipient ?: PTag(zappedEvent.pubKey))
                receiptRelays(relays)
                zappedKind(zappedEvent.kind)
                if (zappedEvent is AddressableEvent) zappedAddress(zappedEvent.address())
                // Zapping NIP-29 group content is itself group content: carry the room's `h` tag so the
                // receipt the provider publishes is scoped to the room, which is what lets the host relay
                // serve it to the members (and to the recipient's `#h` notification query) instead of
                // dropping an unscoped kind-9735 nobody in the group will ever see. Same rule reactions
                // follow (ReactionAction copies the `h` tag onto the kind-7).
                zappedEvent.groupId()?.let { zappedGroup(it) }
                if (pollOption != null && pollOption >= 0) pollVote(pollOption)
                zapTail(amountMillisats, lnurl, zapType)
                initializer()
            }
        }

        /** A zap request for a user's profile, paying [user], with the receipt published to [relays]. */
        fun build(
            user: PTag,
            relays: Set<NormalizedRelayUrl>,
            message: String,
            zapType: ZapReceiptEvent.ZapType,
            amountMillisats: Long? = null,
            lnurl: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ZapRequestEvent>.() -> Unit = {},
        ): EventTemplate<ZapRequestEvent> {
            require(zapType != ZapReceiptEvent.ZapType.NONZAP) { "Invalid zap type" }
            return eventTemplate(KIND, message, createdAt) {
                pTag(user)
                receiptRelays(relays)
                zapTail(amountMillisats, lnurl, zapType)
                initializer()
            }
        }

        private fun TagArrayBuilder<ZapRequestEvent>.zapTail(
            amountMillisats: Long?,
            lnurl: String?,
            zapType: ZapReceiptEvent.ZapType,
        ) {
            if (amountMillisats != null && amountMillisats > 0) amount(amountMillisats)
            if (!lnurl.isNullOrBlank()) lnurl(lnurl)
            when (zapType) {
                // Valueless `anon` tag: a blank-valued one (`["anon", ""]`) is the
                // marker for an *unsigned private* zap and would make the throwaway
                // signer encrypt the message instead of keeping it public.
                ZapReceiptEvent.ZapType.ANONYMOUS -> anonymous()
                ZapReceiptEvent.ZapType.PRIVATE -> privateZap()
                else -> {}
            }
        }

        /** Signs [template] with [signer], or with a throwaway key when the zap is anonymous. */
        private suspend fun sign(
            template: EventTemplate<ZapRequestEvent>,
            zapType: ZapReceiptEvent.ZapType,
            signer: NostrSigner,
        ): ZapRequestEvent =
            if (zapType == ZapReceiptEvent.ZapType.ANONYMOUS) {
                NostrSignerInternal(KeyPair()).sign(template)
            } else {
                signer.sign(template)
            }

        suspend fun create(
            zappedEvent: Event,
            relays: Set<NormalizedRelayUrl>,
            signer: NostrSigner,
            pollOption: Int?,
            message: String,
            zapType: ZapReceiptEvent.ZapType,
            toUserPubHex: String?,
            amountMillisats: Long? = null,
            lnurl: String? = null,
            createdAt: Long = TimeUtils.now(),
        ): ZapRequestEvent {
            val template = build(zappedEvent, relays, message, zapType, toUserPubHex?.let { PTag(it) }, pollOption, amountMillisats, lnurl, createdAt)
            return sign(template, zapType, signer)
        }

        suspend fun create(
            userHex: String,
            relays: Set<NormalizedRelayUrl>,
            signer: NostrSigner,
            message: String,
            zapType: ZapReceiptEvent.ZapType,
            amountMillisats: Long? = null,
            lnurl: String? = null,
            createdAt: Long = TimeUtils.now(),
        ): ZapRequestEvent = sign(build(PTag(userHex), relays, message, zapType, amountMillisats, lnurl, createdAt), zapType, signer)
    }
}

@Deprecated(
    "Renamed to ZapRequestEvent. NIP-57 names kind 9734 the zap request.",
    ReplaceWith("ZapRequestEvent", "com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent"),
)
typealias LnZapRequestEvent = ZapRequestEvent
