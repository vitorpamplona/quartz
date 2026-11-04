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
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.repository.roles
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import com.vitorpamplona.quartz.nip34Git.review.gitCodeLocation
import com.vitorpamplona.quartz.nip34Git.review.isGitThreadResolution
import com.vitorpamplona.quartz.nip34Git.review.resolvesThread
import com.vitorpamplona.quartz.nip34Git.review.reviewedCommit
import com.vitorpamplona.quartz.nip34Git.review.reviewedFile
import com.vitorpamplona.quartz.nip34Git.review.reviewedLines
import com.vitorpamplona.quartz.nip34Git.review.tags.ReviewedLinesTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions from the audit of the NIP-34 roles, review comments and thread roots. */
class GitAuditRegressionTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val sig = "00".repeat(64)
    private val signer = NostrSignerInternal(KeyPair())

    private suspend fun <T : Event> roundTrip(template: EventTemplate<T>): Event = Event.fromJson(signer.sign(template).toJson())

    @Test
    fun aBareLineTagDoesNotCrash() {
        // ["line"] with no value used to throw ArrayIndexOutOfBounds.
        val comment = CommentEvent("1".repeat(64), alice, 1, arrayOf(arrayOf("f", "a.kt"), arrayOf("line")), "", sig)
        assertEquals("a.kt", comment.gitCodeLocation()?.path)
        assertNull(comment.gitCodeLocation()?.startLine)
    }

    @Test
    fun lineRanges() {
        assertEquals(ReviewedLinesTag(42, 42, false), ReviewedLinesTag.parse(arrayOf("line", "42")))
        assertEquals(ReviewedLinesTag(42, 48, true), ReviewedLinesTag.parse(arrayOf("line", "42-48", "del")))
        assertEquals(ReviewedLinesTag(42, 48, false), ReviewedLinesTag.parse(arrayOf("line", "48-42")))
        assertNull(ReviewedLinesTag.parse(arrayOf("line", "abc")))
        assertNull(ReviewedLinesTag.parse(arrayOf("line", "1-2-3")))
    }

    @Test
    fun inlineReviewWritersParseBack() =
        runTest {
            val pr = GitPullRequestEvent("2".repeat(64), alice, 1, arrayOf(arrayOf("a", "30617:$alice:din")), "", sig)
            val template =
                CommentEvent.replyBuilder("this is wrong", EventHintBundle<Event>(pr)) {
                    reviewedFile("src/main.rs")
                    reviewedCommit("abc1234")
                    reviewedLines(ReviewedLinesTag(3, 6, true))
                    resolvesThread()
                }
            val comment = assertIs<CommentEvent>(roundTrip(template))
            val location = comment.gitCodeLocation()!!
            assertEquals("src/main.rs", location.path)
            assertEquals("abc1234", location.commit)
            assertEquals(3, location.startLine)
            assertEquals(6, location.endLine)
            assertTrue(location.removedSide)
            assertTrue(comment.isGitThreadResolution())
        }

    @Test
    fun roleWritersParseBackAndLinkTheirSubjects() =
        runTest {
            val records =
                listOf(
                    RepositoryRoleRecord(RepositoryRole.LEAD, bob, listOf(100L)),
                    RepositoryRoleRecord(RepositoryRole.MODERATOR, carol, listOf(100L, 200L, 300L)),
                    RepositoryRoleRecord(RepositoryRole.MAINTAINER, alice, listOf(0L, null)),
                )
            val template = GitRepositoryEvent.build(name = "din", description = "", dTag = "din") { roles(records) }
            val repo = assertIs<GitRepositoryEvent>(roundTrip(template))
            assertEquals(records, repo.roleRecords())
            // Role-only subjects are linked like `maintainers` ones, the author never.
            assertEquals(setOf(bob, carol, alice).minus(repo.pubKey), repo.linkedPubKeys().toSet())
            assertTrue(repo.linkedAddressIds().isEmpty())
        }

    @Test
    fun pullRequestTargetBranchParsesBack() =
        runTest {
            val repo = GitRepositoryEvent("3".repeat(64), alice, 1, arrayOf(arrayOf("d", "din")), "", sig)
            val template = GitPullRequestEvent.build("desc", EventHintBundle(repo), "euc", "tip", emptyList(), targetBranch = "release")
            assertEquals("release", assertIs<GitPullRequestEvent>(roundTrip(template)).targetBranch())
        }

    @Test
    fun aLaterPatchIsDiscussedInItsSeriesThread() {
        val root = GitPatchEvent("4".repeat(64), alice, 1, arrayOf(arrayOf("t", "root")), "", sig)
        val second = GitPatchEvent("5".repeat(64), alice, 2, arrayOf(arrayOf("e", root.id, "", "root"), arrayOf("e", root.id, "", "reply")), "", sig)
        val revision = GitPatchEvent("6".repeat(64), alice, 3, arrayOf(arrayOf("t", "root"), arrayOf("t", "root-revision"), arrayOf("e", root.id, "", "reply")), "", sig)

        assertEquals(root.id, root.gitThreadRootId())
        assertEquals(root.id, second.gitThreadRootId())
        assertEquals(revision.id, revision.gitThreadRootId())
    }
}
