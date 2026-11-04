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
import kotlin.coroutines.cancellation.CancellationException

/** How a merge lands on the target branch. */
enum class GitMergeStrategy {
    /** A two-parent merge commit, as `git merge --no-ff` (gitworkshop's and ngit's PR merge). */
    MERGE_COMMIT,

    /** The patches replayed one by one on the branch tip, as `git am`, when they can't be rebuilt as sent. */
    APPLY_TO_TIP,
}

/** What merging an item would do, before anything is signed or pushed. */
sealed interface GitMergePlan {
    /**
     * Ready to push: the branch moves to [newTip], and [objects] are what a server may lack.
     * [mergeCommit] is the commit the status names; [appliedCommits] the commits a patch series
     * became.
     */
    class Ready(
        val strategy: GitMergeStrategy,
        val newTip: String,
        val objects: List<GitObject>,
        val mergeCommit: String,
        val appliedCommits: List<String>,
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
            prSource.loadHistory(listOf(prTip))
            // The PR's own objects, beyond what the target branch has: the merge needs them, and
            // the target's servers may not have them.
            val branchObjects = prSource.fetch(listOf(prTip), haves = listOf(targetHead)).values
            branchObjects.forEach(source::add)
            source.loadHistory(listOf(targetHead, prTip))

            val base = source.mergeBase(targetHead, prTip) ?: return@guard GitMergePlan.Failed("the pull request shares no history with the branch")
            if (base == prTip) return@guard GitMergePlan.AlreadyMerged

            val builder = GitTreeBuilder(source)
            val ours = source.commit(targetHead).treeOid
            val theirs = source.commit(prTip).treeOid
            val tree = if (base == targetHead) theirs else builder.mergeTrees(source.commit(base).treeOid, ours, theirs)
            if (builder.conflicts.isNotEmpty()) return@guard GitMergePlan.Conflicts(builder.conflicts)

            val merge = builder.keep(GitObjectWriter.commit(tree, listOf(targetHead, prTip), committer, committer, message))
            GitMergePlan.Ready(
                strategy = GitMergeStrategy.MERGE_COMMIT,
                newTip = merge.oid,
                objects = (branchObjects + builder.created.values).distinctBy { it.oid },
                mergeCommit = merge.oid,
                appliedCommits = emptyList(),
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
            tree = applyPatch(builder, tree, patch) ?: return null
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
        patches.forEachIndexed { index, patch ->
            tree = applyPatch(builder, tree, patch) ?: return GitMergePlan.Conflicts(listOf("patch ${index + 1}"))
            val author = patch.author()?.let(::personOf) ?: committer
            val commit = builder.keep(GitObjectWriter.commit(tree, listOf(parent), author, committer, commitMessage(patch)))
            applied.add(commit.oid)
            parent = commit.oid
        }
        return GitMergePlan.Ready(GitMergeStrategy.APPLY_TO_TIP, parent, builder.created.values.toList(), parent, applied)
    }

    /** [patch]'s diff applied to [treeOid]: the new tree, or null when a hunk finds no place. */
    private suspend fun applyPatch(
        builder: GitTreeBuilder,
        treeOid: String,
        patch: GitPatchEvent,
    ): String? {
        val parsed = UnifiedDiffParser.parse(patch.content)
        val changes = LinkedHashMap<List<String>, GitTreeEntry?>()
        for (file in parsed.files) {
            if (file.isBinary) return null
            val oldPath = file.oldPath?.split('/')
            val newPath = file.newPath?.split('/')
            when (file.change) {
                GitFileChange.DELETE -> changes[oldPath ?: return null] = null
                GitFileChange.ADD -> {
                    val text = LineMerge.apply("", file) ?: return null
                    changes[newPath ?: return null] = GitTreeEntry("100644", newPath.last(), builder.keep(GitObjectWriter.blob(text.encodeToByteArray())).oid)
                }
                GitFileChange.MODIFY, GitFileChange.RENAME -> {
                    val from = oldPath ?: newPath ?: return null
                    val to = newPath ?: from
                    val entry = builder.entryAt(treeOid, from) ?: return null
                    val text = LineMerge.apply(source.blob(entry.oid).decodeToString(), file) ?: return null
                    if (to != from) changes[from] = null
                    changes[to] = GitTreeEntry(entry.mode, to.last(), builder.keep(GitObjectWriter.blob(text.encodeToByteArray())).oid)
                }
            }
        }
        return builder.rewrite(treeOid, changes)
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
