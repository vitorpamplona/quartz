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
package com.vitorpamplona.quartz.nip34Git.git

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [GitFileHistory] and [GitRepoSnapshot.searchContents] over real git, checked against `git log` and `git blame`. */
class GitFileHistoryGitTest {
    private fun commit(
        git: GitHttpBackendFixture,
        work: File,
        file: String,
        text: String,
        message: String,
    ): String {
        File(work, file).also { it.parentFile.mkdirs() }.writeText(text)
        git.git(work, "add", ".")
        git.git(work, "commit", "-q", "-m", message)
        return git.git(work, "rev-parse", "HEAD")
    }

    @Test
    fun historyAndBlameMatchGit() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            commit(git, work, "src/f.txt", "a\nb\nc\n", "add f")
            commit(git, work, "other.txt", "x\n", "unrelated")
            commit(git, work, "src/f.txt", "a\nB\nc\nd\n", "edit b, add d")
            commit(git, work, "other.txt", "y\n", "unrelated again")
            val tip = commit(git, work, "src/f.txt", "zero\na\nB\nc\nd\n", "prepend zero")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            val files = GitHttpClient { git.client }.files
            val history = runBlocking { files.history(url, tip, listOf("src", "f.txt")) }
            assertEquals(git.git(work, "log", "--format=%H", "--", "src/f.txt").lines(), history.map { it.commit.oid })

            val blame = runBlocking { files.blame(url, tip, listOf("src", "f.txt")) }!!
            val expected =
                git
                    .git(work, "blame", "--line-porcelain", "src/f.txt")
                    .lines()
                    .filter { it.matches(Regex("^[0-9a-f]{40} .*")) }
                    .map { it.substringBefore(' ') }
            assertEquals(expected, blame.lines.map { blame.versions[it].commit.oid })
            assertFalse(blame.truncated)
        }

    @Test
    fun searchReadsFileContents() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            commit(git, work, "a.kt", "fun main() {\n    println(\"Needle here\")\n}\n", "a")
            commit(git, work, "dir/b.md", "no match\nanother NEEDLE\n", "b")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            val snapshot = runBlocking { GitHttpClient { git.client }.open(url) }
            val matches = runBlocking { snapshot.searchContents("needle", batchSize = 1) }
            assertEquals(setOf("a.kt:2", "dir/b.md:2"), matches.map { it.path.joinToString("/") + ":" + it.line }.toSet())
            assertTrue(matches.any { it.text == "println(\"Needle here\")" })
        }
}
