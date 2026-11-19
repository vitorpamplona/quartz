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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.TlvTypes
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.tlv.readInt32

/**
 * The strict, Namecoin-nSite-only NIP-19 decoding profile.
 *
 * This deliberately does not change [com.vitorpamplona.quartz.nip19Bech32.Nip19Parser], whose
 * leniency is useful for user input and note content. A canonical re-encoding rejects mixed/upper
 * case, Bech32m, ignored unprintable characters, prefixes, suffixes, and non-canonical padding.
 */
internal object NamecoinNsiteNip19Decoder {
    fun decode(value: String): Entity? {
        val hrp =
            when {
                value.startsWith("npub1") -> "npub"
                value.startsWith("nprofile1") -> "nprofile"
                value.startsWith("naddr1") -> "naddr"
                value.startsWith("nevent1") -> "nevent"
                value.startsWith("note1") -> "note"
                else -> return null
            }

        val bytes = Bech32.decodeBytesOrNull(value, hrp) ?: return null
        if (Bech32.encodeBytes(hrp, bytes, Bech32.Encoding.Bech32) != value) return null

        return when (hrp) {
            "npub" -> if (bytes.size == 32) NPub.parse(bytes) else null
            "note" -> if (bytes.size == 32) NNote.parse(bytes) else null
            "nprofile" -> decodeTlv(hrp, bytes) { NProfile.parse(it) }
            "naddr" -> decodeTlv(hrp, bytes) { NAddress.parse(it) }
            "nevent" -> decodeTlv(hrp, bytes) { NEvent.parse(it) }
            else -> null
        }
    }

    private fun decodeTlv(
        hrp: String,
        bytes: ByteArray,
        parser: (ByteArray) -> Entity?,
    ): Entity? {
        if (!hasValidTlvShape(hrp, bytes)) return null
        return parser(bytes)
    }

    private fun hasValidTlvShape(
        hrp: String,
        bytes: ByteArray,
    ): Boolean {
        var specialCount = 0
        var authorCount = 0
        var kindCount = 0
        var pos = 0

        while (pos < bytes.size) {
            if (pos + 2 > bytes.size) return false
            val type = bytes[pos]
            val length = bytes[pos + 1].toInt() and 0xff
            val from = pos + 2
            val to = from + length
            if (to > bytes.size) return false

            when (hrp) {
                "nprofile" ->
                    when (type) {
                        TlvTypes.SPECIAL.id -> {
                            specialCount++
                            if (length != 32) return false
                        }

                        TlvTypes.RELAY.id -> if (!isValidRelay(bytes, from, to)) return false
                    }

                "nevent" ->
                    when (type) {
                        TlvTypes.SPECIAL.id -> {
                            specialCount++
                            if (length != 32) return false
                        }

                        TlvTypes.RELAY.id -> if (!isValidRelay(bytes, from, to)) return false
                        TlvTypes.AUTHOR.id -> {
                            authorCount++
                            if (length != 32) return false
                        }

                        TlvTypes.KIND.id -> {
                            kindCount++
                            if (length != 4 || readInt32(bytes, from) !in 0..65535) return false
                        }
                    }

                "naddr" ->
                    when (type) {
                        TlvTypes.SPECIAL.id -> {
                            specialCount++
                            if (!isValidUtf8(bytes, from, to)) return false
                        }

                        TlvTypes.RELAY.id -> if (!isValidRelay(bytes, from, to)) return false
                        TlvTypes.AUTHOR.id -> {
                            authorCount++
                            if (length != 32) return false
                        }

                        TlvTypes.KIND.id -> {
                            kindCount++
                            if (length != 4 || readInt32(bytes, from) !in 0..65535) return false
                        }
                    }
            }
            pos = to
        }

        return when (hrp) {
            "nprofile" -> specialCount == 1
            "nevent" -> specialCount == 1 && authorCount <= 1 && kindCount <= 1
            "naddr" -> specialCount == 1 && authorCount == 1 && kindCount == 1
            else -> false
        }
    }

    private fun isValidRelay(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        val relay = decodeUtf8(bytes, from, to) ?: return false
        return relay.all { it.code in 33..126 } && RelayUrlNormalizer.normalizeHintOrNull(relay) != null
    }

    private fun isValidUtf8(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Boolean = decodeUtf8(bytes, from, to) != null

    private fun decodeUtf8(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): String? =
        try {
            bytes.decodeToString(from, to, throwOnInvalidSequence = true)
        } catch (_: Exception) {
            null
        }
}
