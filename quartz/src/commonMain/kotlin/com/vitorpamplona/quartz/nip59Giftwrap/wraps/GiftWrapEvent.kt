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
package com.vitorpamplona.quartz.nip59Giftwrap.wraps

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUserIds
import com.vitorpamplona.quartz.nip21UriScheme.toNostrUri
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.nip44Encryption.Nip44v1
import com.vitorpamplona.quartz.nip59Giftwrap.HasInnerEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile

/**
 * Caller hook to adjust the finished wrap template right before the ephemeral
 * key signs it — e.g. mining a NIP-13 proof of work into it. Receives the
 * ephemeral key's pubkey because the NIP-01 id (what a nonce commits to)
 * includes it, and the key never leaves [GiftWrapEvent.create]. Must return a
 * template of the same kind; the default is the identity.
 */
typealias GiftWrapTemplateConversion = (template: EventTemplate<GiftWrapEvent>, ephemeralPubKey: HexKey) -> EventTemplate<GiftWrapEvent>

@Immutable
open class GiftWrapEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
    kind: Int = KIND,
) : Event(id, pubKey, createdAt, kind, tags, content, sig),
    HasInnerEvent,
    PubKeyHintProvider {
    // The recipient `p` (NIP-17 lets it carry the recipient's DM inbox relay, see
    // [create]'s `recipientRelayHint`). Tags only: the content is never opened here.
    override fun pubKeyHints() = tags.fastMapNotNull(PTag::parseAsHint)

    /** `RECIPIENT`: the user the wrap is addressed to (`p`). */
    override fun linkedPubKeys() = recipientKeys()

    // `@Volatile`: set by the decrypting coroutine in [unwrapThrowing], read
    // by relay socket threads walking the wrap → seal → rumor chain.
    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    @Volatile
    override var innerEventId: HexKey? = null

    open fun copyNoContent(): GiftWrapEvent {
        val copy =
            GiftWrapEvent(
                id,
                pubKey,
                createdAt,
                tags,
                "",
                sig,
            )

        copy.innerEventId = innerEventId

        return copy
    }

    override fun isContentEncoded() = true

    /**
     * The event this wrap carries, **not verified**. NIP-59 wraps a [SealEvent]; other protocols
     * (NIP-AC calls, ContextVM) wrap a signed event of their own. The wrap's signature only proves
     * its throwaway key signed it, so the caller must check the inner event's signature before
     * trusting its author: unchecked, anyone able to encrypt to us could hand us an event "from"
     * any pubkey. Verifying is left to the caller so it can skip events it already holds (an app
     * that has the seal cached need not verify it again).
     *
     * @throws IllegalStateException when the content is not NIP-44.
     */
    suspend fun unwrapThrowing(signer: NostrSigner): Event {
        val giftStr = plainContent(signer)
        val gift = fromJson(giftStr)

        innerEventId = gift.id

        return gift
    }

    suspend fun unwrapOrNull(signer: NostrSigner): Event? =
        try {
            unwrapThrowing(signer)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d("GiftWrapEvent") { "Couldn't Decrypt the content " + this.toNostrUri() }
            null
        }

    /**
     * Peels both NIP-59 layers, kind:1059 wrap → kind:13 [SealEvent] → the rumor, where
     * [unwrapOrNull] peels only the wrap. The rumor's author is the seal's signer, which the
     * seal's encryption proves (only that key and ours derive its conversation key).
     *
     * Null when the wrap does not carry a seal: NIP-59 wraps nothing else, and an event that
     * skipped the seal names whatever author it likes. Use [unwrapOrNull] for a protocol that
     * wraps a signed event directly. Either step failing returns null.
     */
    suspend fun unwrapAndUnsealOrNull(signer: NostrSigner): Event? {
        val inner = unwrapOrNull(signer) ?: return null
        return (inner as? SealEvent)?.unsealOrNull(signer)
    }

    private suspend fun plainContent(signer: NostrSigner): String {
        if (content.isEmpty()) return ""

        // NIP-44 only (v2, or v1 for wraps from before v2): never the NIP-04 forms the lenient
        // decrypt still opens for old direct messages.
        check(Nip44.payloadVersion(content) >= Nip44v1.EncryptedInfo.V) { "Gift wrap ${id.take(8)} is not NIP-44 encrypted" }

        // The wrap's key is a throwaway, used once: its conversation key stays out of the cache.
        return signer.nip44DecryptOnce(content, pubKey)
    }

    fun recipientPubKey() = tags.firstTagValue("p")

    /** Every well-formed `p` key, in tag order. NIP-59 writes one, the recipient. */
    fun recipientKeys(): List<HexKey> = tags.taggedUserIds()

    companion object {
        const val KIND = 1059
        const val ALT = "Encrypted event"

        /**
         * The unsigned wrap: [event] NIP-44 encrypted by [wrapSigner] to [recipient]. NIP-59 signs a
         * wrap with a throwaway key, so [wrapSigner] is that key, and the caller signs the template
         * with it: a fresh `NostrSignerSync()` per wrap, never the user's own, which would put the
         * sender's identity on the outside of the wrap. [expirationDelta] adds an `expiration` that far past the (randomized) [createdAt],
         * plus two days so the randomization never expires the wrap before it arrives.
         */
        fun build(
            event: Event,
            recipient: PTag,
            wrapSigner: NostrSignerSync,
            expirationDelta: Long? = null,
            createdAt: Long = TimeUtils.randomWithTwoDays(),
            initializer: TagArrayBuilder<GiftWrapEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, encryptWithThrowawayKey(event.toJson(), recipient.pubKey, wrapSigner), createdAt) {
            recipient(recipient)
            expirationDelta?.let { expiration(createdAt + it + TimeUtils.twoDays()) }
            initializer()
        }

        /**
         * [NostrSignerSync.nip44Encrypt] without the shared conversation-key cache: the wrap key
         * is used once, so caching its key would only evict a real peer's (and keep a throwaway
         * private key in a process-wide cache).
         */
        internal fun encryptWithThrowawayKey(
            plaintext: String,
            toPublicKey: HexKey,
            wrapSigner: NostrSignerSync,
        ): String {
            val privKey = wrapSigner.keyPair.privKey ?: throw SignerExceptions.ReadOnlyException()
            if (plaintext.isBlank()) return ""
            val conversationKey = Nip44.v2.computeConversationKey(privKey, toPublicKey.hexToByteArray())
            return Nip44.v2.encrypt(plaintext, conversationKey).encodePayload()
        }

        /**
         * Build a NIP-59 gift wrap addressed to `recipientPubKey`.
         *
         * Per NIP-17 §Publishing, the `p` tag on the wrap MAY carry the
         * recipient's primary DM inbox relay as a hint, so other clients
         * the recipient runs (or relays acting as inbox routers) can locate
         * the wrap without a separate kind:10050 lookup. Pass it via
         * [recipientRelayHint] — `null` (the default) preserves the
         * historical 2-element `["p", pubkey]` shape.
         *
         * [templateConversion] runs on the finished template right before the
         * ephemeral key signs it. This is how a caller mines a NIP-13 proof
         * of work into the wrap itself (the ephemeral-key envelope, never the
         * inner seal or rumor) so DM relays can PoW-filter inbox spam —
         * without this NIP-59 code knowing anything about mining.
         */
        fun create(
            event: Event,
            recipientPubKey: HexKey,
            expirationDelta: Long? = null,
            createdAt: Long = TimeUtils.randomWithTwoDays(),
            recipientRelayHint: NormalizedRelayUrl? = null,
            templateConversion: GiftWrapTemplateConversion = { template, _ -> template },
        ): GiftWrapEvent {
            val signer = NostrSignerSync(KeyPair()) // GiftWrap is always a random key

            val template = build(event, PTag(recipientPubKey, recipientRelayHint), signer, expirationDelta, createdAt)

            val readyToSign = templateConversion(template, signer.pubKey)

            return signer.sign(
                createdAt = readyToSign.createdAt,
                kind = readyToSign.kind,
                tags = readyToSign.tags,
                content = readyToSign.content,
            )
        }
    }
}
