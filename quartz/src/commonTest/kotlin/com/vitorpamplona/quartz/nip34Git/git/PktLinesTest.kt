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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

class PktLinesTest {
    /** A source that hands out one byte per read, as a slow network would. */
    private fun trickle(bytes: ByteArray): GitByteSource {
        val all = GitByteSource.of(bytes)
        return GitByteSource { dest, offset, length -> all.read(dest, offset, minOf(1, length)) }
    }

    private val stream =
        PktLines.build {
            write(PktLines.dataLine("command=fetch\n"))
            write(PktLines.DELIM)
            write(PktLines.dataLine(byteArrayOf(1, 2, 3)))
            write(PktLines.FLUSH)
            write("0002".encodeToByteArray())
        }

    @Test
    fun buildsTheWireBytes() {
        assertEquals("0012command=fetch\n0001", stream.decodeToString(0, 22))
        assertEquals("0007", stream.decodeToString(22, 26))
        assertEquals("00000002", stream.decodeToString(29, 37))
        assertEquals(37, stream.size)
    }

    @Test
    fun parsesAWholeStream() {
        val lines = PktLines.parse(stream)
        assertEquals(5, lines.size)
        assertEquals("command=fetch", assertIs<PktLine.Data>(lines[0]).text())
        assertEquals(PktLine.Delim, lines[1])
        assertEquals(listOf<Byte>(1, 2, 3), assertIs<PktLine.Data>(lines[2]).payload.toList())
        assertEquals(PktLine.Flush, lines[3])
        assertEquals(PktLine.ResponseEnd, lines[4])
    }

    @Test
    fun readsFramesAsTheyArrive() {
        val source = trickle(stream)
        assertEquals("command=fetch", assertIs<PktLine.Data>(PktLines.read(source)).text())
        assertEquals(PktLine.Delim, PktLines.read(source))
        assertEquals(3, assertIs<PktLine.Data>(PktLines.read(source)).payload.size)
        assertEquals(PktLine.Flush, PktLines.read(source))
        assertEquals(PktLine.ResponseEnd, PktLines.read(source))
        assertNull(PktLines.read(source))
    }

    @Test
    fun aFrameCutShortFails() {
        val cut = PktLines.dataLine("want abc\n").copyOf(8)
        assertFails { PktLines.read(GitByteSource.of(cut)) }
        assertFails { PktLines.read(GitByteSource.of("00".encodeToByteArray())) }
    }
}
