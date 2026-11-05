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

/**
 * Line-level merging for the three-way merge of a pull request or patch: a diff3 merge of two
 * edits of one base text, and the application of a parsed patch's hunks to a text. Both work on
 * lines split at `\n`, so a trailing newline survives as the empty last line.
 */
object LineMerge {
    /** A clean merge, or the conflict that stopped it. */
    sealed interface Result {
        class Merged(
            val text: String,
        ) : Result

        object Conflict : Result
    }

    /**
     * Merges [ours] and [theirs], two edits of [base], as `git merge-file` does without markers:
     * where only one side changed a region, take that side; where both made the same change, take
     * it once; where they changed it differently, it is a conflict.
     */
    fun merge3(
        base: String,
        ours: String,
        theirs: String,
    ): Result {
        if (ours == theirs) return Result.Merged(ours)
        if (base == ours) return Result.Merged(theirs)
        if (base == theirs) return Result.Merged(ours)

        val b = base.split('\n')
        val a = ours.split('\n')
        val c = theirs.split('\n')
        val toA = LineDiff.matches(b, a)
        val toC = LineDiff.matches(b, c)

        val out = ArrayList<String>()
        var ib = 0
        var ia = 0
        var ic = 0
        while (true) {
            // The next base line both sides kept: a stable point the unstable region before it ends at.
            var j = ib
            while (j < b.size && (toA[j] < 0 || toC[j] < 0)) j++
            val endA = if (j < b.size) toA[j] else a.size
            val endC = if (j < b.size) toC[j] else c.size

            val baseChunk = b.subList(ib, j)
            val oursChunk = a.subList(ia, endA)
            val theirsChunk = c.subList(ic, endC)
            when {
                oursChunk == baseChunk -> out.addAll(theirsChunk)
                theirsChunk == baseChunk -> out.addAll(oursChunk)
                oursChunk == theirsChunk -> out.addAll(oursChunk)
                else -> return Result.Conflict
            }

            if (j >= b.size) break
            out.add(b[j])
            ib = j + 1
            ia = endA + 1
            ic = endC + 1
        }
        return Result.Merged(out.joinToString("\n"))
    }

    /**
     * Applies [file]'s hunks to [text], as `git apply` does: each hunk where its old lines match,
     * searching outward from where it says it starts (earlier hunks shift the rest), and, failing
     * that, matching with up to [maxFuzz] context lines dropped from each end, as `patch` fuzz does.
     * Null when a hunk finds no place.
     */
    fun apply(
        text: String,
        file: GitDiffFile,
        maxFuzz: Int = 3,
    ): String? {
        // A text ending in a newline splits into a last empty line, not part of any hunk; a new
        // (empty) file has no lines at all, and gets the newline git ends files with.
        val endsWithNewline = text.isEmpty() || text.endsWith('\n')
        val lines = if (text.isEmpty()) mutableListOf() else text.split('\n').toMutableList()
        if (text.isNotEmpty() && endsWithNewline) lines.removeAt(lines.lastIndex)

        // How far the text has moved from the patch's line numbers: earlier hunks' net size, plus
        // how far from its stated place the last one landed.
        var offset = 0
        for (hunk in file.hunks) {
            val old = hunk.lines.filter { it.type != GitDiffLineType.ADD }.map { it.content }
            val new = hunk.lines.filter { it.type != GitDiffLineType.DELETE }.map { it.content }
            // A pure insertion's start is the line it goes after; otherwise the first line it covers.
            val stated = if (old.isEmpty()) hunk.oldStart else hunk.oldStart - 1
            val expected = stated + offset

            var placed = false
            for (fuzz in 0..maxFuzz) {
                val (lead, trail) = contextTrim(hunk, fuzz)
                if (lead + trail > old.size) break
                val oldCore = old.subList(lead, old.size - trail)
                val newCore = new.subList(lead, new.size - trail)
                val at = find(lines, oldCore, expected.coerceIn(0, lines.size) + lead) ?: continue
                repeat(oldCore.size) { lines.removeAt(at) }
                lines.addAll(at, newCore)
                offset = (at - lead) - stated + (new.size - old.size)
                placed = true
                break
            }
            if (!placed) return null
        }
        // The patch says how its new text ends; a patch that touched neither end keeps the text's.
        val endsWithNewlineNow =
            when {
                file.newMissingNewline -> false
                file.oldMissingNewline -> true
                else -> endsWithNewline
            }
        return lines.joinToString("\n") + if (endsWithNewlineNow && lines.isNotEmpty()) "\n" else ""
    }

    /** How many leading and trailing context lines a hunk drops at [fuzz]: never more than it has. */
    private fun contextTrim(
        hunk: GitDiffHunk,
        fuzz: Int,
    ): Pair<Int, Int> {
        val leading = hunk.lines.takeWhile { it.type == GitDiffLineType.CONTEXT }.size
        val trailing = hunk.lines.takeLastWhile { it.type == GitDiffLineType.CONTEXT }.size
        return minOf(fuzz, leading) to minOf(fuzz, trailing)
    }

    /** The index nearest [around] where [needle] occurs in [haystack], searching outward. */
    private fun find(
        haystack: List<String>,
        needle: List<String>,
        around: Int,
    ): Int? {
        if (needle.isEmpty()) return around.coerceIn(0, haystack.size)
        val last = haystack.size - needle.size
        if (last < 0) return null
        val start = around.coerceIn(0, last)
        for (delta in 0..maxOf(start, last - start)) {
            val before = start - delta
            if (before >= 0 && matchesAt(haystack, needle, before)) return before
            val after = start + delta
            if (delta > 0 && after <= last && matchesAt(haystack, needle, after)) return after
        }
        return null
    }

    private fun matchesAt(
        haystack: List<String>,
        needle: List<String>,
        at: Int,
    ): Boolean {
        for (i in needle.indices) if (haystack[at + i] != needle[i]) return false
        return true
    }
}
