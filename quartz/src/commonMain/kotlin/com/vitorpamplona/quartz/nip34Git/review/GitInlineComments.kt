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
package com.vitorpamplona.quartz.nip34Git.review

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip22Comments.CommentEvent

/**
 * Where an inline code-review comment (a NIP-22 kind-1111 reply on a patch or PR) points:
 *
 *     ["f", "<path/to/file>"], ["c", "<commit-id>"], ["line", "42" | "42-48", "del"?]
 *
 * [startLine]..[endLine] is the range at [commit]; [removedSide] is set when the lines are ones
 * the commit removed (`["line", "3-6", "del"]`). A comment without an `f` tag is not inline.
 */
@Immutable
data class GitCodeLocation(
    val path: String,
    val commit: String?,
    val startLine: Int?,
    val endLine: Int?,
    val removedSide: Boolean,
)

/** Codecs for gitworkshop's inline code-review comments and thread resolutions on NIP-22 comments. */
object GitInlineComments {
    const val FILE_TAG = "f"
    const val COMMIT_TAG = "c"
    const val LINE_TAG = "line"
    const val REMOVED_SIDE = "del"
    const val LABEL_TAG = "l"
    const val RESOLVED = "resolved"

    fun location(tags: TagArray): GitCodeLocation? {
        val path = tags.firstOrNull { it.size > 1 && it[0] == FILE_TAG && it[1].isNotBlank() }?.get(1) ?: return null
        val commit = tags.firstOrNull { it.size > 1 && it[0] == COMMIT_TAG && it[1].isNotBlank() }?.get(1)
        val line = tags.firstOrNull { it.size > 1 && it[0] == LINE_TAG }
        val range = line?.get(1)?.split('-')?.mapNotNull { it.trim().toIntOrNull() }
        val start = range?.firstOrNull()
        val end = range?.getOrNull(1) ?: start
        return GitCodeLocation(path, commit, start, end, line?.getOrNull(2) == REMOVED_SIDE)
    }

    /** A reply carrying `["l", "resolved"]` resolves the sub-thread it replies to. */
    fun isResolution(tags: TagArray): Boolean = tags.any { it.size > 1 && it[0] == LABEL_TAG && it[1] == RESOLVED }
}

/** The file and lines this comment reviews, or null when it is an ordinary comment. */
fun CommentEvent.gitCodeLocation(): GitCodeLocation? = GitInlineComments.location(tags)

/** True when this comment marks the thread it replies to as resolved. */
fun CommentEvent.isGitThreadResolution(): Boolean = GitInlineComments.isResolution(tags)
