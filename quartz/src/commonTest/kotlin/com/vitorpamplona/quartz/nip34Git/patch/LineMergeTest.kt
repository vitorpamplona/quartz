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
import kotlin.test.assertIs
import kotlin.test.assertNull

class LineMergeTest {
    private val base = "one\ntwo\nthree\nfour\nfive\n"

    private fun merged(result: LineMerge.Result): String = assertIs<LineMerge.Result.Merged>(result).text

    @Test
    fun editsInDifferentPlacesBothLand() {
        val ours = "ONE\ntwo\nthree\nfour\nfive\n"
        val theirs = "one\ntwo\nthree\nfour\nFIVE\nsix\n"
        assertEquals("ONE\ntwo\nthree\nfour\nFIVE\nsix\n", merged(LineMerge.merge3(base, ours, theirs)))
    }

    @Test
    fun theSameEditOnBothSidesLandsOnce() {
        val both = "one\nTWO\nthree\nfour\nfive\n"
        assertEquals(both, merged(LineMerge.merge3(base, both, "one\nTWO\nthree\nfour\nfive\n")))
    }

    @Test
    fun differentEditsOfOneLineConflict() {
        assertIs<LineMerge.Result.Conflict>(LineMerge.merge3(base, "one\n2\nthree\nfour\nfive\n", "one\nzwei\nthree\nfour\nfive\n"))
    }

    @Test
    fun aDeletionBesideAnUntouchedEditMerges() {
        val ours = "one\nthree\nfour\nfive\n"
        val theirs = "one\ntwo\nthree\nfour\n5\n"
        assertEquals("one\nthree\nfour\n5\n", merged(LineMerge.merge3(base, ours, theirs)))
    }

    private val diff =
        """
        diff --git a/f.txt b/f.txt
        --- a/f.txt
        +++ b/f.txt
        @@ -2,3 +2,3 @@ one
         two
        -three
        +THREE
         four
        @@ -5 +5,2 @@ four
         five
        +six
        """.trimIndent() + "\n"

    @Test
    fun hunksApplyWhereTheySay() {
        val file = UnifiedDiffParser.parse(diff).files.single()
        assertEquals("one\ntwo\nTHREE\nfour\nfive\nsix\n", LineMerge.apply(base, file))
    }

    @Test
    fun hunksFollowLinesThatMoved() {
        val file = UnifiedDiffParser.parse(diff).files.single()
        // Two lines added at the top since the patch was made: both hunks land two lines lower.
        assertEquals("zero\nzero\none\ntwo\nTHREE\nfour\nfive\nsix\n", LineMerge.apply("zero\nzero\n$base", file))
    }

    @Test
    fun aHunkWithNoPlaceFails() {
        val file = UnifiedDiffParser.parse(diff).files.single()
        assertNull(LineMerge.apply("completely\ndifferent\n", file))
    }
}
