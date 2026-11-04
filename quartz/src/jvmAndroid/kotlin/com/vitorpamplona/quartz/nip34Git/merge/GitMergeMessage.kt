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
package com.vitorpamplona.quartz.nip34Git.merge

/**
 * The message of a merge commit, as gitworkshop and ngit write it (`Merge #<8 hex>: <title>`),
 * so a merge made here reads, and is detected, the same as one made with them.
 */
object GitMergeMessage {
    private const val SUBJECT_BUDGET = 72

    /**
     * @param rootId the PR's (or the series root patch's) event id.
     * @param title its current title.
     * @param nostrUri `nostr:nevent1…` for it.
     * @param authorName its author's display name, if known.
     * @param authorNpub its author's npub.
     * @param description the current cover note, or failing that its description.
     * @param descriptionIsCoverNote whether [description] is a cover note.
     */
    fun build(
        rootId: String,
        title: String,
        nostrUri: String,
        authorName: String?,
        authorNpub: String,
        description: String?,
        descriptionIsCoverNote: Boolean,
    ): String {
        val prefix = "Merge #${rootId.take(8)}: "
        val cleanTitle = title.trim()
        val budget = SUBJECT_BUDGET - prefix.length
        val truncated = cleanTitle.codePointCount(0, cleanTitle.length) > budget
        val subject = if (truncated) cleanTitle.take(budget - 1).trimEnd() + "…" else cleanTitle
        return buildString {
            append(prefix).append(subject)
            if (truncated) append("\n\n").append(cleanTitle)
            append("\n\n").append(nostrUri)
            append("\n\n").append("PR-Author:").append(authorName?.let { " $it" } ?: "")
            append('\n').append("nostr:").append(authorNpub)
            val body = description?.trim()
            if (!body.isNullOrEmpty()) {
                append("\n\n").append(if (descriptionIsCoverNote) "CoverNote:" else "PR description:")
                append("\n\n").append(body)
            }
        }
    }
}
