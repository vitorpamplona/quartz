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
package com.vitorpamplona.quartz.nip34Git

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip34Git.coverNote.GitCoverNoteEvent
import com.vitorpamplona.quartz.nip34Git.issue.GitIssueEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestUpdateEvent
import com.vitorpamplona.quartz.nip34Git.reply.GitReplyEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusEvent

/**
 * The issue, patch or pull request whose thread this event belongs to: itself for the item, the
 * item it acts on for a status, PR update, cover note or legacy reply, and null for anything else.
 *
 * Clients read an item's discussion by its id (NIP-22 `#E`, legacy `#e`), so a comment on any
 * event in that thread roots there (see `CommentEvent.replyBuilder(msg, root, replyingTo)`).
 */
fun Event.gitThreadRootId(): HexKey? =
    when (this) {
        is GitIssueEvent, is GitPullRequestEvent -> id
        // A later patch of a series is discussed in the series' thread, rooted at its first patch.
        is GitPatchEvent -> if (isRoot()) id else seriesRootId() ?: id
        is GitReplyEvent -> rootIssueOrPatch()
        is GitPullRequestUpdateEvent -> parentPullRequestId()
        is GitCoverNoteEvent -> rootEventId()
        is GitStatusEvent -> rootEventId()
        else -> null
    }
