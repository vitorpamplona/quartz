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
 * Computes a line-level unified diff between two texts using Myers' O(ND)
 * difference algorithm, producing the same [GitDiffHunk] model the embedded
 * patch parser uses. This lets the app render a diff for pull requests that
 * reference a clone URL + commit instead of embedding a `git format-patch`.
 */
object LineDiff {
    private class Op(
        val type: GitDiffLineType,
        val oldIndex: Int, // 0-based index into old lines, -1 for inserts
        val newIndex: Int, // 0-based index into new lines, -1 for deletes
    )

    /**
     * Builds unified-diff hunks between [oldLines] and [newLines], with [context]
     * unchanged lines around each change (git's default is 3).
     */
    fun hunks(
        oldLines: List<String>,
        newLines: List<String>,
        context: Int = 3,
    ): List<GitDiffHunk> {
        val ops = computeOps(oldLines, newLines)
        return groupHunks(ops, oldLines, newLines, context)
    }

    /**
     * For each line of [oldLines], the index of the line of [newLines] the diff keeps it as, or -1
     * when the diff deletes it: the alignment a three-way merge ([LineMerge]) works from.
     */
    fun matches(
        oldLines: List<String>,
        newLines: List<String>,
    ): IntArray {
        val result = IntArray(oldLines.size) { -1 }
        computeOps(oldLines, newLines).forEach { if (it.type == GitDiffLineType.CONTEXT) result[it.oldIndex] = it.newIndex }
        return result
    }

    /**
     * The most snake-search state the diff keeps, in ints (16 MB). Past it the diff stops looking
     * for the shortest edit and reports the rest as one replacement: still a correct diff, so a
     * huge rewrite renders as delete-then-add and a three-way merge sees it as one change.
     */
    private const val MAX_TRACE_INTS = 4_000_000

    /**
     * Myers O(ND) diff: an ordered op list (CONTEXT / DELETE / ADD). The common prefix and suffix
     * are matched first, so a small edit of a large file costs only its changed middle, and each
     * step keeps only the diagonals it reached ([MAX_TRACE_INTS] in all).
     */
    private fun computeOps(
        a: List<String>,
        b: List<String>,
    ): List<Op> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val ops = ArrayList<Op>(a.size + b.size - prefix - suffix)
        for (i in 0 until prefix) ops.add(Op(GitDiffLineType.CONTEXT, i, i))
        middle(a, b, prefix, a.size - suffix, prefix, b.size - suffix, ops)
        for (i in 0 until suffix) ops.add(Op(GitDiffLineType.CONTEXT, a.size - suffix + i, b.size - suffix + i))
        return ops
    }

    /** Myers on `a[aFrom, aTo)` against `b[bFrom, bTo)`, appending its ops (with absolute indices) to [out]. */
    private fun middle(
        a: List<String>,
        b: List<String>,
        aFrom: Int,
        aTo: Int,
        bFrom: Int,
        bTo: Int,
        out: MutableList<Op>,
    ) {
        val n = aTo - aFrom
        val m = bTo - bFrom
        if (n == 0 && m == 0) return
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 1)
        // trace[d] holds v[-d-1 .. d+1] as it stood when step d began: all that backtracking reads.
        val trace = ArrayList<IntArray>()
        var kept = 0L

        var found = false
        for (d in 0..max) {
            kept += 2 * d + 3
            if (kept > MAX_TRACE_INTS) break
            trace.add(v.copyOfRange((offset - d - 1).coerceAtLeast(0), (offset + d + 2).coerceAtMost(v.size)))
            var k = -d
            while (k <= d) {
                val idx = k + offset
                var x =
                    if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                        v[idx + 1]
                    } else {
                        v[idx - 1] + 1
                    }
                var y = x - k
                while (x < n && y < m && a[aFrom + x] == b[bFrom + y]) {
                    x++
                    y++
                }
                v[idx] = x
                if (x >= n && y >= m) {
                    found = true
                    break
                }
                k += 2
            }
            if (found) break
        }

        if (!found) {
            // Too costly to minimise: the whole middle is replaced.
            for (i in 0 until n) out.add(Op(GitDiffLineType.DELETE, aFrom + i, -1))
            for (j in 0 until m) out.add(Op(GitDiffLineType.ADD, -1, bFrom + j))
            return
        }

        // Backtrack to recover the edit script.
        val reversed = ArrayList<Op>()
        var x = n
        var y = m
        for (d in trace.indices.reversed()) {
            val vv = trace[d]
            val base = (offset - d - 1).coerceAtLeast(0) - offset

            fun at(k: Int) = vv[k - base]
            val k = x - y
            val prevK =
                if (k == -d || (k != d && at(k - 1) < at(k + 1))) {
                    k + 1
                } else {
                    k - 1
                }
            val prevX = at(prevK)
            val prevY = prevX - prevK

            while (x > prevX && y > prevY) {
                reversed.add(Op(GitDiffLineType.CONTEXT, aFrom + x - 1, bFrom + y - 1))
                x--
                y--
            }
            if (d > 0) {
                if (x == prevX) {
                    reversed.add(Op(GitDiffLineType.ADD, -1, bFrom + y - 1))
                } else {
                    reversed.add(Op(GitDiffLineType.DELETE, aFrom + x - 1, -1))
                }
            }
            x = prevX
            y = prevY
        }
        reversed.reverse()
        out.addAll(reversed)
    }

    private fun groupHunks(
        ops: List<Op>,
        oldLines: List<String>,
        newLines: List<String>,
        context: Int,
    ): List<GitDiffHunk> {
        if (ops.none { it.type != GitDiffLineType.CONTEXT }) return emptyList()

        val changeIndexes = ops.indices.filter { ops[it].type != GitDiffLineType.CONTEXT }
        val hunks = ArrayList<GitDiffHunk>()

        var i = 0
        while (i < changeIndexes.size) {
            val start = changeIndexes[i]
            var end = start
            // Extend the hunk while the next change is within 2*context of the previous.
            var j = i
            while (j + 1 < changeIndexes.size && changeIndexes[j + 1] - changeIndexes[j] <= 2 * context + 1) {
                j++
                end = changeIndexes[j]
            }
            val from = (start - context).coerceAtLeast(0)
            val to = (end + context).coerceAtMost(ops.size - 1)

            val lines = ArrayList<GitDiffLine>()
            var oldStart = -1
            var newStart = -1
            var oldCount = 0
            var newCount = 0
            for (idx in from..to) {
                val op = ops[idx]
                val oldNo = if (op.oldIndex >= 0) op.oldIndex + 1 else null
                val newNo = if (op.newIndex >= 0) op.newIndex + 1 else null
                val content = if (op.type == GitDiffLineType.ADD) newLines[op.newIndex] else oldLines[op.oldIndex]
                lines.add(GitDiffLine(op.type, content, oldNo, newNo))
                if (op.type != GitDiffLineType.ADD) {
                    if (oldStart < 0) oldStart = op.oldIndex + 1
                    oldCount++
                }
                if (op.type != GitDiffLineType.DELETE) {
                    if (newStart < 0) newStart = op.newIndex + 1
                    newCount++
                }
            }
            if (oldStart < 0) oldStart = 0
            if (newStart < 0) newStart = 0
            val header = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
            hunks.add(GitDiffHunk(header, oldStart, newStart, lines))

            i = j + 1
        }
        return hunks
    }
}
