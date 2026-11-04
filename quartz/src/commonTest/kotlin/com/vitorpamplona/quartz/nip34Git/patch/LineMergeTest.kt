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

    @Test
    fun aPatchCanDropTheFinalNewline() {
        val patch = "diff --git a/f.txt b/f.txt\n--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n\\ No newline at end of file\n"
        val file = UnifiedDiffParser.parse(patch).files.single()
        assertEquals(true, file.newMissingNewline)
        assertEquals("a\nc", LineMerge.apply("a\nb\n", file))
    }

    @Test
    fun aPatchCanAddTheFinalNewline() {
        val patch = "diff --git a/f.txt b/f.txt\n--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n"
        val file = UnifiedDiffParser.parse(patch).files.single()
        assertEquals(true, file.oldMissingNewline)
        assertEquals(false, file.newMissingNewline)
        assertEquals("a\nb\n", LineMerge.apply("a\nb", file))
    }

    @Test
    fun theParserReadsModes() {
        val patch =
            "diff --git a/run.sh b/run.sh\nnew file mode 100755\nindex 0000000..1111111\n--- /dev/null\n+++ b/run.sh\n@@ -0,0 +1 @@\n+echo\n" +
                "diff --git a/x b/x\nold mode 100644\nnew mode 100755\n" +
                "diff --git a/y b/y\nindex 1111111..2222222 100644\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-a\n+b\n"
        val files = UnifiedDiffParser.parse(patch).files
        assertEquals("100755", files[0].newMode)
        assertEquals("100644" to "100755", files[1].oldMode to files[1].newMode)
        assertEquals("100644" to "100644", files[2].oldMode to files[2].newMode)
    }
}
