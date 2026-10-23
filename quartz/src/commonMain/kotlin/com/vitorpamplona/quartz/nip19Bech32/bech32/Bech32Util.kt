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

import kotlin.jvm.JvmField

/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Bech32 works with 5 bits values, we use this type to make it explicit: whenever you see Int5 it
 * means 5 bits values, and whenever you see Byte it means 8 bits values.
 */
private typealias Int5 = Byte

/**
 * Low-level Bech32 / Bech32m codec (BIP-173 / BIP-350).
 *
 * For the common Nostr entities (`npub`, `nsec`, `note`, `nevent`, `nprofile`,
 * `naddr`) you usually **don't** need this directly — prefer the NIP-19 helpers:
 * the `ByteArray.toNpub()/toNsec()/toNote()` extensions to encode and
 * `Nip19Parser.uriToRoute(...)` to decode. Reach for `Bech32` when you need a
 * custom human-readable prefix or raw 5-bit/8-bit access:
 *
 * ```kotlin
 * val addr = Bech32.encodeBytes("npub", pubKeyBytes, Bech32.Encoding.Bech32)
 * val (hrp, data, _) = Bech32.decodeBytes(addr)   // hrp = "npub", data = 32 bytes
 * val bytes = "npub1...".bechToBytes("npub")      // decode + assert the prefix
 * val npub = Bech32.encodeHex("npub", pubKeyHex)   // straight from hex, no ByteArray
 * val raw = Bech32.decodeBytesOrNull(addr, "npub") // no exception, no Triple
 * ```
 *
 * [encodeBytes], [encodeHex], [decodeBytes] and [decodeBytesOrNull] run in a single pass: the
 * 8↔5-bit regrouping and the checksum share one loop and write into one exact-size array, with no
 * boxed 5-bit values. They run on every rendered `nostr:` link and every ingested NIP-19 reference,
 * and measured 3.5–4.2× faster with ~5× less allocation than the [eight2five] / [encode] path
 * they replaced. [encode], [decode], [eight2five] and [five2eight] keep the 5-bit API for callers
 * that build their own data part (segwit addresses, private zaps).
 *
 * See https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki and
 * https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki.
 */
object Bech32 {
    const val ALPHABET: String = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    const val ALPHABET_UPPERCASE: String = "QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L"

    private const val GEN0 = 0x3b6a57b2
    private const val GEN1 = 0x26508e6d
    private const val GEN2 = 0x1ea119fa
    private const val GEN3 = 0x3d4233dd
    private const val GEN4 = 0x2a1462b3

    enum class Encoding(
        val constant: Int,
    ) {
        Bech32(1),
        Bech32m(0x2bc830a3),
        Beck32WithoutChecksum(0),
    }

    /**
     * `GEN_TABLE[b]` is the xor of the generators for each bit set in `b`, the top 5 bits of the
     * checksum: one lookup per polymod step instead of five conditional xors.
     */
    private val GEN_TABLE =
        IntArray(32) { b ->
            var x = 0
            if ((b and 1) != 0) x = x xor GEN0
            if ((b and 2) != 0) x = x xor GEN1
            if ((b and 4) != 0) x = x xor GEN2
            if ((b and 8) != 0) x = x xor GEN3
            if ((b and 16) != 0) x = x xor GEN4
            x
        }

    /** 5-bit value -> ASCII byte of [ALPHABET], for writing the encoded string as bytes. */
    private val ALPHABET_BYTES = ByteArray(32) { ALPHABET[it].code.toByte() }

    @PublishedApi
    internal const val DATA_CHARS_SIZE = 128

    /** char code (< [DATA_CHARS_SIZE]) -> 5-bit value, or -1. Both cases map. */
    private val INT5 = ByteArray(DATA_CHARS_SIZE) { -1 }

    /** hex char code (< 128) -> nibble, or -1. */
    private val HEX_NIBBLE = IntArray(128) { -1 }

    /**
     * Membership table for [isDataChar], a primitive `BooleanArray` because [isDataChar] runs per
     * character over whole note contents (up to ~767KB).
     *
     * `@JvmField` so callers of the inline [isDataChar] compile to a direct `getstatic` instead of
     * a property-getter `invokevirtual` per character (the same reasoning as `PointTypes`).
     * `@PublishedApi internal` because a public inline function cannot touch a private member.
     */
    @PublishedApi
    @JvmField
    internal val DATA_CHARS = BooleanArray(DATA_CHARS_SIZE)

    init {
        for (i in 0..ALPHABET.lastIndex) {
            INT5[ALPHABET[i].code] = i.toByte()
            DATA_CHARS[ALPHABET[i].code] = true
        }
        for (i in 0..ALPHABET_UPPERCASE.lastIndex) {
            INT5[ALPHABET_UPPERCASE[i].code] = i.toByte()
            DATA_CHARS[ALPHABET_UPPERCASE[i].code] = true
        }
        for (i in 0..9) HEX_NIBBLE['0'.code + i] = i
        for (i in 0..5) {
            HEX_NIBBLE['a'.code + i] = 10 + i
            HEX_NIBBLE['A'.code + i] = 10 + i
        }
    }

    /**
     * True when [c] is part of the bech32 data alphabet, in either case — i.e. everything except
     * `1`, `b`, `i` and `o`, which BIP-173 excludes as visually ambiguous.
     *
     * Exposed so scanners can find where an encoded payload *ends* without decoding it. The NIP-19
     * content scan needs exactly that on every ingested event, and cannot use a regex to do it:
     * Android's `java.util.regex` is ICU-backed and `Matcher.region()` copies the entire input into
     * native memory per call, which drove the app's native heap to ~1.9GB on a cold start.
     *
     * `inline` because it is called per character over whole note contents (up to ~767KB). As a
     * normal member it compiled to an `invokevirtual` per char, which measured ~1-2% slower across
     * the scan benchmark than the equivalent private lookup it replaced; inlining puts the constant
     * compare and the `baload` straight into the caller's loop and closes that gap.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun isDataChar(c: Char): Boolean = c.code < DATA_CHARS_SIZE && DATA_CHARS[c.code]

    /**
     * One BCH polymod step: shift [chk] by 5 bits, fold in [v] and reduce by the generators.
     *
     * Masking the table index to 5 bits keeps it in range when a caller of the 5-bit API ([polymod],
     * [decode]) hands in a value outside `0..31` (an invalid char maps to -1): the generator test
     * only ever looked at bits 25..29, which is exactly what the mask keeps.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun step(
        chk: Int,
        v: Int,
    ): Int = ((chk and 0x1ffffff) shl 5) xor v xor GEN_TABLE[(chk ushr 25) and 31]

    fun expand(hrp: String): Array<Int5> {
        val half = hrp.length + 1
        val size = half + hrp.length
        val firstPart = hrp.indices
        val secondPart = half until size
        return Array<Int5>(size) {
            when (it) {
                in firstPart -> hrp[it].code.shr(5).toByte()
                in secondPart -> (hrp[it - half].code and 31).toByte()
                else -> 0
            }
        }
    }

    fun polymod(
        values: Array<Int5>,
        values1: Array<Int5>,
    ): Int {
        var chk = 1
        for (v in values) chk = step(chk, v.toInt())
        for (v in values1) chk = step(chk, v.toInt())
        return chk
    }

    /**
     * The checksum state after the expanded hrp, i.e. `polymod(expand(hrp), [])` without building
     * the expansion. Uppercase ASCII is folded to lowercase, as BIP-173 checksums the lowercase
     * form.
     */
    private fun hrpChecksum(
        s: String,
        start: Int,
        end: Int,
    ): Int {
        var chk = 1
        for (i in start until end) chk = step(chk, lowerAscii(s[i]).code shr 5)
        chk = step(chk, 0)
        for (i in start until end) chk = step(chk, lowerAscii(s[i]).code and 31)
        return chk
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun lowerAscii(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    /** The fast encoders write [prefix] + [hrp] as bytes, so both must be printable ASCII, and [hrp] lowercase. */
    private fun canEncodeFast(
        prefix: String,
        hrp: String,
    ): Boolean {
        for (i in prefix.indices) if (prefix[i].code !in 33..126) return false
        for (i in hrp.indices) {
            val c = hrp[i]
            if (c.code !in 33..126 || c in 'A'..'Z') return false
        }
        return true
    }

    /**
     * @param hrp human readable prefix
     * @param int5s 5-bit data
     * @param encoding encoding to use (bech32 or bech32m)
     * @return hrp + data encoded as a Bech32 string
     */

    public fun encode(
        hrp: String,
        int5s: ArrayList<Int5>,
        encoding: Encoding,
    ): String {
        require(hrp.lowercase() == hrp || hrp.uppercase() == hrp) {
            "mixed case strings are not valid bech32 prefixes"
        }
        val dataWithChecksum =
            when (encoding) {
                Encoding.Beck32WithoutChecksum -> int5s
                else -> addChecksum(hrp, int5s, encoding)
            }

        val charArray = CharArray(dataWithChecksum.size) { ALPHABET[dataWithChecksum[it].toInt()] }.concatToString()

        return hrp + "1" + charArray
    }

    /**
     * @param hrp human readable prefix
     * @param data data to encode
     * @param encoding encoding to use (bech32 or bech32m)
     * @return hrp + data encoded as a Bech32 string
     */
    public fun encodeBytes(
        hrp: String,
        data: ByteArray,
        encoding: Encoding,
    ): String = encodeBytes(hrp, data, encoding, "")

    /**
     * Like [encodeBytes], with [prefix] written in front of the result in the same buffer: pass
     * `"nostr:"` to build a NIP-21 uri without copying the encoded string a second time.
     */
    public fun encodeBytes(
        hrp: String,
        data: ByteArray,
        encoding: Encoding,
        prefix: String,
    ): String {
        if (!canEncodeFast(prefix, hrp)) return prefix + encode(hrp, eight2five(data), encoding)

        val withChecksum = encoding != Encoding.Beck32WithoutChecksum
        val out = ByteArray(prefix.length + hrp.length + 1 + (data.size * 8 + 4) / 5 + if (withChecksum) 6 else 0)
        var p = writeHeader(out, prefix, hrp)
        var chk = hrpChecksum(hrp, 0, hrp.length)

        val alphabet = ALPHABET_BYTES
        var acc = 0
        var bits = 0
        for (i in data.indices) {
            acc = (acc shl 8) or (data[i].toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                val v = (acc ushr bits) and 31
                chk = step(chk, v)
                out[p++] = alphabet[v]
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) {
            val v = (acc shl (5 - bits)) and 31
            chk = step(chk, v)
            out[p++] = alphabet[v]
        }

        if (withChecksum) writeChecksum(out, p, chk, encoding.constant)
        return out.decodeToString()
    }

    /**
     * Bech32-encodes the bytes that [hex] spells, straight from its nibbles, without decoding it
     * into a `ByteArray` first: the path behind `npub`, `nsec` and `note`, whose keys and ids are
     * held as hex. [prefix] is written in front, as in [encodeBytes].
     *
     * Like [com.vitorpamplona.quartz.utils.Hex.decode], it does not validate: a non-hex char is
     * read as nibble -1 and encodes garbage instead of throwing, because keys reach here from tags
     * that are only length-checked and are encoded while rendering. Check with `Hex.isHex` first
     * when the input is untrusted and garbage matters.
     *
     * @throws IllegalArgumentException when [hex] has an odd length.
     */
    public fun encodeHex(
        hrp: String,
        hex: String,
        prefix: String = "",
    ): String {
        require(hex.length and 1 == 0) { "Invalid hex $hex" }
        if (!canEncodeFast(prefix, hrp)) return prefix + encode(hrp, eight2five(hexToBytes(hex)), Encoding.Bech32)

        val out = ByteArray(prefix.length + hrp.length + 1 + (hex.length * 4 + 4) / 5 + 6)
        var p = writeHeader(out, prefix, hrp)
        var chk = hrpChecksum(hrp, 0, hrp.length)

        val alphabet = ALPHABET_BYTES
        var acc = 0
        var bits = 0
        for (i in hex.indices) {
            val nibble = hexNibble(hex, i)
            // a non-hex char: encode the bytes Hex.decode would have produced (rare, so restart)
            if (nibble < 0) return encodeBytes(hrp, hexToBytes(hex), Encoding.Bech32, prefix)
            acc = (acc shl 4) or nibble
            bits += 4
            if (bits >= 5) {
                bits -= 5
                val v = (acc ushr bits) and 31
                chk = step(chk, v)
                out[p++] = alphabet[v]
                acc = acc and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) {
            val v = (acc shl (5 - bits)) and 31
            chk = step(chk, v)
            out[p++] = alphabet[v]
        }

        writeChecksum(out, p, chk, Encoding.Bech32.constant)
        return out.decodeToString()
    }

    /** The nibble of `hex[i]`, or -1 when it is not a hex digit. */
    internal fun hexNibble(
        hex: String,
        i: Int,
    ): Int {
        val code = hex[i].code
        return if (code < 128) HEX_NIBBLE[code] else -1
    }

    /**
     * The byte at `hex[i, i + 2)`, composed exactly as `Hex.decode` does, so a non-hex char (-1)
     * gives the same garbage byte it always gave rather than an exception.
     */
    internal fun hexByte(
        hex: String,
        i: Int,
    ): Byte = ((hexNibble(hex, i) shl 4) or hexNibble(hex, i + 1)).toByte()

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hexByte(hex, it * 2) }

    private fun writeHeader(
        out: ByteArray,
        prefix: String,
        hrp: String,
    ): Int {
        var p = 0
        for (i in prefix.indices) out[p++] = prefix[i].code.toByte()
        for (i in hrp.indices) out[p++] = hrp[i].code.toByte()
        out[p++] = '1'.code.toByte()
        return p
    }

    private fun writeChecksum(
        out: ByteArray,
        start: Int,
        chkIn: Int,
        encodingConstant: Int,
    ) {
        var chk = chkIn
        repeat(6) { chk = step(chk, 0) }
        chk = chk xor encodingConstant
        var p = start
        for (i in 0 until 6) out[p++] = ALPHABET_BYTES[(chk ushr (5 * (5 - i))) and 31]
    }

    /**
     * decodes a bech32 string
     *
     * @param bech32 bech32 string
     * @param noChecksum if true, the bech32 string doesn't have a checksum
     * @return a (hrp, data, encoding) tuple
     */

    public fun decode(
        bech32: String,
        noChecksum: Boolean = false,
    ): Triple<String, Array<Int5>, Encoding> {
        val filteredBech32 = withoutUnprintable(bech32)

        val pos = filteredBech32.lastIndexOf('1').coerceAtLeast(0)

        val hrp = filteredBech32.take(pos).lowercase() // strings must be lower case
        require(hrp.length in 1..83) { "hrp must contain 1 to 83 characters" }

        val data = Array(filteredBech32.length - pos - 1) { INT5[filteredBech32[pos + 1 + it].code] }

        return if (noChecksum) {
            Triple(hrp, data, Encoding.Beck32WithoutChecksum)
        } else {
            val encoding =
                when (polymod(expand(hrp), data)) {
                    Encoding.Bech32.constant -> Encoding.Bech32
                    Encoding.Bech32m.constant -> Encoding.Bech32m
                    else -> throw IllegalArgumentException("invalid checksum for $bech32")
                }
            Triple(hrp, data.copyOfRange(0, data.size - 6), encoding)
        }
    }

    /** [s] without the chars outside printable ASCII (`33..126`); [s] itself when it has none. */
    private fun withoutUnprintable(s: String): String {
        for (i in s.indices) {
            if (s[i].code !in 33..126) return s.filter { it.code in 33..126 }
        }
        return s
    }

    private const val INVALID_CHAR = -1
    private const val INVALID_PADDING = -2

    /**
     * Regroups the 5-bit chars of `s[dataStart, payloadEnd)` into [out] and folds them, and any
     * checksum chars up to [end], into the checksum started at [chkIn].
     *
     * @return the final checksum state (never negative), or [INVALID_CHAR] / [INVALID_PADDING].
     */
    private fun decodeInto(
        s: String,
        dataStart: Int,
        payloadEnd: Int,
        end: Int,
        chkIn: Int,
        out: ByteArray,
    ): Int {
        val int5 = INT5
        var chk = chkIn
        var o = 0
        var acc = 0
        var bits = 0
        var i = dataStart
        while (i < payloadEnd) {
            val v = int5[s[i].code].toInt() // chars are 33..126 here, inside the table
            if (v < 0) return INVALID_CHAR
            chk = step(chk, v)
            acc = ((acc shl 5) or v) and 0xfff
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc ushr bits).toByte()
            }
            i++
        }
        // the same padding rules as five2eight: at most 4 bits, all zero
        if (bits > 4 || acc and ((1 shl bits) - 1) != 0) return INVALID_PADDING

        while (i < end) {
            val v = int5[s[i].code].toInt()
            if (v < 0) return INVALID_CHAR
            chk = step(chk, v)
            i++
        }
        return chk
    }

    /**
     * decodes a bech32 string
     *
     * @param bech32 bech32 string
     * @param noChecksum if true, the bech32 string doesn't have a checksum
     * @return a (hrp, data, encoding) tuple
     */
    public fun decodeBytes(
        bech32: String,
        noChecksum: Boolean = false,
    ): Triple<String, ByteArray, Encoding> {
        val s = withoutUnprintable(bech32)

        val sep = s.lastIndexOf('1')
        require(sep in 1..83) { "hrp must contain 1 to 83 characters" }

        val checksumChars = if (noChecksum) 0 else 6
        val payloadChars = s.length - sep - 1 - checksumChars
        require(payloadChars >= 0) { "invalid checksum for $bech32" }

        val out = ByteArray(payloadChars * 5 / 8)
        val chk = decodeInto(s, sep + 1, sep + 1 + payloadChars, s.length, hrpChecksum(s, 0, sep), out)
        require(chk != INVALID_CHAR) { "invalid character in $bech32" }
        require(chk != INVALID_PADDING) { "Non-zero padding in 8-to-5 conversion" }

        val encoding =
            when {
                noChecksum -> Encoding.Beck32WithoutChecksum
                chk == Encoding.Bech32.constant -> Encoding.Bech32
                chk == Encoding.Bech32m.constant -> Encoding.Bech32m
                else -> throw IllegalArgumentException("invalid checksum for $bech32")
            }
        return Triple(s.substring(0, sep).lowercase(), out, encoding)
    }

    /**
     * Decodes `bech32[start, end)` to its data bytes, or null when it is not valid Bech32 or
     * Bech32m, or when [expectedHrp] is given and the hrp is not it (compared case-insensitively;
     * pass it lowercase). Never throws, so a scan over untrusted text pays no exception per
     * malformed candidate, and builds no hrp string or [Triple].
     *
     * Accepts what [decodeBytes] accepts: either case, chars outside printable ASCII ignored, the
     * separator taken as the last `1`.
     */
    public fun decodeBytesOrNull(
        bech32: String,
        expectedHrp: String? = null,
        start: Int = 0,
        end: Int = bech32.length,
    ): ByteArray? {
        if (start < 0 || end > bech32.length || start > end) return null
        for (i in start until end) {
            if (bech32[i].code !in 33..126) {
                // rare: whitespace or invisible chars inside a pasted entity, dropped like decodeBytes does
                return decodeBytesOrNull(withoutUnprintable(bech32.substring(start, end)), expectedHrp)
            }
        }

        val sep = bech32.lastIndexOf('1', end - 1)
        val hrpLength = sep - start
        if (sep < start || hrpLength !in 1..83) return null
        if (expectedHrp != null) {
            if (expectedHrp.length != hrpLength) return null
            for (k in 0 until hrpLength) {
                if (lowerAscii(bech32[start + k]) != expectedHrp[k]) return null
            }
        }

        val payloadChars = end - sep - 1 - 6
        if (payloadChars < 0) return null

        val out = ByteArray(payloadChars * 5 / 8)
        val chk = decodeInto(bech32, sep + 1, sep + 1 + payloadChars, end, hrpChecksum(bech32, start, sep), out)
        return if (chk == Encoding.Bech32.constant || chk == Encoding.Bech32m.constant) out else null
    }

    val ZEROS = arrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte())

    /**
     * @param hrp Human Readable Part
     * @param data data (a sequence of 5 bits integers)
     * @param encoding encoding to use (bech32 or bech32m)
     * @return a checksum computed over hrp and data
     */
    private fun addChecksum(
        hrp: String,
        data: ArrayList<Int5>,
        encoding: Encoding,
    ): ArrayList<Int5> {
        val values = expand(hrp) + data
        val poly = polymod(values, ZEROS) xor encoding.constant

        for (i in 0 until 6) {
            data.add((poly.shr(5 * (5 - i)) and 31).toByte())
        }

        return data
    }

    /**
     * @param input a sequence of 8 bits integers
     * @return a sequence of 5 bits integers
     */

    public fun eight2five(input: ByteArray): ArrayList<Int5> {
        var buffer = 0L
        // larger array on purpose. Checksum is added later.
        val output = ArrayList<Int5>(input.size * 2)
        var count = 0
        input.forEach { b ->
            buffer = (buffer shl 8) or (b.toLong() and 0xff)
            count += 8
            while (count >= 5) {
                output.add(((buffer shr (count - 5)) and 31).toByte())
                count -= 5
            }
        }
        if (count > 0) output.add(((buffer shl (5 - count)) and 31).toByte())
        return output
    }

    /**
     * @param input a sequence of 5 bits integers
     * @return a sequence of 8 bits integers
     */

    public fun five2eight(
        input: Array<Int5>,
        offset: Int,
    ): ByteArray {
        var buffer = 0L
        val output = ArrayList<Byte>(input.size)
        var count = 0
        for (i in offset..input.lastIndex) {
            val b = input[i]
            buffer = (buffer shl 5) or (b.toLong() and 31)
            count += 5
            while (count >= 8) {
                output.add(((buffer shr (count - 8)) and 0xff).toByte())
                count -= 8
            }
        }
        require(count <= 4) { "Zero-padding of more than 4 bits" }
        require((buffer and ((1L shl count) - 1L)) == 0L) { "Non-zero padding in 8-to-5 conversion" }
        return output.toByteArray()
    }
}

/**
 * Decodes this Bech32 string to its raw data bytes. If [hrp] is given, throws
 * [IllegalArgumentException] when the decoded human-readable prefix doesn't match
 * (e.g. pass `"npub"` to reject anything that isn't a public key).
 */
fun String.bechToBytes(hrp: String? = null): ByteArray {
    val decodedForm = Bech32.decodeBytes(this)
    hrp?.also {
        if (it != decodedForm.first) {
            throw IllegalArgumentException("Expected $it but obtained ${decodedForm.first}")
        }
    }
    return decodedForm.second
}
