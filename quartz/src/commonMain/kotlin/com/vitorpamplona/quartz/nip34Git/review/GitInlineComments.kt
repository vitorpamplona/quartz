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

/** The file and lines this comment reviews, or null when it is an ordinary comment. */
fun CommentEvent.gitCodeLocation(): GitCodeLocation? {
    val path = tags.reviewedFile() ?: return null
    val lines = tags.reviewedLines()
    return GitCodeLocation(path, tags.reviewedCommit(), lines?.start, lines?.end, lines?.removedSide == true)
}

/** True when this comment marks the thread it replies to as resolved. */
fun CommentEvent.isGitThreadResolution(): Boolean = tags.isThreadResolution()
