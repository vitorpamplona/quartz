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

import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip34Git.git.GIT_ZERO_OID
import com.vitorpamplona.quartz.nip34Git.git.GitObject
import com.vitorpamplona.quartz.nip34Git.git.GitPushClient
import com.vitorpamplona.quartz.nip34Git.git.GitRefUpdate
import com.vitorpamplona.quartz.nip34Git.grasp.GraspServer
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** A GRASP server of a repository: its clone URL and the relay it reads the signed state from. */
class GraspTarget(
    val cloneUrl: String,
    val relay: NormalizedRelayUrl,
) {
    companion object {
        /**
         * The GRASP servers [announcement] names both ways (clone URL and relay). Its other clone
         * URLs are git hosts a browser cannot push to.
         */
        fun of(announcement: GitRepositoryEvent): List<GraspTarget> {
            val relays = announcement.relays()
            return announcement.clones().mapNotNull { url ->
                GraspServer.pairedRelay(url, relays)?.let(RelayUrlNormalizer::normalizeOrNull)?.let { GraspTarget(url, it) }
            }
        }

        /** True when every clone URL of [announcement] is a GRASP server, the case gitworkshop merges in the browser. */
        fun coversAllClones(announcement: GitRepositoryEvent): Boolean {
            val clones = announcement.clones()
            return clones.isNotEmpty() && of(announcement).size == clones.size
        }
    }
}

/** How one GRASP server took the push. */
class GraspServerOutcome(
    val target: GraspTarget,
    /** The server's relay stored the signed state; without it the server refuses the push. */
    val stateAccepted: Boolean,
    /** The branch on this server now points at the new tip. */
    val pushed: Boolean,
    val error: String?,
)

class GraspPushResult(
    val servers: List<GraspServerOutcome>,
) {
    /** At least one server holds the new tip: the merge happened, the others can catch up later. */
    val isSuccess: Boolean get() = servers.any { it.pushed }

    fun describeFailures(): String = servers.filter { !it.pushed }.joinToString("; ") { "${it.target.cloneUrl}: ${it.error ?: "not pushed"}" }
}

/**
 * Moves a branch on a repository's GRASP servers, the way gitworkshop and ngit do: the signed
 * state (kind 30618) goes to each server's relay first, since a GRASP server accepts a push only
 * when it matches the latest state it holds, then each server whose relay stored the state gets
 * the git push. Broadcasting the state elsewhere and publishing the status are the caller's,
 * once [push] reports success.
 */
class GraspPusher(
    private val client: INostrClient,
    private val git: GitPushClient,
    /** How long a server may take to process a state its relay just took, before a push retries. */
    private val settleMs: Long = 1_500,
    private val attempts: Int = 3,
) {
    suspend fun push(
        state: GitRepositoryStateEvent,
        targets: List<GraspTarget>,
        branch: String,
        newTip: String,
        objects: Collection<GitObject>,
        branchObjects: GitBranchObjects? = null,
    ): GraspPushResult {
        require(targets.isNotEmpty()) { "no GRASP server to push to" }
        val acks = client.publishAndCollectResults(state, targets.mapTo(LinkedHashSet()) { it.relay })
        val ref = "refs/heads/$branch"
        return coroutineScope {
            GraspPushResult(
                targets
                    .map { target ->
                        async {
                            val ack = acks[target.relay]
                            if (ack?.accepted != true) {
                                GraspServerOutcome(target, false, false, "its relay refused the state: ${ack?.message ?: "no answer"}")
                            } else {
                                pushOne(target, ref, newTip, objects, branchObjects)
                            }
                        }
                    }.awaitAll(),
            )
        }
    }

    private suspend fun pushOne(
        target: GraspTarget,
        ref: String,
        newTip: String,
        objects: Collection<GitObject>,
        branchObjects: GitBranchObjects?,
    ): GraspServerOutcome {
        var error: String? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(settleMs)
            try {
                // The old id is what this server holds now, which can differ from the state's
                // previous tip on a server that lagged behind.
                val advertisement = git.advertisement(target.cloneUrl)
                val current = advertisement.refs[ref]
                if (current == newTip) return GraspServerOutcome(target, true, true, null)
                // A server that holds the PR's tip (under refs/nostr/<id>) has all its commits.
                val toSend = if (branchObjects == null || branchObjects.tip in advertisement.refs.values) objects else objects + branchObjects.objects()
                val report = git.push(target.cloneUrl, listOf(GitRefUpdate(ref, current ?: GIT_ZERO_OID, newTip)), toSend, advertisement)
                if (report.isSuccess) {
                    // Trust the refs, not the report: a server can say ok and keep the old tip.
                    val after = git.advertisement(target.cloneUrl).refs[ref]
                    if (after == newTip) return GraspServerOutcome(target, true, true, null)
                    error = "the server reports $ref at ${after ?: "nothing"} after the push"
                } else {
                    error = report.describe()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
        return GraspServerOutcome(target, true, false, error)
    }
}
