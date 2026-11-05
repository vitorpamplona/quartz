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

import com.vitorpamplona.quartz.nip34Git.git.GitHttpBackendFixture
import com.vitorpamplona.quartz.nip34Git.git.GitPerson
import com.vitorpamplona.quartz.nip34Git.git.GitPushClient
import com.vitorpamplona.quartz.nip34Git.git.GitSmartHttpTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [GraspProposalPusher] and [commitChanges] over real git: a proposal's commits land under refs/nostr/<id>. */
class GraspProposalsGitTest {
    private val me = GitPerson("Me", "me@x.org", 1700000000)

    @Test
    fun anEditInTheAppBecomesAProposalRef() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            File(work, "src").mkdirs()
            File(work, "src/a.txt").writeText("old\n")
            File(work, "run.sh").writeText("echo\n")
            git.git(work, "add", ".")
            git.git(work, "update-index", "--chmod=+x", "run.sh")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            val main = git.git(work, "rev-parse", "HEAD")

            val transport = GitSmartHttpTransport { git.client }
            val edit =
                runBlocking {
                    GitObjectSource(transport, listOf(url)).commitChanges(
                        parent = main,
                        changes = mapOf(listOf("src", "a.txt") to "new\n".encodeToByteArray(), listOf("run.sh") to "echo hi\n".encodeToByteArray()),
                        author = me,
                        message = "Edit two files",
                    )
                }
            val id = "e".repeat(64)
            val results = runBlocking { GraspProposalPusher(GitPushClient({ git.client })).push(id, listOf(url), edit.commit, edit.objects) }
            assertNull(results[url])
            assertEquals(edit.commit, git.git(bare, "rev-parse", "refs/nostr/$id"))
            assertEquals("new", git.git(bare, "show", "refs/nostr/$id:src/a.txt"))
            // The mode of an edited file is kept.
            assertEquals("100755", git.git(bare, "ls-tree", "refs/nostr/$id", "run.sh").substringBefore(' '))
            assertEquals("", git.git(bare, "fsck", "--no-progress", "--connectivity-only"))
        }

    @Test
    fun aForksBranchIsCopiedWithOnlyItsOwnCommits() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val (forkBare, forkUrl) = git.bare("fork")
            val work = git.work(bare, "work")
            File(work, "f.txt").writeText("1\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            val main = git.git(work, "rev-parse", "HEAD")
            File(work, "f.txt").writeText("2\n")
            git.git(work, "commit", "-q", "-am", "fork change")
            git.git(work, "push", "-q", forkBare.path, "HEAD:refs/heads/feature")
            val tip = git.git(work, "rev-parse", "HEAD")

            val transport = GitSmartHttpTransport { git.client }
            val target = GitObjectSource(transport, listOf(url))
            val fork = GitObjectSource(transport, listOf(forkUrl, url))
            val base = runBlocking { mergeBaseOf(target, main, fork, tip) }
            assertEquals(main, base)
            val id = "f".repeat(64)
            val results = runBlocking { GraspProposalPusher(GitPushClient({ git.client })).push(id, listOf(url), tip, branchObjects = GitBranchObjects(tip, base!!, fork)) }
            assertNull(results[url])
            assertEquals(tip, git.git(bare, "rev-parse", "refs/nostr/$id"))
            assertEquals("", git.git(bare, "fsck", "--no-progress", "--connectivity-only"))
        }
}
