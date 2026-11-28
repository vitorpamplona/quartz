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

import java.io.InputStream

/** [this] read as a [GitByteSource]. */
internal fun InputStream.asGitByteSource(): GitByteSource = GitByteSource { dest, offset, length -> read(dest, offset, length) }

/** [this] read as an [InputStream]; closing it calls [onClose]. */
internal fun GitByteSource.asInputStream(onClose: () -> Unit = {}): InputStream = GitByteSourceInputStream(this, onClose)

private class GitByteSourceInputStream(
    private val source: GitByteSource,
    private val onClose: () -> Unit,
) : InputStream() {
    private val one = ByteArray(1)

    override fun read(): Int {
        while (true) {
            val n = source.read(one, 0, 1)
            if (n < 0) return -1
            if (n == 1) return one[0].toInt() and 0xFF
        }
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        return source.read(b, off, len)
    }

    override fun close() = onClose()
}
