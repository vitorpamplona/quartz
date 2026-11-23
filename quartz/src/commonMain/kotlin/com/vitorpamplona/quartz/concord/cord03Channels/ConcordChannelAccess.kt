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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRosterContent
import com.vitorpamplona.quartz.concord.cord06Rekey.ConcordRekey
import com.vitorpamplona.quartz.concord.cord06Rekey.RekeyBlob
import com.vitorpamplona.quartz.concord.cord06Rekey.RekeyPayload
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.concord.envelope.OpenedStreamEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A kind-3303 **delivery** (CORD-06 §1, CORD-03 §5): keys handed to members who are owed them, on a
 * Channel's access plane. Never a rotation: it advances no epoch, and nothing it omits removes
 * anyone. [form] is `access` (a Channel key, or a writer or certifier secret by [scopeIdHex]) or
 * `history` (an earlier epoch's read-only address and conversation key).
 */
class ChannelDelivery(
    val deliverer: HexKey,
    val form: String,
    val scopeIdHex: HexKey,
    val newEpoch: Long,
    /** Writer and certifier scopes: the pubkey the delivered secret derives to. */
    val writer: HexKey?,
    /** Writer certificates riding the delivery: recipient → signature (CORD-03 §1). */
    val certs: Map<HexKey, String>,
    /** `[part, parts]` of a writer epoch's minting delivery, counted from 1. */
    val mint: Pair<Long, Long>?,
    val blobs: List<RekeyBlob>,
)

/** An earlier epoch of a Channel, read-only (CORD-03 §5): its Chat Plane [address] and [conversationKey], never its secret. */
class ChannelHistoryKey(
    val epoch: Long,
    val address: HexKey,
    val conversationKey: ByteArray,
)

/**
 * A Channel's **access plane** (CORD-03 §5): `group_key("concord/channel-access", community_root,
 * channel_id, root_epoch)`, where any member holding a Channel's key delivers it to the members
 * the fold entitles and who lack it, and from which an entitled member without the key collects it.
 */
object ConcordChannelAccess {
    const val TAG_DELIVERY = "delivery"
    const val TAG_WRITER = "writer"
    const val TAG_CERT = "cert"
    const val TAG_MINT = "mint"
    const val FORM_ACCESS = "access"
    const val FORM_HISTORY = "history"

    /** A history blob's plaintext width: `history_scope[32] ‖ epoch_be[8] ‖ address[32] ‖ conv_key[32]`. */
    const val SIZE_HISTORY = 104

    /** An access delivery's blob cap (CORD-06 §1); 60 for the 104-byte history form, 48 with certificates. */
    const val MAX_BLOBS = 80
    const val MAX_HISTORY_BLOBS = 60
    const val MAX_BLOBS_WITH_CERTS = 48

    /** The most blobs a reader accepts in one event (CORD-06 §1). */
    const val MAX_BLOBS_RECEIVED = 120

    /** The furthest past the epoch a member holds that it adopts a delivered key (CORD-03 §5). */
    const val MAX_EPOCH_JUMP = 1024L

    /** The access plane of [channelId] under the `community_root` at [rootEpoch]. */
    fun plane(
        communityRoot: ByteArray,
        channelId: ByteArray,
        rootEpoch: Long,
    ): GroupKey = ConcordKeyDerivation.channelAccessKey(communityRoot, channelId, rootEpoch)

    private fun strictDecimal(value: String?): Long? {
        if (value.isNullOrEmpty() || value.length > 19 || !value.all { it in '0'..'9' }) return null
        if (value.length > 1 && value[0] == '0') return null
        return value.toLongOrNull()
    }

    /** The only value of [name] among [tags]: null when absent, [AMBIGUOUS] when repeated. */
    private fun unique(
        tags: Array<Array<String>>,
        name: String,
    ): String? {
        var found: String? = null
        for (t in tags) {
            if (t.isEmpty() || t[0] != name) continue
            if (found != null) return AMBIGUOUS
            found = t.getOrNull(1) ?: return AMBIGUOUS
        }
        return found
    }

    private const val AMBIGUOUS = "\u0000ambiguous"

    /**
     * The delivery an opened access-plane wrap carries, or null when it is none: a kind-3303 rumor
     * under an encrypted seal, with exactly one `delivery`, `scope` and `newepoch`. A form this
     * reader doesn't know still parses, and carries no verdict for anyone (CORD-06 §1).
     */
    fun parse(opened: OpenedStreamEvent): ChannelDelivery? {
        val rumor = opened.rumor
        if (rumor.kind != ConcordRekey.KIND) return null
        if (opened.sealKind != ConcordStreamEnvelope.KIND_SEAL_ENCRYPTED) return null
        val tags = rumor.tags
        val form = unique(tags, TAG_DELIVERY)?.takeIf { it != AMBIGUOUS } ?: return null
        val scope = unique(tags, ConcordRekey.TAG_SCOPE)?.takeIf { RankedRosterContent.isHex64(it) } ?: return null
        val epoch = strictDecimal(unique(tags, ConcordRekey.TAG_NEWEPOCH)) ?: return null
        val writerTag = unique(tags, TAG_WRITER)
        if (writerTag == AMBIGUOUS || (writerTag != null && !RankedRosterContent.isHex64(writerTag))) return null
        // A malformed certificate is no certificate; it never refuses the delivery it rides.
        val certs = LinkedHashMap<HexKey, String>()
        for (t in tags) {
            if (certs.size >= MAX_BLOBS_RECEIVED) break
            if (t.size == 3 && t[0] == TAG_CERT && RankedRosterContent.isHex64(t[1]) && t[2].length == 128 && t[2].all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                certs.getOrPut(t[1].lowercase()) { t[2].lowercase() }
            }
        }
        val mint =
            tags.firstOrNull { it.isNotEmpty() && it[0] == TAG_MINT }?.let { t ->
                val part = t.getOrNull(1)?.toLongOrNull()
                val parts = t.getOrNull(2)?.toLongOrNull()
                if (part != null && parts != null && part >= 1 && part <= parts && parts <= 0xFFFFFFFFL) part to parts else null
            }
        val blobs = ConcordRekey.decodeContent(rumor.content).take(MAX_BLOBS_RECEIVED)
        return ChannelDelivery(opened.author.lowercase(), form, scope.lowercase(), epoch, writerTag?.lowercase(), certs, mint, blobs)
    }

    /**
     * Whether a reader adopts a key [deliverer] handed [recipient] for [channelIdHex] (CORD-03 §5):
     * the reader's fold entitles the recipient (VIEW_CHANNEL, judged as a Private Channel), and the
     * deliverer holds the Channel (entitled, MANAGE_CHANNELS, or the owner) and isn't banned.
     */
    fun deliveryTrusted(
        state: ConcordCommunityState,
        channelIdHex: HexKey,
        recipient: HexKey,
        deliverer: HexKey,
    ): Boolean {
        val authority = state.authority
        if (authority.isBanned(deliverer)) return false
        if (!state.channelPermissions(recipient, channelIdHex, isPrivate = true).has(ConcordPermissions.VIEW_CHANNEL)) return false
        if (authority.isOwner(deliverer)) return true
        return state.channelPermissions(deliverer, channelIdHex, isPrivate = true).has(ConcordPermissions.VIEW_CHANNEL) ||
            authority.hasPermission(deliverer, ConcordPermissions.MANAGE_CHANNELS)
    }

    /**
     * The Channel key an `access` [delivery] hands [recipientSigner] for [channelId], or null when it
     * holds no blob for them, the blob won't open, or it names another scope or epoch. The caller
     * checks [deliveryTrusted] and the epoch bound first.
     */
    suspend fun openAccess(
        delivery: ChannelDelivery,
        recipientSigner: NostrSigner,
        scopeId: ByteArray,
    ): ByteArray? {
        if (delivery.form != FORM_ACCESS || delivery.scopeIdHex != scopeId.toHexKey()) return null
        return ConcordRekey.findNewKeyWithSigner(delivery.blobs, recipientSigner, delivery.deliverer.hexToByteArray(), scopeId, delivery.newEpoch)
    }

    /**
     * The earlier epoch a `history` [delivery] hands [recipientSigner] for [channelId] (CORD-03 §5),
     * or null: the blob must be 104 bytes bound to the Channel's history scope and the delivery's
     * epoch.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun openHistory(
        delivery: ChannelDelivery,
        recipientSigner: NostrSigner,
        channelId: ByteArray,
    ): ChannelHistoryKey? {
        if (delivery.form != FORM_HISTORY) return null
        val scope = ConcordKeyDerivation.historyScope(channelId)
        if (delivery.scopeIdHex != scope.toHexKey()) return null
        val deliverer = delivery.deliverer.hexToByteArray()
        val locator = ConcordKeyDerivation.recipientLocator(deliverer, recipientSigner.pubKey.hexToByteArray(), scope, delivery.newEpoch).toHexKey()
        val blob = delivery.blobs.firstOrNull { it.locator == locator } ?: return null
        return try {
            val bytes = Base64.Default.decode(recipientSigner.nip44Decrypt(blob.wrapped, delivery.deliverer))
            if (bytes.size != SIZE_HISTORY) return null
            if (!bytes.copyOfRange(0, 32).contentEquals(scope)) return null
            var epoch = 0L
            for (i in 0 until 8) epoch = (epoch shl 8) or (bytes[32 + i].toLong() and 0xFF)
            if (epoch != delivery.newEpoch) return null
            ChannelHistoryKey(epoch, bytes.copyOfRange(40, 72).toHexKey(), bytes.copyOfRange(72, 104))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** What a reader does with one `access` delivery it holds (CORD-03 §5), decided before any signer call. */
    enum class AccessVerdict {
        /** Not an `access` delivery of this Channel, or it holds no blob for the reader: settle it. */
        NOT_FOR_ME,

        /** It hands an epoch the reader already holds or has passed: settle it. */
        STALE,

        /**
         * The fold does not trust it yet (the deliverer's or the reader's own Grant may still fold),
         * or it jumps more than [MAX_EPOCH_JUMP] past the held epoch: keep it for a later fold.
         */
        WAIT,

        /** Open the reader's blob ([openAccessFor]). */
        OPEN,
    }

    /**
     * [AccessVerdict] for [delivery] of Channel [channelIdHex] held by [recipient], who holds the
     * Channel at [heldEpoch] (null: no key yet). [trusted] is [deliveryTrusted] against the
     * reader's fold, asked last since it is the costliest check.
     */
    fun accessVerdict(
        delivery: ChannelDelivery,
        channelIdHex: HexKey,
        recipient: HexKey,
        heldEpoch: Long?,
        trusted: () -> Boolean,
    ): AccessVerdict {
        if (delivery.form != FORM_ACCESS || !delivery.scopeIdHex.equals(channelIdHex, ignoreCase = true)) return AccessVerdict.NOT_FOR_ME
        if (!holdsAccessBlobFor(delivery, recipient, channelIdHex)) return AccessVerdict.NOT_FOR_ME
        if (heldEpoch != null && delivery.newEpoch <= heldEpoch) return AccessVerdict.STALE
        if (heldEpoch != null && delivery.newEpoch > heldEpoch + MAX_EPOCH_JUMP) return AccessVerdict.WAIT
        return if (trusted()) AccessVerdict.OPEN else AccessVerdict.WAIT
    }

    /** Whether [delivery] carries a blob at [recipient]'s locator (CORD-06 §1): no signer call, no decryption. */
    fun holdsAccessBlobFor(
        delivery: ChannelDelivery,
        recipient: HexKey,
        scopeIdHex: HexKey,
    ): Boolean {
        val locator = recipientLocatorOrNull(delivery, recipient, scopeIdHex) ?: return false
        return delivery.blobs.any { it.locator == locator }
    }

    private fun recipientLocatorOrNull(
        delivery: ChannelDelivery,
        recipient: HexKey,
        scopeIdHex: HexKey,
    ): HexKey? =
        try {
            ConcordKeyDerivation.recipientLocator(delivery.deliverer.hexToByteArray(), recipient.hexToByteArray(), scopeIdHex.hexToByteArray(), delivery.newEpoch).toHexKey()
        } catch (_: Exception) {
            null
        }

    /** What opening the reader's blob of an `access` delivery came to ([openAccessFor]). */
    sealed interface AccessOpening {
        /** The Channel key it hands the reader. */
        class Key(
            val key: ByteArray,
        ) : AccessOpening

        /** No blob for the reader, or it decrypts to something that is not this Channel's key at this epoch: settled. */
        data object Refused : AccessOpening

        /**
         * The signer did not answer (a NIP-46 timeout, a NIP-55 rejection, a signer not reachable):
         * nothing is known about the blob, so the delivery stays for a later try.
         */
        data object SignerUnavailable : AccessOpening
    }

    /**
     * [openAccess] telling a signer that could not answer ([AccessOpening.SignerUnavailable]) apart
     * from a blob that is not the reader's to use ([AccessOpening.Refused]), which [openAccess] folds
     * into one null. Settling a delivery on a signer error leaves the member keyless: every holder
     * reads them as served and none delivers again.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun openAccessFor(
        delivery: ChannelDelivery,
        signer: NostrSigner,
        channelIdHex: HexKey,
    ): AccessOpening {
        if (delivery.form != FORM_ACCESS || !delivery.scopeIdHex.equals(channelIdHex, ignoreCase = true)) return AccessOpening.Refused
        val scope = channelIdHex.hexToByteArray()
        val locator = recipientLocatorOrNull(delivery, signer.pubKey, channelIdHex) ?: return AccessOpening.Refused
        val blob = delivery.blobs.firstOrNull { it.locator == locator } ?: return AccessOpening.Refused
        val plaintext =
            try {
                signer.nip44Decrypt(blob.wrapped, delivery.deliverer.lowercase())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return AccessOpening.SignerUnavailable
            }
        val payload =
            try {
                RekeyPayload.decode(Base64.Default.decode(plaintext))
            } catch (_: Exception) {
                null
            } ?: return AccessOpening.Refused
        if (!payload.scopeId.contentEquals(scope) || payload.epoch != delivery.newEpoch) return AccessOpening.Refused
        return AccessOpening.Key(payload.newKey)
    }

    /** The tags of an `access` delivery of [scopeId] at [newEpoch] (CORD-06 §1): no `prevepoch`, `prevcommit` or `chunk`. */
    fun accessTags(
        scopeId: ByteArray,
        newEpoch: Long,
        writer: HexKey? = null,
    ): Array<Array<String>> {
        val base = arrayOf(arrayOf(ConcordRekey.TAG_SCOPE, scopeId.toHexKey()), arrayOf(ConcordRekey.TAG_NEWEPOCH, newEpoch.toString()), arrayOf(TAG_DELIVERY, FORM_ACCESS))
        return if (writer == null) base else base + arrayOf(arrayOf(TAG_WRITER, writer.lowercase()))
    }
}
