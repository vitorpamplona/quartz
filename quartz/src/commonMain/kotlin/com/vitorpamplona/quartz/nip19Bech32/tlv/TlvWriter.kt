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
package com.vitorpamplona.quartz.nip19Bech32.tlv

import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32

/**
 * Exact-size TLV writer behind the NIP-19 entity encoders. The caller sums the record sizes first
 * ([hexSize], [stringSize] with [utf8Length], [INT32_SIZE]), then fills the buffer once: hex is
 * decoded straight into it and ASCII copied char by char, where [TlvBuilder] decoded each value
 * into its own array, grew a stream and copied it out again.
 *
 * Values must fit [TlvBuilder.MAX_VALUE_SIZE]; callers check before sizing the buffer.
 */
internal class TlvWriter(
    size: Int,
) {
    val buffer = ByteArray(size)
    private var pos = 0

    fun hex(
        type: Byte,
        hex: String,
    ) {
        buffer[pos++] = type
        buffer[pos++] = (hex.length / 2).toByte()
        var i = 0
        while (i < hex.length) {
            buffer[pos++] = ((Bech32.hexNibble(hex, i) shl 4) or Bech32.hexNibble(hex, i + 1)).toByte()
            i += 2
        }
    }

    /** Writes [s], whose UTF-8 length is [utf8Length] (from [TlvWriter.utf8Length]). */
    fun string(
        type: Byte,
        s: String,
        utf8Length: Int,
    ) {
        buffer[pos++] = type
        buffer[pos++] = utf8Length.toByte()
        if (utf8Length == s.length) {
            // all ASCII: one byte per char
            for (i in s.indices) buffer[pos++] = s[i].code.toByte()
        } else {
            val bytes = s.encodeToByteArray()
            bytes.copyInto(buffer, pos)
            pos += bytes.size
        }
    }

    /** Writes [s] only when it fits a record: the write half of [optionalStringSize]. */
    fun optionalString(
        type: Byte,
        s: String,
    ) {
        val length = utf8Length(s)
        if (fits(length)) string(type, s, length)
    }

    fun int32(
        type: Byte,
        value: Int,
    ) {
        buffer[pos++] = type
        buffer[pos++] = 4
        buffer[pos++] = (value ushr 24).toByte()
        buffer[pos++] = (value ushr 16).toByte()
        buffer[pos++] = (value ushr 8).toByte()
        buffer[pos++] = value.toByte()
    }

    companion object {
        const val INT32_SIZE = 6

        /** @throws IllegalArgumentException when [hex] has an odd length or spells more than one record holds. */
        fun hexSize(hex: String): Int {
            require(hex.length and 1 == 0) { "Invalid hex $hex" }
            require(fits(hex.length / 2)) { "TLV value is ${hex.length / 2} bytes; the length field holds at most ${TlvBuilder.MAX_VALUE_SIZE}" }
            return 2 + hex.length / 2
        }

        fun stringSize(utf8Length: Int) = 2 + utf8Length

        /**
         * The record size of an optional value such as a relay hint, or 0 when it is too long for
         * one record and will be left out by [optionalString]: a hint that cannot be encoded is
         * dropped rather than corrupting the whole entity.
         */
        fun optionalStringSize(s: String): Int {
            val length = utf8Length(s)
            return if (fits(length)) stringSize(length) else 0
        }

        /** True when a value of [byteLength] bytes fits one TLV record. */
        fun fits(byteLength: Int) = byteLength <= TlvBuilder.MAX_VALUE_SIZE

        /**
         * The UTF-8 length of [s] without encoding it, the same count `encodeToByteArray()` gives:
         * an unpaired surrogate becomes the 3-byte replacement character.
         */
        fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i].code
                when {
                    c < 0x80 -> n += 1
                    c < 0x800 -> n += 2
                    c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> {
                        n += 4
                        i++
                    }
                    else -> n += 3
                }
                i++
            }
            return n
        }
    }
}

/**
 * Walks the records of [tlv] once, in place, calling [onRecord] with each record's type and the
 * `[from, to)` range of its value. Stops at a truncated record, as [Tlv.parse] does.
 */
internal inline fun forEachTlvRecord(
    tlv: ByteArray,
    onRecord: (type: Byte, from: Int, to: Int) -> Unit,
) {
    var pos = 0
    while (pos + 2 <= tlv.size) {
        val type = tlv[pos]
        val length = tlv[pos + 1].toInt() and 0xff
        if (pos + 2 + length > tlv.size) return
        onRecord(type, pos + 2, pos + 2 + length)
        pos += 2 + length
    }
}

/** The big-endian Int at `tlv[from, from + 4)`. */
internal fun readInt32(
    tlv: ByteArray,
    from: Int,
): Int =
    ((tlv[from].toInt() and 0xff) shl 24) or
        ((tlv[from + 1].toInt() and 0xff) shl 16) or
        ((tlv[from + 2].toInt() and 0xff) shl 8) or
        (tlv[from + 3].toInt() and 0xff)
