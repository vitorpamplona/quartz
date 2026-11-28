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

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual class ZlibInflater actual constructor() {
    private val inflater = Inflater()

    actual fun setInput(
        input: ByteArray,
        offset: Int,
        length: Int,
    ) = inflater.setInput(input, offset, length)

    actual fun inflate(
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Int = inflater.inflate(output, offset, length)

    actual fun finished(): Boolean = inflater.finished()

    actual fun needsInput(): Boolean = inflater.needsInput()

    actual fun needsDictionary(): Boolean = inflater.needsDictionary()

    actual fun bytesRead(): Long = inflater.bytesRead

    actual fun remaining(): Int = inflater.remaining

    actual fun reset() = inflater.reset()

    actual fun end() = inflater.end()
}

internal actual object Zlib {
    actual fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, input.size / 2))
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}

internal actual typealias ZlibDataFormatException = DataFormatException

internal actual typealias EofException = EOFException
