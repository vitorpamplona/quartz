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
package com.vitorpamplona.quartz.nip38UserStatus

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.aTag.aTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.events.eTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.pTag
import com.vitorpamplona.quartz.nip30CustomEmoji.EmojiUrlTag
import com.vitorpamplona.quartz.nip30CustomEmoji.emojis
import com.vitorpamplona.quartz.nip38UserStatus.tags.StatusEmojiTag
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class UserStatusEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent,
    PubKeyHintProvider,
    EventHintProvider,
    AddressHintProvider {
    override fun indexableContent() = content

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(content)
    }

    // The profile / note / address a status points at (NIP-38 `p`, `e`, `a`): one of each at most.
    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(referencedProfile())

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint)

    override fun linkedEventIds(): List<HexKey> = listOfNotNull(referencedEventId())

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds(): List<String> = listOfNotNull(referencedAddressId())

    /** The profile this status links to (NIP-38 `p`). */
    fun referencedProfile(): HexKey? = tags.firstNotNullOfOrNull(PTag::parseKey)

    /** The note this status links to (NIP-38 `e`). */
    fun referencedEventId(): HexKey? = tags.firstNotNullOfOrNull(ETag::parseId)

    /** The addressable this status links to (NIP-38 `a`, e.g. a live activity or a track), as an address id. */
    fun referencedAddressId(): String? = tags.firstNotNullOfOrNull(ATag::parseValidAddress)

    fun firstTaggedUrl() = tags.firstTagValue("r")

    /**
     * The plain status emoji from a 2-element `["emoji", "🎉"]` tag (Buzz's status shape), or null.
     * NIP-30 custom emoji tags (3+ elements) are not status emoji and are read by `taggedEmojis()`.
     */
    fun statusEmoji(): String? = tags.firstNotNullOfOrNull(StatusEmojiTag::parse)

    /**
     * True when this status carries nothing to show: blank text and no [statusEmoji]. Buzz clears a
     * status that way, and an emoji with blank text is still a status ("🌴" alone reads as away).
     */
    fun isCleared(): Boolean = content.isBlank() && statusEmoji() == null

    companion object {
        const val KIND = 30315

        const val GENERAL = "general"
        const val MUSIC = "music"

        /**
         * A status of [type] (`d`: [GENERAL], [MUSIC], …) saying [msg], optionally pointing at a
         * [url], a [profile], an [event] or an [address], with NIP-30 [emojis] for its text or
         * Buzz's plain [statusEmoji].
         */
        fun build(
            msg: String,
            type: String = GENERAL,
            expiration: Long? = null,
            url: String? = null,
            profile: PTag? = null,
            event: ETag? = null,
            address: ATag? = null,
            emojis: List<EmojiUrlTag> = emptyList(),
            statusEmoji: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<UserStatusEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, msg, createdAt) {
            dTag(type)
            expiration?.let { expiration(it) }
            url?.let { statusUrl(it) }
            profile?.let { pTag(it) }
            event?.let { eTag(it) }
            address?.let { aTag(it) }
            emojis(emojis)
            statusEmoji?.let { statusEmoji(it) }
            initializer()
        }

        /** [addressableId] must be a valid `kind:pubkey:d` address; an invalid one is left out. */
        suspend fun create(
            msg: String,
            type: String = GENERAL,
            expiration: Long? = null,
            url: String? = null,
            profileId: HexKey? = null,
            eventId: HexKey? = null,
            addressableId: String? = null,
            emojiTags: List<EmojiUrlTag>? = null,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): UserStatusEvent =
            signer.sign(
                build(
                    msg = msg,
                    type = type,
                    expiration = expiration,
                    url = url,
                    profile = profileId?.let { PTag(it) },
                    event = eventId?.let { ETag(it) },
                    address = addressableId?.let { ATag.parse(it, null) },
                    emojis = emojiTags ?: emptyList(),
                    createdAt = createdAt,
                ),
            )

        suspend fun update(
            event: UserStatusEvent,
            newStatus: String,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): UserStatusEvent {
            val tags = event.tags
            return signer.sign(createdAt, KIND, tags, newStatus)
        }

        suspend fun clear(
            event: UserStatusEvent,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): UserStatusEvent {
            val msg = ""
            val tags = event.tags.filter(DTag::isTag)
            return signer.sign(createdAt, KIND, tags.toTypedArray(), msg)
        }
    }
}

@Deprecated(
    "Renamed to UserStatusEvent. NIP-38 names kind 30315 the user status.",
    ReplaceWith("UserStatusEvent", "com.vitorpamplona.quartz.nip38UserStatus.UserStatusEvent"),
)
typealias StatusEvent = UserStatusEvent
