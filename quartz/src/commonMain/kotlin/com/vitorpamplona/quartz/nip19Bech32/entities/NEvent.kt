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
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.TlvTypes
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvWriter
import com.vitorpamplona.quartz.nip19Bech32.tlv.forEachTlvRecord
import com.vitorpamplona.quartz.nip19Bech32.tlv.readInt32
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.internIfPossible

@Immutable
data class NEvent(
    val hex: String,
    val relay: List<NormalizedRelayUrl>,
    val author: String?,
    val kind: Int?,
) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NEvent? {
            if (bytes.isEmpty()) return null

            // One pass over the records, reading each value in place. Only the first id, author
            // and kind count, as with Tlv.firstAs*; every relay is kept.
            var hex: String? = null
            var authorSeen = false
            var author: String? = null
            var kindSeen = false
            var kind: Int? = null
            var relays: ArrayList<NormalizedRelayUrl>? = null
            forEachTlvRecord(bytes) { type, from, to ->
                when (type) {
                    TlvTypes.SPECIAL.id -> if (hex == null) hex = Hex.encode(bytes, from, to).internIfPossible()
                    TlvTypes.RELAY.id -> relays = addRelayHint(relays, bytes, from, to)
                    TlvTypes.AUTHOR.id ->
                        if (!authorSeen) {
                            authorSeen = true
                            // The id and author are 32 bytes (see NPub.parse). A bad id makes the
                            // entity useless; the author and kind are only hints, so a malformed one
                            // is dropped rather than sinking the reference.
                            if (to - from == 32) author = Hex.encode(bytes, from, to).internIfPossible()
                        }
                    TlvTypes.KIND.id ->
                        if (!kindSeen) {
                            kindSeen = true
                            // The TLV kind is a raw uint32 read as a signed Int (0xFFFFFFFF arrives as
                            // -1); NIP-01 kinds are 0..65535.
                            if (to - from == 4) kind = readInt32(bytes, from).takeIf { it in 0..65535 }
                        }
                }
            }

            val id = hex ?: return null
            if (id.length != 64) return null

            return NEvent(id, relays ?: emptyList(), author, kind)
        }

        fun create(
            idHex: String,
            author: String?,
            kind: Int?,
            relay: NormalizedRelayUrl?,
        ): String = encode(idHex, author, kind, relay, emptyList(), "")

        fun create(
            idHex: String,
            author: String?,
            kind: Int?,
            relays: List<NormalizedRelayUrl>,
        ): String = encode(idHex, author, kind, null, relays, "")

        /** `nostr:nevent1…`, built in one buffer. */
        fun createNostrUri(
            idHex: String,
            author: String?,
            kind: Int?,
            relay: NormalizedRelayUrl?,
        ): String = encode(idHex, author, kind, relay, emptyList(), NOSTR_URI_PREFIX)

        /** `nostr:nevent1…`, built in one buffer. */
        fun createNostrUri(
            idHex: String,
            author: String?,
            kind: Int?,
            relays: List<NormalizedRelayUrl>,
        ): String = encode(idHex, author, kind, null, relays, NOSTR_URI_PREFIX)

        /** Relay hints too long for a TLV record (over 255 bytes) are left out. */
        private fun encode(
            idHex: String,
            author: String?,
            kind: Int?,
            relay: NormalizedRelayUrl?,
            relays: List<NormalizedRelayUrl>,
            prefix: String,
        ): String {
            var size = TlvWriter.hexSize(idHex)
            if (relay != null) size += TlvWriter.optionalStringSize(relay.url)
            for (i in relays.indices) size += TlvWriter.optionalStringSize(relays[i].url)
            if (author != null) size += TlvWriter.hexSize(author)
            if (kind != null) size += TlvWriter.INT32_SIZE

            val tlv = TlvWriter(size)
            tlv.hex(TlvTypes.SPECIAL.id, idHex)
            if (relay != null) tlv.optionalString(TlvTypes.RELAY.id, relay.url)
            for (i in relays.indices) tlv.optionalString(TlvTypes.RELAY.id, relays[i].url)
            if (author != null) tlv.hex(TlvTypes.AUTHOR.id, author)
            if (kind != null) tlv.int32(TlvTypes.KIND.id, kind)

            return Bech32.encodeBytes("nevent", tlv.buffer, Bech32.Encoding.Bech32, prefix)
        }
    }
}
