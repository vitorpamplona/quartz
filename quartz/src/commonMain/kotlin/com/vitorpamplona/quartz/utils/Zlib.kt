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

/**
 * A streaming zlib (RFC 1950: the deflate format with its 2-byte header and Adler-32 trailer, the
 * way git stores objects) inflater, shaped like `java.util.zip.Inflater` so a reader can walk a
 * buffer holding several streams back to back: [remaining] tells where one stream ended.
 *
 * JVM and Android wrap `java.util.zip.Inflater`; native runs `platform.zlib` and keeps the array
 * given to [setInput] pinned until the next [setInput], [reset] or [end]. Call [end] when done:
 * on native it frees zlib's state.
 */
internal expect class ZlibInflater() {
    /** Inflates from [length] bytes of [input] at [offset] next, replacing any input not read yet. */
    fun setInput(
        input: ByteArray,
        offset: Int,
        length: Int,
    )

    /**
     * Inflates into [output], returning how many bytes it wrote: 0 when it needs input, a
     * dictionary, or the stream has [finished]. Corrupt data is a [ZlibDataFormatException].
     */
    fun inflate(
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /** True once the end of the zlib stream (its checksum included) was read. */
    fun finished(): Boolean

    /** True when every byte given to [setInput] has been read. */
    fun needsInput(): Boolean

    fun needsDictionary(): Boolean

    /** Compressed bytes read since the last [reset]. */
    fun bytesRead(): Long

    /** Bytes given to [setInput] and not read: past a [finished] stream, where the next data starts. */
    fun remaining(): Int

    /** Starts a new stream, dropping the input. */
    fun reset()

    /** Frees the inflater; it can't be used again. */
    fun end()
}

/** zlib-wrapped (RFC 1950) deflate at the default level, as git writes objects into a pack. */
internal expect object Zlib {
    fun deflate(input: ByteArray): ByteArray
}

/** Corrupt zlib data. `java.util.zip.DataFormatException` on JVM and Android. */
internal expect class ZlibDataFormatException(
    message: String,
) : Exception

/** Input that ended before the data it was reading did. `java.io.EOFException` on JVM and Android. */
internal expect class EofException(
    message: String,
) : Exception
