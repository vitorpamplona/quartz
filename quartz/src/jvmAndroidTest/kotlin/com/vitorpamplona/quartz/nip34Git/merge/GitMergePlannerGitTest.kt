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
import com.vitorpamplona.quartz.nip34Git.git.GitRefUpdate
import com.vitorpamplona.quartz.nip34Git.git.GitSmartHttpTransport
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [GitMergePlanner] over real repositories served by `git http-backend`, checked against git's own merge. */
class GitMergePlannerGitTest {
    private val maintainer = GitPerson("Maintainer", "m@x.org", 1700001000)

    private fun write(
        dir: File,
        name: String,
        text: String,
    ) = File(dir, name).also { it.parentFile.mkdirs() }.writeText(text)

    @Test
    fun aPullRequestMergesAsGitMergesItAndPushes() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            write(work, "f.txt", "1\n2\n3\n4\n5\n")
            write(work, "g.txt", "g\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            // The PR changes the last line of f and adds a file; main meanwhile changes the first line.
            git.git(work, "checkout", "-q", "-b", "pr")
            write(work, "f.txt", "1\n2\n3\n4\nFIVE\n")
            write(work, "dir/h.txt", "h\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "pr change")
            git.git(work, "push", "-q", "origin", "HEAD:refs/nostr/pr")
            git.git(work, "checkout", "-q", "main")
            write(work, "f.txt", "ONE\n2\n3\n4\n5\n")
            git.git(work, "commit", "-q", "-am", "main change")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            val main = git.git(work, "rev-parse", "main")
            val pr = git.git(work, "rev-parse", "pr")
            val transport = GitSmartHttpTransport { git.client }
            val source = GitObjectSource(transport, listOf(url))
            val plan = runBlocking { GitMergePlanner(source).planPullRequest(main, pr, GitObjectSource(transport, listOf(url)), maintainer, "Merge #12345678: pr") }
            (plan as? GitMergePlan.Failed)?.let { error(it.reason) }
            val ready = assertIs<GitMergePlan.Ready>(plan)

            // Same tree as git's own merge of the two.
            assertEquals(git.git(work, "merge-tree", "--write-tree", main, pr), git.git(bare, "rev-parse", "--verify", "-q", "${ready.newTip}^{tree}").ifEmpty { treeOf(ready) })

            val report = runBlocking { GitPushClient({ git.client }).push(url, listOf(GitRefUpdate("refs/heads/main", main, ready.newTip)), ready.objects) }
            assertTrue(report.isSuccess, "push: ${report.unpackError} ${report.refErrors}")
            assertEquals("ONE\n2\n3\n4\nFIVE", git.git(bare, "show", "main:f.txt"))
            assertEquals("h", git.git(bare, "show", "main:dir/h.txt"))
            assertEquals("$main $pr", git.git(bare, "log", "-1", "--format=%P", "main"))
            assertEquals("", git.git(bare, "fsck", "--no-progress", "--connectivity-only"))
        }

    private fun treeOf(ready: GitMergePlan.Ready): String =
        ready.objects
            .first { it.oid == ready.newTip }
            .data
            .decodeToString()
            .lineSequence()
            .first()
            .removePrefix("tree ")

    @Test
    fun bothSidesEditingOneLineIsAConflict() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            write(work, "f.txt", "1\n2\n3\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            git.git(work, "checkout", "-q", "-b", "pr")
            write(work, "f.txt", "1\ntwo\n3\n")
            git.git(work, "commit", "-q", "-am", "pr")
            git.git(work, "push", "-q", "origin", "HEAD:refs/nostr/pr")
            git.git(work, "checkout", "-q", "main")
            write(work, "f.txt", "1\nzwei\n3\n")
            git.git(work, "commit", "-q", "-am", "main")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            val transport = GitSmartHttpTransport { git.client }
            val plan =
                runBlocking {
                    GitMergePlanner(GitObjectSource(transport, listOf(url)))
                        .planPullRequest(git.git(work, "rev-parse", "main"), git.git(work, "rev-parse", "pr"), GitObjectSource(transport, listOf(url)), maintainer, "m")
                }
            (plan as? GitMergePlan.Failed)?.let { error(it.reason) }
            assertEquals(listOf("f.txt"), assertIs<GitMergePlan.Conflicts>(plan).paths)
        }

    @Test
    fun aPatchRebuildsToTheCommitItNames() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            write(work, "f.txt", "1\n2\n3\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            val base = git.git(work, "rev-parse", "HEAD")

            write(work, "f.txt", "1\n2\nthree\n")
            write(work, "new.txt", "new\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "Change three\n\nAnd add a file.")
            val commit = git.git(work, "rev-parse", "HEAD")
            val patchText = git.git(work, "format-patch", "-1", "--stdout", "HEAD")
            val authorTime = git.git(work, "log", "-1", "--format=%at")
            val committerTime = git.git(work, "log", "-1", "--format=%ct")
            git.git(work, "reset", "-q", "--hard", base)

            val patch =
                GitPatchEvent(
                    "0".repeat(64),
                    "1".repeat(64),
                    1700000000,
                    arrayOf(
                        arrayOf("commit", commit),
                        arrayOf("parent-commit", base),
                        arrayOf("author", "Ann", "ann@x.org", authorTime, "0"),
                        arrayOf("committer", "Ann", "ann@x.org", committerTime, "0"),
                        arrayOf("t", "root"),
                    ),
                    patchText,
                    "0".repeat(128),
                )
            val transport = GitSmartHttpTransport { git.client }
            val plan = runBlocking { GitMergePlanner(GitObjectSource(transport, listOf(url))).planPatches(base, listOf(patch), maintainer, "Merge #00000000: Change three") }
            (plan as? GitMergePlan.Failed)?.let { error(it.reason) }
            val ready = assertIs<GitMergePlan.Ready>(plan)
            assertEquals(GitMergeStrategy.MERGE_COMMIT, ready.strategy)
            // Rebuilt exactly as sent: the commit the patch names is among what gets pushed.
            assertTrue(ready.objects.any { it.oid == commit }, "the rebuilt commit should hash to $commit")
        }
}
