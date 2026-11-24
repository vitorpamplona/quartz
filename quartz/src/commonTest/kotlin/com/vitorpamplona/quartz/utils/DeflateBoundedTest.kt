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
package com.vitorpamplona.quartz.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class DeflateBoundedTest {
    @Test
    fun boundedDecompressStopsAtTheLimit() {
        val bomb = Deflate.compress(ByteArray(1_000_000))
        assertNull(Deflate.decompress(bomb, 1000))
        assertEquals(1_000_000, Deflate.decompress(bomb, 1_000_000)!!.size)
    }

    // A BLE peer controls these bytes and can send them again and again: each refusal must free the
    // inflate stream (zlib's ~40 KB window on native) rather than leak it.
    @Test
    fun malformedInputFailsEveryTime() {
        // BFINAL=1, BTYPE=11: a reserved block type, invalid from the first byte.
        val reserved = byteArrayOf(0x07, 0x00, 0x00, 0x00)
        repeat(2_000) { assertFails { Deflate.decompress(reserved, 1_000_000) } }
        assertFails { Deflate.decompress(reserved) }
    }

    @Test
    fun truncatedInputFailsInsteadOfReturningAPrefix() {
        val original = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val compressed = Deflate.compress(original)
        val truncated = compressed.copyOf(compressed.size / 2)
        repeat(200) { assertFails { Deflate.decompress(truncated, 1_000_000) } }
        assertFails { Deflate.decompress(truncated) }
    }

    @Test
    fun aLargeInputUnderASmallLimitIsRefused() {
        // 4x a large input is a big first chunk; the limit, not the input size, bounds what one
        // inflate call may write.
        val original = ByteArray(2_000_000) { (it * 7 % 13).toByte() }
        val compressed = Deflate.compress(original)
        assertNull(Deflate.decompress(compressed, 10))
        assertEquals(original.size, Deflate.decompress(compressed, original.size)!!.size)
        assertNull(Deflate.decompress(compressed, original.size - 1))
    }
}
