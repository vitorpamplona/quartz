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
     * The commits that changed [path] (segments) in the [depth] commits before [tip], newest first,
     * with git's default history simplification: a merge that kept one parent's version follows
     * that parent alone.
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
        val trees = HashMap<String, List<GitTreeEntry>>()
        objects.values.forEach { obj ->
            when (obj.type) {
                GitObjectType.COMMIT -> commits[obj.oid] = GitObjectParser.parseCommit(obj.oid, obj.data)
                GitObjectType.TREE -> trees[obj.oid] = GitObjectParser.parseTree(obj.data)
                else -> {}
            }
        }

        fun blobAt(commit: GitCommit): String? {
            var tree = commit.treeOid
            path.forEachIndexed { index, name ->
                val entry = trees[tree]?.firstOrNull { it.name == name } ?: return null
                if (index == path.lastIndex) return entry.oid.takeIf { !entry.isFolder }
                if (!entry.isFolder) return null
                tree = entry.oid
            }
            return null
        }

        val result = ArrayList<GitFileVersion>()
        val seen = HashSet<String>()
        val frontier = PriorityQueue<GitCommit>(compareByDescending { it.authorTimeSec })
        commits[tip]?.let { frontier.add(it) }
        while (frontier.isNotEmpty()) {
            val commit = frontier.poll()
            if (!seen.add(commit.oid)) continue
            val mine = blobAt(commit)
            val parents = commit.parents.mapNotNull { commits[it] }
            // Unchanged from a parent: not part of the file's history, which runs on through that parent.
            val same = parents.firstOrNull { blobAt(it) == mine }
            if (same != null) {
                frontier.add(same)
                continue
            }
            val edge = parents.size < commit.parents.size
            if (mine != null || parents.isNotEmpty()) result.add(GitFileVersion(commit, mine, atHistoryEdge = edge))
            parents.forEach { frontier.add(it) }
        }
        return result
    }

    /**
     * The commit that last changed each line of [path] at [tip], read from its [maxVersions] latest
     * versions: each version's lines are matched to the one before it ([LineDiff.matches]), and a
     * line with no match there was written by that version.
     */
    suspend fun blame(
        cloneUrl: String,
        tip: String,
        path: List<String>,
        depth: Int = DEFAULT_DEPTH,
        maxVersions: Int = DEFAULT_BLAME_VERSIONS,
    ): GitBlame? {
        val existing = history(cloneUrl, tip, path, depth).takeWhile { it.blobOid != null }
        val versions = existing.take(maxVersions)
        if (versions.isEmpty()) return null
        val texts = readBlobs(cloneUrl, versions.mapNotNull { it.blobOid }.distinct())

        fun linesOf(version: GitFileVersion) = (texts[version.blobOid]?.decodeToString() ?: "").removeSuffix("\n").split('\n')

        val newest = linesOf(versions[0])
        val owner = IntArray(newest.size) { -1 }
        // Where each newest line sits in the version being read.
        val at = IntArray(newest.size) { it }
        for (k in versions.indices) {
            val current = linesOf(versions[k])
            if (k == versions.lastIndex) {
                newest.indices.forEach { if (owner[it] < 0 && at[it] >= 0) owner[it] = k }
                break
            }
            val older = linesOf(versions[k + 1])
            val currentToOlder = IntArray(current.size) { -1 }
            LineDiff.matches(older, current).forEachIndexed { o, c -> if (c >= 0) currentToOlder[c] = o }
            newest.indices.forEach { line ->
                if (owner[line] >= 0) return@forEach
                val position = at[line]
                val previous = if (position in currentToOlder.indices) currentToOlder[position] else -1
                if (previous < 0) owner[line] = k else at[line] = previous
            }
        }
        // The window ran out with lines still traced further back: they are older than it shows.
        val truncated = versions.size < existing.size || versions.last().atHistoryEdge
        return GitBlame(versions, owner, truncated)
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
