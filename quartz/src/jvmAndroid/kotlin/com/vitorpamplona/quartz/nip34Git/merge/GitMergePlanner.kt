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

import com.vitorpamplona.quartz.nip34Git.git.GitObject
import com.vitorpamplona.quartz.nip34Git.git.GitObjectParser
import com.vitorpamplona.quartz.nip34Git.git.GitObjectWriter
import com.vitorpamplona.quartz.nip34Git.git.GitPerson
import com.vitorpamplona.quartz.nip34Git.git.GitTreeEntry
import com.vitorpamplona.quartz.nip34Git.patch.GitFileChange
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.patch.LineMerge
import com.vitorpamplona.quartz.nip34Git.patch.UnifiedDiffParser
import com.vitorpamplona.quartz.nip34Git.patch.tags.Committer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** How a merge lands on the target branch. */
enum class GitMergeStrategy {
    /** A two-parent merge commit, as `git merge --no-ff` (gitworkshop's and ngit's PR merge). */
    MERGE_COMMIT,

    /** The patches replayed one by one on the branch tip, as `git am`, when they can't be rebuilt as sent. */
    APPLY_TO_TIP,
}

/**
 * The objects of a pull request's branch beyond its merge base [base] with the target. A server
 * that already has [tip] (a GRASP server keeps each PR under `refs/nostr/<id>`) needs none of them;
 * for one that doesn't, [objects] fetches them once, on first use.
 */
class GitBranchObjects(
    val tip: String,
    private val base: String,
    private val source: GitObjectSource,
) {
    private val lock = Mutex()
    private var loaded: Collection<GitObject>? = null

    suspend fun objects(): Collection<GitObject> =
        lock.withLock {
            loaded ?: source.fetch(listOf(tip), haves = listOf(base)).values.also { loaded = it }
        }
}

/** What merging an item would do, before anything is signed or pushed. */
sealed interface GitMergePlan {
    /**
     * Ready to push: the branch moves to [newTip]; [objects] are the ones the merge made, and
     * [branch] the pull request's, for a server that doesn't have them.
     * [mergeCommit] is the commit the status names; [appliedCommits] the commits a patch series
     * became.
     */
    class Ready(
        val strategy: GitMergeStrategy,
        val newTip: String,
        val objects: List<GitObject>,
        val mergeCommit: String,
        val appliedCommits: List<String>,
        /** A pull request's own commits, which a server that lacks its tip also needs. */
        val branch: GitBranchObjects? = null,
    ) : GitMergePlan

    /** The item's commits are already in the target branch. */
    object AlreadyMerged : GitMergePlan

    /** These paths changed on both sides in ways that need a person. */
    class Conflicts(
        val paths: List<String>,
    ) : GitMergePlan

    class Failed(
        val reason: String,
    ) : GitMergePlan
}

/**
 * Plans the merge of a NIP-34 pull request or patch series into a branch, as gitworkshop does in
 * the browser: the objects and the new branch tip, computed from the servers' objects and
 * nothing local. Pushing them and publishing the state and status are the caller's.
 */
class GitMergePlanner(
    private val source: GitObjectSource,
) {
    /**
     * A pull request (kind 1618) at [prTip], served by [prSource], merged into [targetHead] with a
     * two-parent merge commit written by [committer] with [message].
     */
    suspend fun planPullRequest(
        targetHead: String,
        prTip: String,
        prSource: GitObjectSource,
        committer: GitPerson,
        message: String,
    ): GitMergePlan =
        guard {
            // Commits only, to find the merge base before anything heavier is asked for: a fork's
            // server may not know the target's head, and asked to send what that lacks would send
            // its whole history. The merge base it always has.
            prSource.loadHistory(listOf(prTip))
            source.adopt(prSource)
            source.loadHistory(listOf(targetHead), haves = listOf(prTip))

            val base = source.mergeBase(targetHead, prTip) ?: return@guard GitMergePlan.Failed("the pull request shares no history with the branch")
            if (base == prTip) return@guard GitMergePlan.AlreadyMerged

            // The PR's trees, for the tree merge; blobs only for the files both sides changed.
            if (base != targetHead) {
                prSource.fetch(listOf(prTip), haves = listOf(base), filter = "blob:none")
                source.adopt(prSource)
            }

            val builder = GitTreeBuilder(source)
            val ours = source.commit(targetHead).treeOid
            val theirs = source.commit(prTip).treeOid
            val tree = if (base == targetHead) theirs else builder.mergeTrees(source.commit(base).treeOid, ours, theirs)
            if (builder.conflicts.isNotEmpty()) return@guard GitMergePlan.Conflicts(builder.conflicts)

            val merge = builder.keep(GitObjectWriter.commit(tree, listOf(targetHead, prTip), committer, committer, message))
            GitMergePlan.Ready(
                strategy = GitMergeStrategy.MERGE_COMMIT,
                newTip = merge.oid,
                objects = builder.created.values.toList(),
                mergeCommit = merge.oid,
                appliedCommits = emptyList(),
                branch = GitBranchObjects(prTip, base, prSource),
            )
        }

    /**
     * A patch series (kind 1617, in order) merged into [targetHead]. First as sent: rebuilt on its
     * `parent-commit` with its own `author`/`committer`, which must hash to its `commit` tags, then
     * merged with a merge commit. Failing that, replayed on [targetHead] with [committer] as the
     * committer (apply-to-tip).
     */
    suspend fun planPatches(
        targetHead: String,
        patches: List<GitPatchEvent>,
        committer: GitPerson,
        message: String,
    ): GitMergePlan =
        guard {
            source.loadHistory(listOf(targetHead))
            val asSent = rebuildAsSent(patches)
            if (asSent != null) {
                val (tip, builder) = asSent
                if (tip in source.ancestors(targetHead)) return@guard GitMergePlan.AlreadyMerged
                val base = patches.first().parentCommit()!!
                val ours = source.commit(targetHead).treeOid
                val theirs = GitObjectParser.parseCommitTree(builder.created.getValue(tip).data)!!
                val tree = if (base == targetHead) theirs else builder.mergeTrees(source.commit(base).treeOid, ours, theirs)
                if (builder.conflicts.isEmpty()) {
                    val merge = builder.keep(GitObjectWriter.commit(tree, listOf(targetHead, tip), committer, committer, message))
                    return@guard GitMergePlan.Ready(GitMergeStrategy.MERGE_COMMIT, merge.oid, builder.created.values.toList(), merge.oid, emptyList())
                }
            }
            applyToTip(targetHead, patches, committer)
        }

    /** The series rebuilt on its `parent-commit`, or null when a commit doesn't hash to its tag. */
    private suspend fun rebuildAsSent(patches: List<GitPatchEvent>): Pair<String, GitTreeBuilder>? {
        val first = patches.first().parentCommit() ?: return null
        val builder = GitTreeBuilder(source)
        source.loadHistory(listOf(first))
        var parent = first
        var tree = source.commit(first).treeOid
        for (patch in patches) {
            val claimed = patch.commit() ?: return null
            val author = patch.author()?.let(::personOf) ?: return null
            val committer = patch.committer().firstOrNull()?.let(::personOf) ?: author
            tree = applyPatch(builder, tree, patch).tree ?: return null
            val commit = GitObjectWriter.commit(tree, listOf(parent), author, committer, commitMessage(patch))
            if (commit.oid != claimed) return null
            builder.keep(commit)
            parent = commit.oid
        }
        return parent to builder
    }

    private suspend fun applyToTip(
        targetHead: String,
        patches: List<GitPatchEvent>,
        committer: GitPerson,
    ): GitMergePlan {
        val builder = GitTreeBuilder(source)
        var parent = targetHead
        var tree = source.commit(targetHead).treeOid
        val applied = ArrayList<String>()
        patches.forEach { patch ->
            val result = applyPatch(builder, tree, patch)
            tree = result.tree ?: return GitMergePlan.Conflicts(listOfNotNull(result.failedPath))
            val author = patch.author()?.let(::personOf) ?: committer
            val commit = builder.keep(GitObjectWriter.commit(tree, listOf(parent), author, committer, commitMessage(patch)))
            applied.add(commit.oid)
            parent = commit.oid
        }
        return GitMergePlan.Ready(GitMergeStrategy.APPLY_TO_TIP, parent, builder.created.values.toList(), parent, applied)
    }

    /** A patch applied to a tree: the new tree, or the path that would not apply. */
    private class Applied(
        val tree: String?,
        val failedPath: String?,
    )

    /**
     * [patch]'s diff applied to [treeOid], as `git apply` would: a file it adds must not exist, a
     * file it changes or deletes must, modes it states are kept, and a text that is not UTF-8
     * (the patch's own encoding) does not apply.
     */
    private suspend fun applyPatch(
        builder: GitTreeBuilder,
        treeOid: String,
        patch: GitPatchEvent,
    ): Applied {
        val files = UnifiedDiffParser.parse(patch.content).files
        // The current entry of every file the patch reads, and all their blobs in one request.
        val current = files.map { file -> (file.oldPath ?: file.newPath)?.split('/')?.let { builder.entryAt(treeOid, it) } }
        source.prefetch(files.indices.filter { files[it].change == GitFileChange.MODIFY || files[it].change == GitFileChange.RENAME }.mapNotNull { current[it]?.oid })

        val changes = LinkedHashMap<List<String>, GitTreeEntry?>()
        files.forEachIndexed { index, file ->
            val failed = Applied(null, file.displayPath)
            if (file.isBinary) return failed
            val oldPath = file.oldPath?.split('/')
            val newPath = file.newPath?.split('/')
            val entry = current[index]
            when (file.change) {
                GitFileChange.DELETE -> {
                    if (oldPath == null || entry == null) return failed
                    changes[oldPath] = null
                }
                GitFileChange.ADD -> {
                    if (newPath == null || builder.entryAt(treeOid, newPath) != null) return failed
                    val text = LineMerge.apply("", file) ?: return failed
                    val blob = builder.keep(GitObjectWriter.blob(text.encodeToByteArray()))
                    changes[newPath] = GitTreeEntry(file.newMode ?: "100644", newPath.last(), blob.oid)
                }
                GitFileChange.MODIFY, GitFileChange.RENAME -> {
                    val from = oldPath ?: newPath ?: return failed
                    val to = newPath ?: from
                    if (entry == null || entry.isDirectory) return failed
                    if (to != from && builder.entryAt(treeOid, to) != null) return failed
                    val bytes = source.blob(entry.oid)
                    val oid =
                        if (file.hunks.isEmpty()) {
                            // A rename or mode change only: the content stays byte for byte.
                            entry.oid
                        } else {
                            val text = utf8(bytes) ?: return failed
                            val applied = LineMerge.apply(text, file) ?: return failed
                            builder.keep(GitObjectWriter.blob(applied.encodeToByteArray())).oid
                        }
                    if (to != from) changes[from] = null
                    changes[to] = GitTreeEntry(file.newMode ?: entry.mode, to.last(), oid)
                }
            }
        }
        return Applied(builder.rewrite(treeOid, changes), null)
    }

    private fun utf8(bytes: ByteArray): String? =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            null
        }

    private inline fun guard(block: () -> GitMergePlan): GitMergePlan =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitMergePlan.Failed(e.message ?: e::class.simpleName ?: "merge failed")
        }

    companion object {
        /** A patch's commit message: its subject line, then its body, as `git am` writes it. */
        fun commitMessage(patch: GitPatchEvent): String {
            val description = patch.description()
            if (description != null) return description.trim()
            val message = UnifiedDiffParser.parse(patch.content).message.trim()
            val subject = patch.subject()?.trim()
            return when {
                subject.isNullOrBlank() -> message
                message.isBlank() || message.startsWith(subject) -> message.ifBlank { subject }
                else -> "$subject\n\n$message"
            }
        }

        /** A patch tag's person, with its offset given as minutes or as git's `±HHMM`. */
        fun personOf(tag: Committer): GitPerson? {
            val time = tag.timestamp?.toLongOrNull() ?: return null
            val tz = tag.timezoneInMinutes?.trim().orEmpty()
            val minutes =
                if (tz.length == 5 && (tz[0] == '+' || tz[0] == '-') && tz.drop(1).all(Char::isDigit)) {
                    val sign = if (tz[0] == '-') -1 else 1
                    sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt())
                } else {
                    tz.toIntOrNull() ?: 0
                }
            return GitPerson(tag.name.orEmpty(), tag.email.orEmpty(), time, minutes)
        }
    }
}
