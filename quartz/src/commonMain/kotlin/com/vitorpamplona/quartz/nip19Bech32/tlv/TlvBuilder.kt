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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.utils.ByteArrayOutputStream

class TlvBuilder {
    val outputStream = ByteArrayOutputStream(100)

    /**
     * @throws IllegalArgumentException when [byteArray] is longer than [MAX_VALUE_SIZE]: the
     * length is a single byte, and writing `size.toByte()` used to wrap it (300 -> 44), leaving a
     * string no decoder can read back.
     */
    private fun add(
        type: Byte,
        byteArray: ByteArray,
    ) {
        require(byteArray.size <= MAX_VALUE_SIZE) {
            "TLV value of type $type is ${byteArray.size} bytes; the length field holds at most $MAX_VALUE_SIZE"
        }
        outputStream.write(type)
        outputStream.write(byteArray.size.toByte())
        outputStream.write(byteArray)
    }

    fun addString(
        type: Byte,
        string: String,
    ) = add(type, string.encodeToByteArray())

    fun addHex(
        type: Byte,
        key: HexKey,
    ) = add(type, key.hexToByteArray())

    fun addInt(
        type: Byte,
        data: Int,
    ) = add(type, data.to32BitByteArray())

    fun addStringIfNotNull(
        type: Byte,
        data: String?,
    ) = data?.let { addString(type, it) }

    fun addStringIfNotBlank(
        type: Byte,
        data: String,
    ) {
        if (data.isNotBlank()) {
            addString(type, data)
        }
    }

    fun addHexIfNotNull(
        type: Byte,
        data: HexKey?,
    ) = data?.let { addHex(type, it) }

    fun addIntIfNotNull(
        type: Byte,
        data: Int?,
    ) = data?.let { addInt(type, it) }

    fun build(): ByteArray = outputStream.toByteArray()

    companion object {
        /** The largest value a TLV record can hold: its length is one unsigned byte. */
        const val MAX_VALUE_SIZE = 255
    }
}

fun Int.to32BitByteArray(): ByteArray {
    val bytes = ByteArray(4)
    (0..3).forEach { bytes[3 - it] = (this ushr (8 * it)).toByte() }
    return bytes
}
