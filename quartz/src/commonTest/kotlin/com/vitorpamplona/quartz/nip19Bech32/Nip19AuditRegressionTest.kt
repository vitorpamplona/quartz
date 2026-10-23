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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvBuilder
import com.vitorpamplona.quartz.utils.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regressions found auditing the single-pass codec against the 5-bit / TlvBuilder path it replaced.
 */
class Nip19AuditRegressionTest {
    private val pubKey = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val relay = NormalizedRelayUrl("wss://relay.damus.io/")

    private fun reference(
        hrp: String,
        tlv: TlvBuilder.() -> Unit,
    ) = Bech32.encode(hrp, Bech32.eight2five(TlvBuilder().apply(tlv).build()), Bech32.Encoding.Bech32)

    /**
     * A lone surrogate is 1 byte on the JVM (`?`) but 3 bytes (U+FFFD) on Native: the size pass must
     * count what `encodeToByteArray()` actually writes, or the length byte claims bytes that are not
     * there and the naddr decodes to null.
     */
    @Test
    fun loneSurrogatesInADTagMatchWhatTheEncoderWrites() {
        for (dTag in listOf("a\uD800b", "\uDC00", "x\uD83D", "\uD83D\uDE00\uD800")) {
            assertEquals(
                reference("naddr") {
                    addString(0, dTag)
                    addString(1, relay.url)
                    addHex(2, pubKey)
                    addInt(3, 30023)
                },
                NAddress.create(30023, pubKey, dTag, relay),
                dTag,
            )
            val parsed = Nip19Parser.uriToRoute(NAddress.create(30023, pubKey, dTag, relay))?.entity as NAddress
            assertEquals(dTag.encodeToByteArray().decodeToString(), parsed.dTag)
        }
    }

    /**
     * Keys come from tags that are only length-checked, and 2.1.0 encoded non-hex ASCII the way
     * Hex.decode reads it instead of throwing; callers render these during composition.
     */
    @Test
    fun nonHexKeysEncodeAsBeforeInsteadOfThrowing() {
        val bad = "g".repeat(64)
        val synthetic = "concorddirectinvite-$pubKey"
        assertEquals(Bech32.encodeBytes("npub", Hex.decode(bad), Bech32.Encoding.Bech32), NPub.create(bad))
        assertEquals(Bech32.encodeBytes("note", Hex.decode(synthetic), Bech32.Encoding.Bech32), NNote.create(synthetic))
        assertEquals(
            reference("nprofile") {
                addHex(0, bad)
                addString(1, relay.url)
            },
            NProfile.create(bad, relay),
        )
        assertEquals(
            reference("nevent") {
                addHex(0, synthetic)
                addHex(2, bad)
                addInt(3, 1)
            },
            NEvent.create(synthetic, bad, 1, null as NormalizedRelayUrl?),
        )
        assertEquals(
            reference("naddr") {
                addString(0, "d")
                addHex(2, bad)
                addInt(3, 1)
            },
            NAddress.create(1, bad, "d", null as NormalizedRelayUrl?),
        )
        // chars past the hex table never throw either (2.1.0 threw IndexOutOfBounds for those)
        NPub.create("\u00e9".repeat(64))
        // odd lengths threw in 2.1.0 and still do
        assertFailsWith<IllegalArgumentException> { NPub.create("abc") }
    }

    /** The 5-bit API takes Int5s from callers; an out-of-range one must not index past the table. */
    @Test
    fun outOfRangeFiveBitValuesMatchTheOldPolymod() {
        fun oldPolymod(values: List<Int>): Int {
            val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            var chk = 1
            for (v in values) {
                val b = chk shr 25
                chk = ((chk and 0x1ffffff) shl 5) xor v
                for (i in 0..4) if (((b shr i) and 1) != 0) chk = chk xor gen[i]
            }
            return chk
        }
        for (values in listOf(listOf(-1, 0, 0), listOf(-1, -1, 5, 31, 0, 0, 0, 0, 0, 0), listOf(127, -128, 3))) {
            assertEquals(oldPolymod(values), Bech32.polymod(values.map { it.toByte() }.toTypedArray(), emptyArray()))
        }
        // an invalid char maps to -1; decode must still fail on the checksum, as before
        val npub = NPub.create(pubKey)
        assertFailsWith<IllegalArgumentException> { Bech32.decode(npub.replaceRange(10, 11, "b")) }
    }
}
