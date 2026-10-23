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
package com.vitorpamplona.quartz.nip19Bech32.bech32

import com.vitorpamplona.quartz.utils.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The single-pass [Bech32.encodeBytes] / [Bech32.encodeHex] / [Bech32.decodeBytes] /
 * [Bech32.decodeBytesOrNull] against the 5-bit path ([Bech32.encode] + [Bech32.eight2five],
 * [Bech32.decode] + [Bech32.five2eight]) they replaced, which stays as the reference.
 */
class Bech32SinglePassTest {
    private val rnd = Random(19)

    private fun reference(
        hrp: String,
        data: ByteArray,
        encoding: Bech32.Encoding,
    ) = Bech32.encode(hrp, Bech32.eight2five(data), encoding)

    @Test
    fun encodeBytesMatchesTheFiveBitPath() {
        for (size in 0..300) {
            val data = rnd.nextBytes(size)
            for (encoding in Bech32.Encoding.entries) {
                assertEquals(reference("nevent", data, encoding), Bech32.encodeBytes("nevent", data, encoding), "size $size $encoding")
            }
        }
    }

    @Test
    fun encodeBytesWritesThePrefixInFront() {
        val data = rnd.nextBytes(40)
        assertEquals("nostr:" + reference("nevent", data, Bech32.Encoding.Bech32), Bech32.encodeBytes("nevent", data, Bech32.Encoding.Bech32, "nostr:"))
    }

    @Test
    fun uppercaseAndNonAsciiInputsFallBackToTheFiveBitPath() {
        val data = rnd.nextBytes(32)
        assertEquals(reference("LNBC", data, Bech32.Encoding.Bech32), Bech32.encodeBytes("LNBC", data, Bech32.Encoding.Bech32))
        assertEquals("n\u00f3str:" + reference("npub", data, Bech32.Encoding.Bech32), Bech32.encodeBytes("npub", data, Bech32.Encoding.Bech32, "n\u00f3str:"))
        assertFailsWith<IllegalArgumentException> { Bech32.encodeBytes("NpUb", data, Bech32.Encoding.Bech32) }
    }

    @Test
    fun encodeHexMatchesEncodeBytes() {
        for (size in 0..64) {
            val data = rnd.nextBytes(size)
            val hex = Hex.encode(data)
            assertEquals(Bech32.encodeBytes("npub", data, Bech32.Encoding.Bech32), Bech32.encodeHex("npub", hex), "size $size")
            assertEquals(Bech32.encodeBytes("npub", data, Bech32.Encoding.Bech32), Bech32.encodeHex("npub", hex.uppercase()), "size $size")
        }
        assertEquals("nostr:" + Bech32.encodeHex("note", "ab".repeat(32)), Bech32.encodeHex("note", "ab".repeat(32), "nostr:"))
    }

    @Test
    fun encodeHexReadsBadHexLikeHexDecode() {
        assertFailsWith<IllegalArgumentException> { Bech32.encodeHex("npub", "abc") }
        assertEquals(Bech32.encodeBytes("npub", Hex.decode("zz"), Bech32.Encoding.Bech32), Bech32.encodeHex("npub", "zz"))
        assertEquals(Bech32.encodeBytes("npub", Hex.decode("a\u00e9"), Bech32.Encoding.Bech32), Bech32.encodeHex("npub", "a\u00e9"))
        assertEquals("nostr:" + Bech32.encodeHex("npub", "0g"), Bech32.encodeHex("npub", "0g", "nostr:"))
    }

    @Test
    fun nip19SpecVectors() {
        assertEquals(
            "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg",
            Bech32.encodeHex("npub", "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"),
        )
        assertEquals(
            "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
            Bech32.encodeHex("nsec", "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"),
        )
        assertEquals(
            "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
            Hex.encode(Bech32.decodeBytesOrNull("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", "npub")!!),
        )
    }

    @Test
    fun bip173AndBip350ValidStrings() {
        // checksums from the BIPs' test vectors, data-free so the bytes are trivially known
        for (s in listOf("A12UEL5L", "a12uel5l", "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw")) {
            assertEquals(Bech32.Encoding.Bech32, Bech32.decodeBytes(s).third, s)
        }
        for (s in listOf("a1lqfn3a", "A1LQFN3A", "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx")) {
            assertEquals(Bech32.Encoding.Bech32m, Bech32.decodeBytes(s).third, s)
        }
    }

    @Test
    fun decodeBytesMatchesTheFiveBitPath() {
        for (size in 0..300) {
            val data = rnd.nextBytes(size)
            for (encoding in listOf(Bech32.Encoding.Bech32, Bech32.Encoding.Bech32m)) {
                val s = reference("naddr", data, encoding)
                for (input in listOf(s, s.uppercase())) {
                    val (hrp, int5s, refEncoding) = Bech32.decode(input)
                    val (fastHrp, bytes, fastEncoding) = Bech32.decodeBytes(input)
                    assertEquals(hrp, fastHrp)
                    assertEquals(refEncoding, fastEncoding)
                    assertContentEquals(Bech32.five2eight(int5s, 0), bytes)
                    assertContentEquals(bytes, Bech32.decodeBytesOrNull(input))
                    assertContentEquals(bytes, Bech32.decodeBytesOrNull(input, "naddr"))
                }
            }
        }
    }

    @Test
    fun decodeBytesWithoutChecksum() {
        val data = rnd.nextBytes(50)
        val s = Bech32.encodeBytes("lno", data, Bech32.Encoding.Beck32WithoutChecksum)
        val (hrp, bytes, encoding) = Bech32.decodeBytes(s, noChecksum = true)
        assertEquals("lno", hrp)
        assertEquals(Bech32.Encoding.Beck32WithoutChecksum, encoding)
        assertContentEquals(data, bytes)
    }

    @Test
    fun unprintableCharsAreIgnoredLikeBefore() {
        val s = Bech32.encodeHex("npub", "7e".repeat(32))
        val messy = s.substring(0, 10) + "\n " + s.substring(10, 30) + "\u200B" + s.substring(30)
        assertContentEquals(Bech32.decodeBytes(s).second, Bech32.decodeBytes(messy).second)
        assertContentEquals(Bech32.decodeBytes(s).second, Bech32.decodeBytesOrNull(messy, "npub"))
    }

    @Test
    fun decodeBytesOrNullRejectsWithoutThrowing() {
        val s = Bech32.encodeHex("npub", "7e".repeat(32))
        val flipped = s.dropLast(1) + (if (s.last() == 'q') 'p' else 'q')
        assertNull(Bech32.decodeBytesOrNull(flipped))
        assertNull(Bech32.decodeBytesOrNull(s, "nsec"))
        assertNull(Bech32.decodeBytesOrNull(s, "npu"))
        assertNull(Bech32.decodeBytesOrNull(s.replaceRange(20, 21, "b"))) // 'b' is not in the alphabet
        assertNull(Bech32.decodeBytesOrNull("npub1qqqqq"))
        assertNull(Bech32.decodeBytesOrNull("1qqqqqqqqqq"))
        assertNull(Bech32.decodeBytesOrNull("noseparator"))
        assertNull(Bech32.decodeBytesOrNull(""))
        assertNull(Bech32.decodeBytesOrNull(s, start = 5, end = 2))
        assertFailsWith<IllegalArgumentException> { Bech32.decodeBytes(flipped) }
    }

    @Test
    fun decodeBytesOrNullReadsARangeInPlace() {
        val s = Bech32.encodeHex("npub", "7e".repeat(32))
        val text = "hi nostr:$s, bye"
        val start = text.indexOf("npub1")
        assertContentEquals(Bech32.decodeBytes(s).second, Bech32.decodeBytesOrNull(text, "npub", start, start + s.length))
    }
}
