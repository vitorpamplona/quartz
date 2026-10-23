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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.TlvTypes
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvWriter
import com.vitorpamplona.quartz.nip19Bech32.tlv.forEachTlvRecord
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.internIfPossible

@Immutable
data class NProfile(
    override val hex: HexKey,
    val relay: List<NormalizedRelayUrl>,
) : IPubKeyEntity {
    companion object {
        fun parse(bytes: ByteArray): NProfile? {
            if (bytes.isEmpty()) return null

            var hex: String? = null
            var relays: ArrayList<NormalizedRelayUrl>? = null
            forEachTlvRecord(bytes) { type, from, to ->
                when (type) {
                    TlvTypes.SPECIAL.id -> if (hex == null) hex = Hex.encode(bytes, from, to).internIfPossible()
                    TlvTypes.RELAY.id -> relays = addRelayHint(relays, bytes, from, to)
                }
            }

            // x-only 32-byte pubkey only (64 hex chars). Reject a 33-byte compressed
            // key or any other malformed length — see NPub.parse for why it matters.
            val pubKey = hex ?: return null
            if (pubKey.length != 64) return null

            return NProfile(pubKey, relays ?: emptyList())
        }

        fun create(
            authorPubKeyHex: String,
            relay: NormalizedRelayUrl?,
        ): String = encode(authorPubKeyHex, relay, emptyList(), "")

        fun create(
            authorPubKeyHex: String,
            relays: List<NormalizedRelayUrl>,
        ): String = encode(authorPubKeyHex, null, relays, "")

        /** `nostr:nprofile1…`, built in one buffer. */
        fun createNostrUri(
            authorPubKeyHex: String,
            relay: NormalizedRelayUrl?,
        ): String = encode(authorPubKeyHex, relay, emptyList(), NOSTR_URI_PREFIX)

        /** `nostr:nprofile1…`, built in one buffer. */
        fun createNostrUri(
            authorPubKeyHex: String,
            relays: List<NormalizedRelayUrl>,
        ): String = encode(authorPubKeyHex, null, relays, NOSTR_URI_PREFIX)

        /** Relay hints too long for a TLV record (over 255 bytes) are left out. */
        private fun encode(
            authorPubKeyHex: String,
            relay: NormalizedRelayUrl?,
            relays: List<NormalizedRelayUrl>,
            prefix: String,
        ): String {
            var size = TlvWriter.hexSize(authorPubKeyHex)
            if (relay != null) size += TlvWriter.optionalStringSize(relay.url)
            for (i in relays.indices) size += TlvWriter.optionalStringSize(relays[i].url)

            val tlv = TlvWriter(size)
            tlv.hex(TlvTypes.SPECIAL.id, authorPubKeyHex)
            if (relay != null) tlv.optionalString(TlvTypes.RELAY.id, relay.url)
            for (i in relays.indices) tlv.optionalString(TlvTypes.RELAY.id, relays[i].url)

            return Bech32.encodeBytes("nprofile", tlv.buffer, Bech32.Encoding.Bech32, prefix)
        }
    }
}
