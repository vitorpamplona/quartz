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

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip34Git.issue.GitIssueEvent
import com.vitorpamplona.quartz.nip34Git.issue.repository
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestUpdateEvent
import com.vitorpamplona.quartz.nip34Git.reply.GitReplyEvent
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusAppliedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusOpenEvent
import com.vitorpamplona.quartz.nip34Git.status.earliestUniqueCommit
import com.vitorpamplona.quartz.nip34Git.status.repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What gitworkshop reads from the events Amethyst writes, and how it finds them. */
class GitInteropTagsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val issueId = "1".repeat(64)
    private val sig = "00".repeat(64)

    private val aliceRepo = Address(GitRepositoryEvent.KIND, alice, "din")
    private val bobRepo = Address(GitRepositoryEvent.KIND, bob, "din")

    private val issue = GitIssueEvent(issueId, alice, 1, arrayOf(arrayOf("a", aliceRepo.toValue())), "it broke", sig)

    @Test
    fun anItemKeepsEveryRepositoryCoordinate() {
        val template =
            GitIssueEvent.build("Broken", "it broke", EventHintBundle(GitRepositoryEvent("2".repeat(64), alice, 1, arrayOf(arrayOf("d", "din")), "", sig)), emptyList(), emptyList()) {
                repository(ATag(bobRepo))
            }
        assertEquals(listOf(aliceRepo.toValue(), bobRepo.toValue()), template.tags.filter { it[0] == "a" }.map { it[1] })
    }

    @Test
    fun aStatusNamesEveryCoordinateAndTheRepositoryCommit() {
        val template =
            GitStatusOpenEvent.build("", EventHintBundle(issue)) {
                repository(ATag(aliceRepo))
                repository(ATag(bobRepo))
                earliestUniqueCommit("ce7491683db8f67667f32b598a93b5018f38c1d5")
            }
        val status = GitStatusOpenEvent("3".repeat(64), bob, 2, template.tags, "", sig)

        assertEquals(issueId, status.rootEventId())
        assertEquals(listOf(aliceRepo, bobRepo), status.repositoryAddresses())
        assertEquals(listOf(aliceRepo.toValue(), bobRepo.toValue()), status.linkedAddressIds())
        assertEquals(listOf("ce7491683db8f67667f32b598a93b5018f38c1d5"), status.referenceCommits())
    }

    @Test
    fun aMergeNamesItsCommit() {
        val template = GitStatusAppliedEvent.build("", EventHintBundle(issue), mergeCommit = "abc")
        assertEquals(listOf("merge-commit", "abc"), template.tags.first { it[0] == "merge-commit" }.toList())
        assertEquals(listOf("r", "abc"), template.tags.first { it[0] == "r" }.toList())
    }

    @Test
    fun aCommentOnAThreadEntryRootsAtTheItem() {
        val legacyReply = GitReplyEvent("4".repeat(64), bob, 2, arrayOf(arrayOf("e", issueId, "", "root"), arrayOf("p", alice)), "same here", sig)
        val template = CommentEvent.replyBuilder("me too", EventHintBundle(issue), EventHintBundle(legacyReply))

        fun value(name: String) = template.tags.first { it[0] == name }[1]
        assertEquals(issueId, value("E"))
        assertEquals("1621", value("K"))
        assertEquals(alice, value("P"))
        assertEquals(legacyReply.id, value("e"))
        assertEquals("1622", value("k"))
        assertEquals(bob, value("p"))
    }

    @Test
    fun threadEntriesNameTheirItem() {
        val reply = GitReplyEvent("4".repeat(64), bob, 2, arrayOf(arrayOf("e", issueId, "", "root")), "", sig)
        val update = GitPullRequestUpdateEvent("5".repeat(64), bob, 2, arrayOf(arrayOf("E", issueId), arrayOf("K", GitPullRequestEvent.KIND.toString())), "", sig)
        val status = GitStatusOpenEvent("6".repeat(64), bob, 2, arrayOf(arrayOf("e", issueId, "", "root")), "", sig)
        val comment = CommentEvent("7".repeat(64), bob, 2, arrayOf(arrayOf("E", issueId)), "", sig)

        assertEquals(issueId, issue.gitThreadRootId())
        assertEquals(issueId, reply.gitThreadRootId())
        assertEquals(issueId, update.gitThreadRootId())
        assertEquals(issueId, status.gitThreadRootId())
        assertNull(comment.gitThreadRootId())
    }
}
