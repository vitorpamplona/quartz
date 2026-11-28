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
package com.vitorpamplona.quartz.nip34Git.git

/**
 * Bytes read as they arrive, blocking until some are there: a response body streamed to
 * [GitPackReader] and [PktLines.read], common code's `InputStream`.
 */
fun interface GitByteSource {
    /**
     * Reads up to [length] bytes into [dest] at [offset], blocking until at least one is there,
     * and returns how many it read, or -1 at the end of the data. Never 0 when [length] is not.
     */
    fun read(
        dest: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    companion object {
        /** A source over [length] bytes of [bytes] from [offset]: a body read whole, served as a stream. */
        fun of(
            bytes: ByteArray,
            offset: Int = 0,
            length: Int = bytes.size - offset,
        ): GitByteSource {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
            var pos = offset
            val end = offset + length
            return GitByteSource { dest, at, len ->
                if (len == 0) {
                    0
                } else if (pos >= end) {
                    -1
                } else {
                    val n = minOf(len, end - pos)
                    bytes.copyInto(dest, at, pos, pos + n)
                    pos += n
                    n
                }
            }
        }
    }
}
