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

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip34Git.git.GitHttpBackendFixture
import com.vitorpamplona.quartz.nip34Git.git.GitObjectWriter
import com.vitorpamplona.quartz.nip34Git.git.GitPerson
import com.vitorpamplona.quartz.nip34Git.git.GitPushClient
import com.vitorpamplona.quartz.nip34Git.git.GitTreeEntry
import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import com.vitorpamplona.quartz.nip34Git.state.tags.RefTag
import com.vitorpamplona.quartz.testing.RelayClientTest
import com.vitorpamplona.quartz.testing.TestRelay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [GraspPusher]: the state goes to each server's relay, and only a server whose relay took it is pushed. */
class GraspPusherTest : RelayClientTest() {
    private val signer = NostrSignerInternal(KeyPair())

    private object RefuseAll : PassThroughPolicy() {
        override fun accept(cmd: EventCmd): PolicyResult<EventCmd> = PolicyResult.Rejected("blocked: not a GRASP repository")
    }

    @Test
    fun pushesWhereTheStateLandedAndSkipsWhereItDidNot() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bareA, urlA) = git.bare("a")
            val (bareB, urlB) = git.bare("b")
            val work = git.work(bareA, "work")
            File(work, "f.txt").writeText("1\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "base")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            git.git(work, "push", "-q", urlB.replace("http://git.test", git.root.path), "HEAD:main")
            val base = git.git(work, "rev-parse", "HEAD")

            // One commit on top of main, written here, not by git.
            val person = GitPerson("M", "m@x.org", 1700000000)
            val blob = GitObjectWriter.blob("2\n".encodeToByteArray())
            val tree = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "f.txt", blob.oid)))
            val commit = GitObjectWriter.commit(tree.oid, listOf(base), person, person, "two")

            val relayA = RelayUrlNormalizer.normalize("ws://relay-a.test/")
            val relayB = RelayUrlNormalizer.normalize("ws://relay-b.test/")
            hub.getOrCreate(relayA)
            hub.register(TestRelay(relayB, policyBuilder = { RefuseAll }))

            val state = runBlocking { signer.sign(GitRepositoryStateEvent.advance(null, "repo", "main", commit.oid)) }
            val result =
                runBlocking {
                    GraspPusher(client, GitPushClient({ git.client }), settleMs = 10)
                        .push(state, listOf(GraspTarget(urlA, relayA), GraspTarget(urlB, relayB)), "main", commit.oid, listOf(blob, tree, commit))
                }

            assertTrue(result.isSuccess, result.describeFailures())
            val (a, b) = result.servers
            assertTrue(a.stateAccepted && a.pushed)
            assertFalse(b.stateAccepted || b.pushed)
            assertEquals(commit.oid, git.git(bareA, "rev-parse", "main"))
            assertEquals(base, git.git(bareB, "rev-parse", "main"))
            assertEquals(
                commit.oid,
                hub
                    .get(relayA)!!
                    .let { runBlocking { it.store.query<GitRepositoryStateEvent>(Filter(kinds = listOf(GitRepositoryStateEvent.KIND))) } }
                    .single()
                    .branches()
                    .single()
                    .commit,
            )
        }

    @Test
    fun theAdvancedStateKeepsTheOtherRefs() {
        val previous =
            runBlocking {
                signer.sign(
                    GitRepositoryStateEvent.build(
                        "repo",
                        listOf(
                            RefTag.branch("main", "a".repeat(40)),
                            RefTag.branch("dev", "b".repeat(40)),
                            RefTag.tag("v1", "c".repeat(40)),
                        ),
                        head = "main",
                    ),
                )
            }
        val next = GitRepositoryStateEvent.advance(previous, "repo", "main", "d".repeat(40))
        assertTrue(next.createdAt > previous.createdAt)
        val signed = runBlocking { signer.sign(next) }
        assertEquals(mapOf("refs/heads/main" to "d".repeat(40), "refs/heads/dev" to "b".repeat(40), "refs/tags/v1" to "c".repeat(40)), signed.refs().associate { it.name to it.commit })
        assertEquals("main", signed.head())
        assertEquals(1, signed.tags.count { it[0] == "HEAD" })
    }
}
