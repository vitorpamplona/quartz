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

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The JVM form of [PktLines], reading from an [InputStream] and building into a `java.io.ByteArrayOutputStream`. */
@Deprecated("Use PktLines instead. Removed in 3.0.0.", ReplaceWith("PktLines"))
object PktLineCodec {
    /** Encodes a text payload as a single data frame (a trailing `\n` is the git convention). */
    fun dataLine(text: String): ByteArray = PktLines.dataLine(text)

    /** Encodes a binary payload as a single data frame. */
    fun dataLine(payload: ByteArray): ByteArray = PktLines.dataLine(payload)

    val FLUSH: ByteArray get() = PktLines.FLUSH
    val DELIM: ByteArray get() = PktLines.DELIM

    /**
     * Parses a full pkt-line stream into frames. The smart-HTTP responses we
     * consume are small enough to read fully into memory before decoding.
     */
    fun parse(bytes: ByteArray): List<PktLine> = PktLines.parse(bytes)

    /**
     * Reads the next frame from [input] as it arrives, or null at the end of the stream. A frame
     * cut off by the end of the stream is an [java.io.EOFException].
     */
    fun read(input: InputStream): PktLine? = PktLines.read(input.asGitByteSource())

    /** Builds a request body by concatenating pre-encoded frames. */
    fun build(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()
}
