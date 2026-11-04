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
package com.vitorpamplona.quartz.nip34Git.review.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * gitworkshop's inline-review `line` tag: `["line", "42" | "42-48", "del"?]`. The lines are
 * [start]..[end] of the reviewed file, on the side the commit removed when [removedSide] is set.
 */
@Immutable
data class ReviewedLinesTag(
    val start: Int,
    val end: Int,
    val removedSide: Boolean,
) {
    fun toTagArray() = assemble(start, end, removedSide)

    companion object {
        const val TAG_NAME = "line"
        const val REMOVED_SIDE = "del"

        fun parse(tag: Array<String>): ReviewedLinesTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            val bounds = tag[1].split('-')
            ensure(bounds.size in 1..2) { return null }
            val first = bounds[0].trim().toIntOrNull() ?: return null
            val last = bounds.getOrNull(1)?.let { it.trim().toIntOrNull() ?: return null } ?: first
            ensure(first > 0 && last > 0) { return null }
            return ReviewedLinesTag(minOf(first, last), maxOf(first, last), tag.has(2) && tag[2] == REMOVED_SIDE)
        }

        fun assemble(
            start: Int,
            end: Int = start,
            removedSide: Boolean = false,
        ): Array<String> {
            val range = if (end == start) "$start" else "$start-$end"
            return if (removedSide) arrayOf(TAG_NAME, range, REMOVED_SIDE) else arrayOf(TAG_NAME, range)
        }
    }
}
