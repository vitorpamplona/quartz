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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZlibTest {
    private val text = (0 until 500).joinToString("") { "line $it of a file git would store\n" }.encodeToByteArray()

    /** Inflates one zlib stream from [input] at [offset], feeding it [step] bytes at a time; returns the bytes and the inflater. */
    private fun inflate(
        input: ByteArray,
        offset: Int,
        step: Int,
        expected: Int,
    ): Pair<ByteArray, Int> {
        val inflater = ZlibInflater()
        try {
            val out = ByteArray(expected)
            var got = 0
            var pos = offset
            val chunk = ByteArray(7)
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    check(pos < input.size) { "ran out of input" }
                    val n = minOf(step, input.size - pos)
                    inflater.setInput(input, pos, n)
                    pos += n
                }
                val n = inflater.inflate(chunk, 0, chunk.size)
                chunk.copyInto(out, got, 0, n)
                got += n
            }
            assertEquals(expected, got)
            // What the inflater was given past the stream's end is where the next data starts.
            return out to (pos - inflater.remaining())
        } finally {
            inflater.end()
        }
    }

    @Test
    fun deflateThenInflateRoundTrips() {
        val packed = Zlib.deflate(text)
        assertTrue(packed.size < text.size)
        // A zlib header: CM 8 (deflate), and the header as a 16-bit number a multiple of 31.
        assertEquals(0x78, packed[0].toInt() and 0xFF)
        assertEquals(0, ((packed[0].toInt() and 0xFF) * 256 + (packed[1].toInt() and 0xFF)) % 31)
        val (out, end) = inflate(packed, 0, packed.size, text.size)
        assertContentEquals(text, out)
        assertEquals(packed.size, end)
    }

    @Test
    fun emptyInputRoundTrips() {
        val packed = Zlib.deflate(ByteArray(0))
        val (out, end) = inflate(packed, 0, packed.size, 0)
        assertEquals(0, out.size)
        assertEquals(packed.size, end)
    }

    @Test
    fun streamsBackToBackReportWhereEachEnds() {
        val second = "second object\n".encodeToByteArray()
        val first = Zlib.deflate(text)
        val buffer = first + Zlib.deflate(second) + byteArrayOf(1, 2, 3)

        // Fed in pieces, so the piece that holds the first stream's end also holds the second's start.
        for (step in listOf(1, 5, 100, buffer.size)) {
            val (a, end) = inflate(buffer, 0, step, text.size)
            assertContentEquals(text, a)
            assertEquals(first.size, end, "step $step")
            val (b, end2) = inflate(buffer, end, step, second.size)
            assertContentEquals(second, b)
            assertEquals(buffer.size - 3, end2, "step $step")
        }
    }

    @Test
    fun bytesReadCountsTheWholeStream() {
        val packed = Zlib.deflate(text)
        val inflater = ZlibInflater()
        try {
            inflater.setInput(packed + byteArrayOf(9, 9), 0, packed.size + 2)
            val out = ByteArray(text.size)
            var got = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(out, got, out.size - got)
                if (n == 0 && got == out.size) {
                    // Output full: read on with a scratch buffer, so the checksum is consumed too.
                    inflater.inflate(ByteArray(16), 0, 16)
                }
                got += n
            }
            assertEquals(packed.size.toLong(), inflater.bytesRead())
            assertEquals(2, inflater.remaining())

            inflater.reset()
            assertEquals(0L, inflater.bytesRead())
            inflater.setInput(packed, 0, packed.size)
            val again = ByteArray(text.size)
            var at = 0
            while (!inflater.finished()) at += inflater.inflate(again, at, again.size - at).also { if (it == 0 && at == again.size) inflater.inflate(ByteArray(16), 0, 16) }
            assertContentEquals(text, again)
        } finally {
            inflater.end()
        }
    }

    @Test
    fun corruptDataIsADataFormatError() {
        val packed = Zlib.deflate(text)
        // A valid header, then a deflate block of reserved type 3.
        val corrupt = byteArrayOf(packed[0], packed[1], 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val inflater = ZlibInflater()
        try {
            inflater.setInput(corrupt, 0, corrupt.size)
            assertFailsWith<ZlibDataFormatException> { inflater.inflate(ByteArray(64), 0, 64) }
        } finally {
            inflater.end()
        }
    }

    @Test
    fun rawDeflateIsNotZlib() {
        // Deflate's raw stream has no zlib header: the inflater refuses it.
        val raw = Deflate.compress(text)
        val inflater = ZlibInflater()
        try {
            inflater.setInput(raw, 0, raw.size)
            assertFailsWith<ZlibDataFormatException> {
                val out = ByteArray(text.size)
                while (!inflater.finished() && !inflater.needsInput()) inflater.inflate(out, 0, out.size)
            }
        } finally {
            inflater.end()
        }
    }
}
