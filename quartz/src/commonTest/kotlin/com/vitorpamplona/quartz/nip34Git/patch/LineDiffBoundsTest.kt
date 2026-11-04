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
package com.vitorpamplona.quartz.nip34Git.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDiffBoundsTest {
    @Test
    fun aSmallEditOfALargeFileCostsOnlyItsMiddle() {
        val old = List(50_000) { "line $it" }
        val new = old.toMutableList().also { it[25_000] = "changed" }
        val hunks = LineDiff.hunks(old, new)
        assertEquals(1, hunks.size)
        assertEquals(25_001 - 3, hunks.single().oldStart)
        val matches = LineDiff.matches(old, new)
        assertEquals(-1, matches[25_000])
        assertEquals(49_999, matches[49_999])
    }

    @Test
    fun aHugeRewriteFallsBackToAReplacementInsteadOfRunningOutOfMemory() {
        // Two unrelated 20k-line texts: the shortest edit would need a 40k-step search.
        val old = List(20_000) { "a$it" }
        val new = List(20_000) { "b$it" }
        val hunks = LineDiff.hunks(old, new)
        val lines = hunks.flatMap { it.lines }
        assertEquals(20_000, lines.count { it.type == GitDiffLineType.DELETE })
        assertEquals(20_000, lines.count { it.type == GitDiffLineType.ADD })
        assertTrue(LineDiff.matches(old, new).all { it == -1 })
    }

    @Test
    fun stillFindsTheShortestEditInTheMiddle() {
        val old = listOf("a", "b", "c", "d", "e")
        val new = listOf("a", "x", "c", "e", "f")
        assertEquals(listOf(0, -1, 2, -1, 3), LineDiff.matches(old, new).toList())
    }
}
