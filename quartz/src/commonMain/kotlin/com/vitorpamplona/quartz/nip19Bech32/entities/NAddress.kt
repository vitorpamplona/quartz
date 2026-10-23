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
package com.vitorpamplona.quartz.nip19Bech32.entities

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.TlvTypes
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvBuilder
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvWriter
import com.vitorpamplona.quartz.nip19Bech32.tlv.forEachTlvRecord
import com.vitorpamplona.quartz.nip19Bech32.tlv.readInt32
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.internIfPossible

@Immutable
data class NAddress(
    val kind: Int,
    val author: String,
    val dTag: String,
    val relay: List<NormalizedRelayUrl>,
) : Entity {
    fun aTag(): String = Address.assemble(kind, author, dTag)

    fun address() = Address(kind, author, dTag)

    companion object {
        fun parse(naddr: String): NAddress? {
            try {
                val key = naddr.removePrefix("nostr:")

                if (key.startsWith("naddr")) {
                    return Bech32.decodeBytesOrNull(key, "naddr")?.let { parse(it) }
                }
            } catch (e: Throwable) {
                Log.w("NAddress", "Issue trying to Decode NIP19 $naddr", e)
            }

            return null
        }

        fun parse(bytes: ByteArray): NAddress? {
            if (bytes.isEmpty()) return null

            // One pass over the records, reading each value in place. Only the first d-tag,
            // author and kind count, as with Tlv.firstAs*; every relay is kept.
            var dTag: String? = null
            var author: String? = null
            var kindSeen = false
            var kind: Int? = null
            var relays: ArrayList<NormalizedRelayUrl>? = null
            forEachTlvRecord(bytes) { type, from, to ->
                when (type) {
                    TlvTypes.SPECIAL.id -> if (dTag == null) dTag = bytes.decodeToString(from, to)
                    TlvTypes.RELAY.id -> relays = addRelayHint(relays, bytes, from, to)
                    TlvTypes.AUTHOR.id -> if (author == null) author = Hex.encode(bytes, from, to).internIfPossible()
                    TlvTypes.KIND.id ->
                        if (!kindSeen) {
                            kindSeen = true
                            if (to - from == 4) kind = readInt32(bytes, from)
                        }
                }
            }

            val pubKey = author ?: return null
            val k = kind ?: return null

            // x-only 32-byte pubkey only, as in NPub.parse: a 33-byte compressed key would
            // flow into `a` tags and filters that strict relays reject. The TLV kind is a raw
            // uint32 read as a signed Int, so 0xFFFFFFFF arrives as -1; NIP-01 kinds are 0..65535.
            if (pubKey.length != 64) return null
            if (k !in 0..65535) return null

            return NAddress(k, pubKey, dTag ?: "", relays ?: emptyList())
        }

        /**
         * @throws IllegalArgumentException when [dTag] is over 255 bytes in UTF-8, which NIP-19's
         * one-byte TLV length cannot hold. Use [createOrNull] for d-tags from untrusted events.
         */
        fun create(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relay: NormalizedRelayUrl?,
        ): String = createOrNull(kind, pubKeyHex, dTag, relay) ?: throw dTagTooLong(dTag)

        /**
         * @throws IllegalArgumentException when [dTag] is over 255 bytes in UTF-8, which NIP-19's
         * one-byte TLV length cannot hold. Use [createOrNull] for d-tags from untrusted events.
         */
        fun create(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relays: List<NormalizedRelayUrl>,
        ): String = createOrNull(kind, pubKeyHex, dTag, relays) ?: throw dTagTooLong(dTag)

        /**
         * The `naddr1…` of this address, or null when [dTag] is over 255 bytes in UTF-8: no naddr
         * can hold it (its TLV length is one byte), and encoding the length wrapped used to give a
         * string that no client could decode. Relay hints over 255 bytes are left out.
         */
        fun createOrNull(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relay: NormalizedRelayUrl?,
        ): String? = encode(kind, pubKeyHex, dTag, relay, emptyList(), "")

        /** [createOrNull] for a list of relay hints. */
        fun createOrNull(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relays: List<NormalizedRelayUrl>,
        ): String? = encode(kind, pubKeyHex, dTag, null, relays, "")

        /** `nostr:naddr1…`, built in one buffer, or null as in [createOrNull]. */
        fun createNostrUriOrNull(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relay: NormalizedRelayUrl?,
        ): String? = encode(kind, pubKeyHex, dTag, relay, emptyList(), NOSTR_URI_PREFIX)

        /** `nostr:naddr1…`, built in one buffer, or null as in [createOrNull]. */
        fun createNostrUriOrNull(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relays: List<NormalizedRelayUrl>,
        ): String? = encode(kind, pubKeyHex, dTag, null, relays, NOSTR_URI_PREFIX)

        private fun dTagTooLong(dTag: String) =
            IllegalArgumentException(
                "d-tag is ${TlvWriter.utf8Length(dTag)} bytes; an naddr holds at most ${TlvBuilder.MAX_VALUE_SIZE}",
            )

        private fun encode(
            kind: Int,
            pubKeyHex: String,
            dTag: String,
            relay: NormalizedRelayUrl?,
            relays: List<NormalizedRelayUrl>,
            prefix: String,
        ): String? {
            val dTagLength = TlvWriter.utf8Length(dTag)
            if (!TlvWriter.fits(dTagLength)) return null

            // a blank relay url is skipped, as addStringIfNotBlank did
            var size = TlvWriter.stringSize(dTagLength) + TlvWriter.hexSize(pubKeyHex) + TlvWriter.INT32_SIZE
            if (relay != null && relay.url.isNotBlank()) size += TlvWriter.optionalStringSize(relay.url)
            for (i in relays.indices) {
                if (relays[i].url.isNotBlank()) size += TlvWriter.optionalStringSize(relays[i].url)
            }

            val tlv = TlvWriter(size)
            tlv.string(TlvTypes.SPECIAL.id, dTag, dTagLength)
            if (relay != null && relay.url.isNotBlank()) tlv.optionalString(TlvTypes.RELAY.id, relay.url)
            for (i in relays.indices) {
                if (relays[i].url.isNotBlank()) tlv.optionalString(TlvTypes.RELAY.id, relays[i].url)
            }
            tlv.hex(TlvTypes.AUTHOR.id, pubKeyHex)
            tlv.int32(TlvTypes.KIND.id, kind)

            return Bech32.encodeBytes("naddr", tlv.buffer, Bech32.Encoding.Bech32, prefix)
        }
    }
}
