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

import com.vitorpamplona.quartz.nip34Git.patch.LineDiff
import java.util.PriorityQueue

/** One version of a file in its history: the [commit] that changed it, and the file's blob then (null once deleted). */
class GitFileVersion(
    val commit: GitCommit,
    val blobOid: String?,
    /** The history window ended here: this commit's parents were not read, so the file may be older. */
    val atHistoryEdge: Boolean = false,
    /**
     * The versions this one came from, as commit ids: for each of the commit's parents, the
     * version of the file that parent had (a merge has several). Empty for the first version.
     */
    val parentVersions: List<String> = emptyList(),
)

/** Who last changed each line of a file: [lines]' commit, parallel to the file's lines at its newest version. */
class GitBlame(
    val versions: List<GitFileVersion>,
    /** For each line of the newest version, the index into [versions] of the commit that wrote it. */
    val lines: IntArray,
    /** True when the history stopped (its depth) before every line's origin was found: those lines name the oldest version read. */
    val truncated: Boolean,
)

/**
 * A file's history and blame over smart HTTP, as `git log -- <path>` and `git blame` read them,
 * from a bounded window of history: commits and trees come down without blobs (`blob:none`), and
 * only the versions of the one file are fetched.
 */
class GitFileHistory(
    private val transport: GitSmartHttpTransport,
) {
    /**
     * The commits that changed [path] (segments) in the [depth] commits before [tip], newest first
     * by committer date as `git log` orders them, with git's default history simplification: a
     * merge that kept one parent's version follows that parent alone. A change of mode alone
     * (`chmod +x`) is a change, as git counts it.
     */
    suspend fun history(
        cloneUrl: String,
        tip: String,
        path: List<String>,
        depth: Int = DEFAULT_DEPTH,
    ): List<GitFileVersion> {
        val caps = transport.fetchCapabilities(cloneUrl)
        val objects = Packfile.parse(transport.fetchPack(cloneUrl, caps, listOf(tip), deepen = depth, filter = "blob:none"))
        val commits = HashMap<String, GitCommit>()
        val committedAt = HashMap<String, Long>()
        val trees = HashMap<String, List<GitTreeEntry>>()
        objects.values.forEach { obj ->
            when (obj.type) {
                GitObjectType.COMMIT -> {
                    val commit = GitObjectParser.parseCommit(obj.oid, obj.data)
                    commits[obj.oid] = commit
                    committedAt[obj.oid] = committerTime(obj.data) ?: commit.authorTimeSec
                }
                GitObjectType.TREE -> trees[obj.oid] = GitObjectParser.parseTree(obj.data)
                else -> {}
            }
        }

        // The file at a commit as mode and blob, so a mode-only change differs too.
        fun entryAt(commit: GitCommit): GitTreeEntry? {
            var tree = commit.treeOid
            path.forEachIndexed { index, name ->
                val entry = trees[tree]?.firstOrNull { it.name == name } ?: return null
                if (index == path.lastIndex) return entry.takeIf { !entry.isFolder }
                if (!entry.isFolder) return null
                tree = entry.oid
            }
            return null
        }

        fun sameFile(
            a: GitTreeEntry?,
            b: GitTreeEntry?,
        ) = a?.oid == b?.oid && a?.mode == b?.mode

        // The TREESAME parent each commit's history runs through, when it has one.
        val treesame = HashMap<String, String?>()

        fun treesameParent(commit: GitCommit): String? =
            treesame.getOrPut(commit.oid) {
                val mine = entryAt(commit)
                commit.parents.firstOrNull { parent -> commits[parent]?.let { sameFile(entryAt(it), mine) } == true }
            }

        // The commit whose version of the file [oid] holds: itself when it changed it, else its TREESAME ancestor's.
        fun versionOf(oid: String): String {
            var current = oid
            while (true) {
                val commit = commits[current] ?: return current
                current = treesameParent(commit) ?: return current
            }
        }

        val result = ArrayList<GitFileVersion>()
        val seen = HashSet<String>()
        val frontier = PriorityQueue<GitCommit>(compareByDescending { committedAt[it.oid] ?: it.authorTimeSec })
        commits[tip]?.let { frontier.add(it) }
        while (frontier.isNotEmpty()) {
            val commit = frontier.poll()
            if (!seen.add(commit.oid)) continue
            val same = treesameParent(commit)
            // Unchanged from a parent: not part of the file's history, which runs on through that parent.
            if (same != null) {
                commits[same]?.let { frontier.add(it) }
                continue
            }
            val parents = commit.parents.mapNotNull { commits[it] }
            val edge = parents.size < commit.parents.size
            val mine = entryAt(commit)
            if (mine != null || parents.isNotEmpty()) {
                result.add(GitFileVersion(commit, mine?.oid, atHistoryEdge = edge, parentVersions = parents.map { versionOf(it.oid) }.distinct()))
            }
            parents.forEach { frontier.add(it) }
        }
        return result
    }

    /**
     * The commit that last changed each line of [path] at [tip], read from its [maxVersions] latest
     * versions as `git blame` does: a version hands each of its lines to the first parent version
     * that holds it ([LineDiff.matches]), so a merge passes lines to whichever side wrote them, and
     * a line no parent holds was written by that version.
     */
    suspend fun blame(
        cloneUrl: String,
        tip: String,
        path: List<String>,
        depth: Int = DEFAULT_DEPTH,
        maxVersions: Int = DEFAULT_BLAME_VERSIONS,
    ): GitBlame? {
        val history = history(cloneUrl, tip, path, depth)
        if (history.isEmpty() || history[0].blobOid == null) return null
        val versions = history.take(maxVersions)
        val index = HashMap<String, Int>()
        versions.forEachIndexed { i, version -> index[version.commit.oid] = i }
        val texts = readBlobs(cloneUrl, versions.mapNotNull { it.blobOid }.distinct())
        val lineCache = HashMap<Int, List<String>>()

        fun linesOf(k: Int) =
            lineCache.getOrPut(k) {
                val oid = versions[k].blobOid ?: return@getOrPut emptyList()
                (texts[oid]?.decodeToString() ?: "").removeSuffix("\n").split('\n')
            }

        // Each version's parents within the window read; a parent outside it ends the trail there.
        val parents = versions.map { version -> version.parentVersions.mapNotNull { index[it] } }
        val outside = versions.mapIndexed { k, version -> version.atHistoryEdge || parents[k].size < version.parentVersions.size }

        // Children first: a version is read once every version that hands lines to it was.
        val pendingChildren = IntArray(versions.size)
        parents.forEach { ps -> ps.forEach { pendingChildren[it]++ } }

        val newest = linesOf(0)
        val owner = IntArray(newest.size) { -1 }
        // The newest lines still unowned at each version, and where each sits in that version.
        val suspects = HashMap<Int, MutableList<IntArray>>()
        suspects[0] = newest.indices.mapTo(ArrayList()) { intArrayOf(it, it) }
        var truncated = false
        val ready = ArrayDeque<Int>()
        ready.add(0)
        while (ready.isNotEmpty()) {
            val k = ready.removeFirst()
            var left: List<IntArray> = suspects.remove(k).orEmpty()
            if (left.isNotEmpty()) {
                val current = linesOf(k)
                for (p in parents[k]) {
                    if (left.isEmpty()) break
                    val currentToParent = IntArray(current.size) { -1 }
                    LineDiff.matches(linesOf(p), current).forEachIndexed { o, c -> if (c >= 0) currentToParent[c] = o }
                    val stays = ArrayList<IntArray>()
                    val passed = suspects.getOrPut(p) { ArrayList() }
                    left.forEach { suspect ->
                        val position = suspect[1]
                        val there = if (position in currentToParent.indices) currentToParent[position] else -1
                        if (there >= 0) passed.add(intArrayOf(suspect[0], there)) else stays.add(suspect)
                    }
                    left = stays
                }
                left.forEach { owner[it[0]] = k }
                // Lines owned here may be older than the window shows.
                if (left.isNotEmpty() && outside[k]) truncated = true
            }
            parents[k].forEach { p -> if (--pendingChildren[p] == 0) ready.add(p) }
        }
        // Versions the walk never reached (a cycle can't happen in git; this is only a guard) keep their lines.
        suspects.forEach { (k, list) -> list.forEach { if (owner[it[0]] < 0) owner[it[0]] = k } }
        return GitBlame(versions, owner, truncated)
    }

    /** The committer's timestamp in a raw commit object (`committer Name <email> <time> <tz>`). */
    private fun committerTime(data: ByteArray): Long? {
        val text = data.decodeToString(0, minOf(data.size, 4096))
        val line = text.lineSequence().takeWhile { it.isNotEmpty() }.firstOrNull { it.startsWith("committer ") } ?: return null
        val afterEmail = line.substringAfterLast('>', "").trim().split(' ')
        return afterEmail.firstOrNull()?.toLongOrNull()
    }

    /** [oids]' contents in one request (a GRASP server serves any reachable object by id). */
    private suspend fun readBlobs(
        cloneUrl: String,
        oids: List<String>,
    ): Map<String, ByteArray> {
        if (oids.isEmpty()) return emptyMap()
        val caps = transport.fetchCapabilities(cloneUrl)
        return Packfile.parse(transport.fetchPack(cloneUrl, caps, oids, deepen = null, filter = null)).mapValues { it.value.data }
    }

    companion object {
        const val DEFAULT_DEPTH = 300
        const val DEFAULT_BLAME_VERSIONS = 60
    }
}
