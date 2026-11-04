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

import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag
import com.vitorpamplona.quartz.nip34Git.pr.tags.CurrentCommitTag
import com.vitorpamplona.quartz.nip34Git.review.tags.ReviewedFileTag
import com.vitorpamplona.quartz.nip34Git.review.tags.ReviewedLinesTag

/** The file an inline review comment is about (`f`). */
fun TagArray.reviewedFile() = fastFirstNotNullOfOrNull(ReviewedFileTag::parse)

/** The commit the reviewed lines are read at (`c`). */
fun TagArray.reviewedCommit() = fastFirstNotNullOfOrNull(CurrentCommitTag::parse)

/** The reviewed lines (`line`). */
fun TagArray.reviewedLines() = fastFirstNotNullOfOrNull(ReviewedLinesTag::parse)

/** True for gitworkshop's `["l", "resolved"]`, which resolves the sub-thread the comment replies to. */
fun TagArray.isThreadResolution() = fastAny { LabelTag.isTagged(it, RESOLVED_LABEL) }

const val RESOLVED_LABEL = "resolved"
