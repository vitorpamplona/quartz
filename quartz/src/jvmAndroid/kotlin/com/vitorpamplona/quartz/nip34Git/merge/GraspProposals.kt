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

import com.vitorpamplona.quartz.nip34Git.git.GIT_ZERO_OID
import com.vitorpamplona.quartz.nip34Git.git.GitObject
import com.vitorpamplona.quartz.nip34Git.git.GitObjectWriter
import com.vitorpamplona.quartz.nip34Git.git.GitPerson
import com.vitorpamplona.quartz.nip34Git.git.GitPushClient
import com.vitorpamplona.quartz.nip34Git.git.GitRefUpdate
import com.vitorpamplona.quartz.nip34Git.git.GitTreeEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hands a pull request's commits (or a PR update's) to a repository's GRASP servers, as ngit does:
 * each server takes `refs/nostr/<event-id>` from anyone, no signed state needed, and keeps it while
 * a kind 1618 or 1619 with that id and tip arrives within 20 minutes (GRASP-01). The event can be
 * signed before or after; it is published once a server holds the commits.
 */
class GraspProposalPusher(
    private val git: GitPushClient,
) {
    /**
     * Pushes [tip] to `refs/nostr/[eventId]` on each of [cloneUrls], sending [objects] and, to a
     * server that doesn't hold the tip yet, the branch's own commits ([branchObjects]). Returns
     * each URL with null when it took the push, or why it didn't.
     */
    suspend fun push(
        eventId: String,
        cloneUrls: List<String>,
        tip: String,
        objects: Collection<GitObject> = emptyList(),
        branchObjects: GitBranchObjects? = null,
    ): Map<String, String?> =
        coroutineScope {
            cloneUrls
                .distinct()
                .map { url -> async { url to pushOne(url, refOf(eventId), tip, objects, branchObjects) } }
                .awaitAll()
                .toMap()
        }

    private suspend fun pushOne(
        cloneUrl: String,
        ref: String,
        tip: String,
        objects: Collection<GitObject>,
        branchObjects: GitBranchObjects?,
    ): String? =
        try {
            val advertisement = git.advertisement(cloneUrl)
            val current = advertisement.refs[ref]
            if (current == tip) {
                null
            } else {
                val hasTip = tip in advertisement.refs.values
                val toSend = if (branchObjects == null || hasTip) objects else objects + branchObjects.objects()
                val report = git.push(cloneUrl, listOf(GitRefUpdate(ref, current ?: GIT_ZERO_OID, tip)), toSend, advertisement)
                if (report.isSuccess) null else report.describe()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e::class.simpleName
        }

    companion object {
        /** The ref a GRASP server keeps a pull request's (or update's) commits under. */
        fun refOf(eventId: String) = "refs/nostr/$eventId"
    }
}

/** A commit made in the app: its id and every object a server may lack to take it. */
class GitNewCommit(
    val commit: String,
    val objects: List<GitObject>,
)

/**
 * A commit that changes files on top of [parent]: each path in [changes] set to new bytes (with
 * its mode kept, or `100644` for a new file), or removed with null. The trees it rewrites are read
 * from [source] (without blobs); only the new blobs, trees and the commit are created.
 */
suspend fun GitObjectSource.commitChanges(
    parent: String,
    changes: Map<List<String>, ByteArray?>,
    author: GitPerson,
    message: String,
): GitNewCommit {
    val builder = GitTreeBuilder(this)
    val root = commit(parent).treeOid
    val entries =
        changes.mapValues { (path, bytes) ->
            bytes?.let {
                val mode = builder.entryAt(root, path)?.mode ?: "100644"
                GitTreeEntry(mode, path.last(), builder.keep(GitObjectWriter.blob(it)).oid)
            }
        }
    val tree = builder.rewrite(root, entries) ?: builder.keep(GitObjectWriter.tree(emptyList())).oid
    val commit = builder.keep(GitObjectWriter.commit(tree, listOf(parent), author, author, message))
    return GitNewCommit(commit.oid, builder.created.values.toList())
}

/**
 * The newest commit [tip] (served by [branch]) shares with [targetHead] (served by [target]), read
 * from each side's recent [depth] commits first and from their whole histories only when that
 * window holds none, as a merge plans it. Null when they share no history.
 */
suspend fun mergeBaseOf(
    target: GitObjectSource,
    targetHead: String,
    branch: GitObjectSource,
    tip: String,
    depth: Int = GitMergePlanner.DEFAULT_HISTORY_DEPTH,
): String? {
    branch.loadHistory(listOf(tip), depth = depth)
    target.adopt(branch)
    target.loadHistory(listOf(targetHead), haves = listOf(tip), depth = depth)
    target.mergeBase(targetHead, tip)?.let { return it }
    branch.loadFullHistory(listOf(tip))
    target.adopt(branch)
    target.loadFullHistory(listOf(targetHead))
    return target.mergeBase(targetHead, tip)
}
