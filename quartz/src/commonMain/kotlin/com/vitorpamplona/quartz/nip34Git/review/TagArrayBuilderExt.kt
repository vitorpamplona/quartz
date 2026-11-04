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

import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag
import com.vitorpamplona.quartz.nip34Git.pr.tags.CurrentCommitTag
import com.vitorpamplona.quartz.nip34Git.review.tags.ReviewedFileTag
import com.vitorpamplona.quartz.nip34Git.review.tags.ReviewedLinesTag

/** Makes a comment an inline review of [path] (`f`). */
fun TagArrayBuilder<CommentEvent>.reviewedFile(path: String) = addUnique(ReviewedFileTag.assemble(path))

/** The commit the reviewed lines are read at (`c`). */
fun TagArrayBuilder<CommentEvent>.reviewedCommit(commit: String) = addUnique(CurrentCommitTag.assemble(commit))

/** The reviewed lines (`line`). */
fun TagArrayBuilder<CommentEvent>.reviewedLines(lines: ReviewedLinesTag) = addUnique(lines.toTagArray())

/** Resolves the sub-thread this comment replies to (`["l", "resolved"]`, gitworkshop's shape: no namespace). */
fun TagArrayBuilder<CommentEvent>.resolvesThread() = addUnique(arrayOf(LabelTag.TAG_NAME, RESOLVED_LABEL))
