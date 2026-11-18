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

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRosterContent
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.ConcordLabels
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArrayOrNull
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1Instance
import com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One window in which a restricted Channel's posts go to a **writer address** (CORD-03 §1): the
 * writer [epoch], its address [writerPubKey] (`group_key("concord/channel-writer", writer_secret,
 * channel_id, epoch).pk`, which signs the wraps; the Channel's read key still encrypts them), and
 * the [certifier] whose signatures vouch for its writers. The window runs from [fromMs], inclusive,
 * to [toMs], exclusive (null while it is the live window).
 */
data class WriterWindow(
    val epoch: Long,
    val writerPubKey: HexKey,
    val fromMs: Long,
    val toMs: Long?,
    val certifier: HexKey?,
    val root: Long?,
)

/** A `stop` entry (CORD-03 §1): from [atMs] (or the earlier [fromMs]) the certificate its window's epoch gave [member] vouches for nothing. */
data class WriterStop(
    val atMs: Long,
    val member: HexKey,
    val fromMs: Long,
)

/**
 * A restricted Channel's Writers ledger (CORD-03 §1, vsk 18 at `writers_locator(community_id,
 * channel_id)`), folded to its head: every writer window, live and retired, the stops, and whether
 * the Channel posts at a writer address now ([isRestricted]).
 */
data class ConcordWritersLedger(
    val channelIdHex: HexKey,
    val windows: List<WriterWindow>,
    val stops: List<WriterStop>,
    /** True while the newest moving entry is a writer entry: posts go to [live]'s address. */
    val isRestricted: Boolean,
) {
    /** The live writer window, or null while the Channel posts at its read address. */
    val live: WriterWindow? get() = if (isRestricted) windows.lastOrNull { it.toMs == null } else null

    /** Every writer address the ledger names, live or retired: what a reader subscribes to (CORD-03 §3). */
    fun writerAddresses(): List<HexKey> = windows.map { it.writerPubKey }

    /** The window whose address is [writerPubKey], or null. */
    fun windowAt(writerPubKey: HexKey): WriterWindow? = windows.firstOrNull { it.writerPubKey.equals(writerPubKey, ignoreCase = true) }

    /** Whether a `stop` names [member] in [window] at or before [postMs]. */
    fun isStopped(
        window: WriterWindow,
        member: HexKey,
        postMs: Long,
    ): Boolean =
        stops.any { s ->
            s.member.equals(member, ignoreCase = true) && s.fromMs <= postMs &&
                s.atMs >= window.fromMs && (window.toMs == null || s.atMs <= window.toMs)
        }

    /**
     * Whether a post at [window]'s writer address stands (CORD-03 §1): dated before the window
     * ends, carrying a certificate the window's certifier signed for its [author] (a window naming
     * no certifier is judged on its time alone), and dated before any `stop` naming the author
     * there. A post by the owner, or by an unbanned MANAGE_CHANNELS holder ([privileged]), always
     * stands. [postMs] is the post's time in milliseconds (CORD-02 §4).
     */
    fun postStands(
        communityId: ByteArray,
        window: WriterWindow,
        author: HexKey,
        postMs: Long,
        certificate: String?,
        privileged: Boolean,
    ): Boolean {
        if (privileged) return true
        if (window.toMs != null && postMs >= window.toMs) return false
        if (window.certifier != null) {
            val cert = certificate ?: return false
            if (!verifyCertificate(communityId, channelIdHex.hexToByteArray(), window.epoch, author, window.certifier, cert)) return false
        }
        return !isStopped(window, author, postMs)
    }

    companion object {
        const val MAX_ENTRIES = 64
        const val MAX_RETIRED = 64
        private const val MAX_EPOCH = (1L shl 53) - 1

        private fun hexKeyOf(e: Any?): String? = ((e as? JsonPrimitive)?.takeIf { it.isString }?.content)?.takeIf { RankedRosterContent.isHex64(it) }?.lowercase()

        private fun uint(e: Any?): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.content.toLongOrNull()?.takeIf { it >= 0 }
        }

        /** Whether [hex] names a valid x-only point (an even-y compressed key that parses). */
        private fun isPoint(hex: String): Boolean = Schnorr256k1.pubkeyParse(byteArrayOf(0x02) + hex.hexToByteArray()) != null

        /**
         * Reads a ledger edition's [content] for the Channel at coordinate [entityIdHex] of the
         * Community [communityId], or null when it is malformed (CORD-03 §1 lists every way).
         */
        fun parse(
            content: String,
            communityId: ByteArray,
            entityIdHex: String,
        ): ConcordWritersLedger? {
            val c = RankedRosterContent.parseObject(content) ?: return null
            val channel = hexKeyOf(c["channel"]) ?: return null
            if (ConcordKeyDerivation.writersCoordinate(communityId, channel.hexToByteArray()).toHexKey() != entityIdHex.lowercase()) return null
            val ledger = c["ledger"] as? JsonArray ?: return null
            val retiredEl = c["retired"]
            val retired = if (retiredEl == null) JsonArray(emptyList()) else retiredEl as? JsonArray ?: return null
            if (ledger.size > MAX_ENTRIES || retired.size > MAX_RETIRED) return null

            val windows = ArrayList<WriterWindow>()
            val stops = ArrayList<WriterStop>()
            var lastEpoch = 0L
            for (r in retired) {
                val arr = r as? JsonArray ?: return null
                if (arr.size != 4 && arr.size != 5) return null
                val epoch = uint(arr[0])?.takeIf { it in 1..MAX_EPOCH } ?: return null
                val writer = hexKeyOf(arr[1])?.takeIf { isPoint(it) } ?: return null
                val from = uint(arr[2]) ?: return null
                val to = uint(arr[3]) ?: return null
                if (from > to) return null
                val certifier = if (arr.size == 5) hexKeyOf(arr[4])?.takeIf { isPoint(it) } ?: return null else null
                if (epoch <= lastEpoch) return null
                lastEpoch = epoch
                windows.add(WriterWindow(epoch, writer, from, to, certifier, null))
            }

            var lastAt = -1L
            var openAt: Long? = null
            var lastMovingIsOpen = false
            var hadMoving = false
            var current: WriterWindow? = null
            for (item in ledger) {
                val e = item as? JsonObject ?: return null
                val at = uint(e["at"]) ?: return null
                if (at < lastAt) return null
                lastAt = at
                when {
                    e.containsKey("open") -> {
                        if ((e["open"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true) return null
                        current?.let { windows[windows.indexOf(it)] = it.copy(toMs = at) }
                        current = null
                        lastMovingIsOpen = true
                        hadMoving = true
                        openAt = at
                    }
                    e.containsKey("stop") -> {
                        val member = hexKeyOf(e["stop"])?.takeIf { isPoint(it) } ?: return null
                        if (hadMoving && lastMovingIsOpen) return null
                        val from =
                            (e["from"] as? JsonPrimitive)
                                ?.takeIf { !it.isString }
                                ?.content
                                ?.toLongOrNull()
                                ?.takeIf { it >= 0 }
                        stops.add(WriterStop(at, member, minOf(from ?: at, at)))
                    }
                    e.containsKey("epoch") || e.containsKey("writer") -> {
                        val epoch = uint(e["epoch"])?.takeIf { it in 1..MAX_EPOCH } ?: return null
                        val writer = hexKeyOf(e["writer"])?.takeIf { isPoint(it) } ?: return null
                        val root = uint(e["root"]) ?: return null
                        val by = hexKeyOf(e["by"]) ?: return null
                        if (by.isEmpty()) return null
                        val certifierEl = e["certifier"]
                        val certifier = if (certifierEl == null) null else hexKeyOf(certifierEl)?.takeIf { isPoint(it) } ?: return null
                        if (epoch <= lastEpoch) return null
                        lastEpoch = epoch
                        current?.let { windows[windows.indexOf(it)] = it.copy(toMs = at) }
                        val w = WriterWindow(epoch, writer, at, null, certifier, root)
                        windows.add(w)
                        current = w
                        lastMovingIsOpen = false
                        hadMoving = true
                    }
                    // A kind a reader doesn't know is carried verbatim and moves nothing.
                    else -> {}
                }
            }
            return ConcordWritersLedger(channel, windows, stops, isRestricted = hadMoving && !lastMovingIsOpen && current != null)
        }

        /**
         * A writer certificate's digest (CORD-03 §1): `sha256("concord/writer-cert" ‖ community_id ‖
         * channel_id ‖ epoch_be[8] ‖ writer)`.
         */
        fun certificateDigest(
            communityId: ByteArray,
            channelId: ByteArray,
            epoch: Long,
            writer: ByteArray,
        ): ByteArray {
            val epochBytes = ByteArray(8)
            for (i in 0 until 8) epochBytes[i] = (epoch ushr (56 - 8 * i)).toByte()
            return sha256(ConcordLabels.WRITER_CERT.encodeToByteArray() + communityId + channelId + epochBytes + writer)
        }

        /** The id of the never-published kind-3320 event a certifier signs for a certificate (CORD-03 §1). */
        fun certificateEventId(
            certifier: HexKey,
            digest: ByteArray,
        ): ByteArray = EventHasher.hashIdBytes(certifier.lowercase(), 0, KIND_CERTIFICATE_PREIMAGE, arrayOf(arrayOf("digest", digest.toHexKey())), "")

        /** Kind of the writer certificate's preimage event, never published (CORD-02 Appendix B). */
        const val KIND_CERTIFICATE_PREIMAGE = 3320

        /** Whether [signatureHex] is [certifier]'s certificate for [writer] at [epoch] of the Channel. */
        fun verifyCertificate(
            communityId: ByteArray,
            channelId: ByteArray,
            epoch: Long,
            writer: HexKey,
            certifier: HexKey,
            signatureHex: String,
        ): Boolean {
            val sig = signatureHex.hexToByteArrayOrNull()?.takeIf { it.size == 64 } ?: return false
            val writerBytes = writer.hexToByteArrayOrNull()?.takeIf { it.size == 32 } ?: return false
            val certifierBytes = certifier.hexToByteArrayOrNull()?.takeIf { it.size == 32 } ?: return false
            val id = certificateEventId(certifier, certificateDigest(communityId, channelId, epoch, writerBytes))
            return try {
                Secp256k1Instance.verifySchnorr(sig, id, certifierBytes)
            } catch (_: Exception) {
                false
            }
        }

        /** Signs a writer certificate for [writer] at [epoch] with the certifier's [certifierSecret]. */
        fun signCertificate(
            communityId: ByteArray,
            channelId: ByteArray,
            epoch: Long,
            writer: HexKey,
            certifierSecret: ByteArray,
            certifierPubKey: HexKey,
        ): String {
            val id = certificateEventId(certifierPubKey, certificateDigest(communityId, channelId, epoch, writer.hexToByteArray()))
            return Secp256k1Instance.signSchnorr(id, certifierSecret).toHexKey()
        }

        /** The `wepoch` tag name: the writer epoch a rumor served at a writer address commits (CORD-03 §3). */
        const val WEPOCH = "wepoch"

        /** The `wcert` tag name: a writer post's certificate (CORD-03 §1). */
        const val WCERT = "wcert"

        /**
         * The writer epoch [rumor] commits, `NOT_PRESENT` when it carries no `wepoch`, or `MALFORMED`
         * when it carries one twice or not in canonical decimal (dropped wherever it arrives).
         */
        fun wepochOf(rumor: Event): Long {
            var found: Long = NOT_PRESENT
            for (t in rumor.tags) {
                if (t.isEmpty() || t[0] != WEPOCH) continue
                if (found != NOT_PRESENT) return MALFORMED
                val v = t.getOrNull(1)?.let { s -> if (s.isNotEmpty() && (s.length == 1 || s[0] != '0') && s.all { it in '0'..'9' }) s.toLongOrNull() else null }
                found = v ?: return MALFORMED
            }
            return found
        }

        const val NOT_PRESENT = -1L
        const val MALFORMED = -2L

        /** A post's certificate: its first `wcert` tag, a malformed one counting as none. */
        fun wcertOf(rumor: Event): String? =
            rumor.tags
                .firstOrNull { it.isNotEmpty() && it[0] == WCERT }
                ?.getOrNull(1)
                ?.takeIf { it.length == 128 && it.hexToByteArrayOrNull() != null }
    }
}
