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
import com.vitorpamplona.quartz.utils.Log
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

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
 * from a bounded window of history. Only what the walk reads comes down: the window's commits
 * without trees (`tree:0`), then, one request per path segment, the distinct trees along the path
 * asked for by id (`tree:0` again, so nothing below them), then the versions of the one file. A
 * server that won't send trees by id gets the older fetch: every tree of the window (`blob:none`).
 *
 * The last [MAX_WINDOWS] windows are kept, with the path trees read through them, so the history
 * and then the blame of a file, or the history of a file next to it, do not fetch them again.
 */
class GitFileHistory(
    private val transport: GitSmartHttpTransport,
) {
    /** The commits within some generations of a tip, and the trees read so far along paths through them. */
    private class Window(
        val commits: Map<String, GitCommit>,
        val committedAt: Map<String, Long>,
    ) {
        /** Raw tree objects by oid: only the trees along the paths read so far. */
        val trees = ConcurrentHashMap<String, ByteArray>()

        /** False once the server would not send trees by id: other paths take the whole-window fetch. */
        @Volatile
        var treesById = true
    }

    private val windows =
        object : LinkedHashMap<String, Window>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Window>?) = size > MAX_WINDOWS
        }

    /**
     * The commits that changed [path] (segments) within [depth] generations of [tip] (as git's
     * `deepen` counts them: every commit at most [depth] parent steps away, which on a history
     * with merges is many more than [depth] commits), newest first by committer date as `git log`
     * orders them, with git's default history simplification: a merge that kept one parent's
     * version follows that parent alone. A change of mode alone (`chmod +x`) is a change, as git
     * counts it.
     */
    suspend fun history(
        cloneUrl: String,
        tip: String,
        path: List<String>,
        depth: Int = DEFAULT_DEPTH,
    ): List<GitFileVersion> {
        val caps = transport.fetchCapabilities(cloneUrl)
        val window = window(cloneUrl, caps, tip, path, depth)
        val commits = window.commits
        val committedAt = window.committedAt
        val trees = window.trees

        // The file at a commit as mode and blob, so a mode-only change differs too.
        val entries = HashMap<String, GitTreeEntry?>()

        fun entryAt(commit: GitCommit): GitTreeEntry? {
            if (entries.containsKey(commit.oid)) return entries[commit.oid]
            return pathEntry(trees, commit.treeOid, path).also { entries[commit.oid] = it }
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

    /** The window of [depth] generations from [tip], holding the trees along [path]: cached, or fetched. */
    private suspend fun window(
        cloneUrl: String,
        caps: GitCapabilities,
        tip: String,
        path: List<String>,
        depth: Int,
    ): Window {
        val key = "$cloneUrl\n$tip\n$depth"
        var window = synchronized(windows) { windows[key] }
        if (window == null && caps.supportsFilter) {
            window = windowOf(Packfile.parse(transport.fetchPack(cloneUrl, caps, listOf(tip), deepen = depth, filter = "tree:0")))
            synchronized(windows) { windows[key] = window }
        }
        if (window != null && window.treesById) {
            if (readPathTrees(cloneUrl, caps, window, path)) return window
            window.treesById = false
        }
        if (window != null && holdsPath(window, path)) return window
        return fetchWholeWindow(cloneUrl, caps, tip, path, depth, window).also { whole -> synchronized(windows) { windows[key] = whole } }
    }

    private fun windowOf(objects: Map<String, GitObject>): Window {
        val commits = HashMap<String, GitCommit>()
        val committedAt = HashMap<String, Long>()
        objects.values.forEach { obj ->
            if (obj.type == GitObjectType.COMMIT) {
                val commit = GitObjectParser.parseCommit(obj.oid, obj.data)
                commits[obj.oid] = commit
                committedAt[obj.oid] = committerTime(obj.data) ?: commit.authorTimeSec
            }
        }
        return Window(commits, committedAt)
    }

    /**
     * Fetches the trees along [path] in [window] that it doesn't hold yet, a level at a time: the
     * commits' root trees, then each one's entry for the next segment. False when the server
     * refused them or left some out.
     */
    private suspend fun readPathTrees(
        cloneUrl: String,
        caps: GitCapabilities,
        window: Window,
        path: List<String>,
    ): Boolean {
        try {
            var level: Set<String> = window.commits.values.mapTo(HashSet()) { it.treeOid }
            for (index in path.indices) {
                if (!fetchTrees(cloneUrl, caps, window, level.filter { !window.trees.containsKey(it) })) return false
                if (index == path.lastIndex) break
                val next = HashSet<String>()
                level.forEach { oid ->
                    window.trees[oid]
                        ?.let { GitObjectParser.findTreeEntry(it, path[index]) }
                        ?.takeIf { it.isFolder }
                        ?.let { next.add(it.oid) }
                }
                if (next.isEmpty()) break
                level = next
            }
            return true
        } catch (e: GitHttpException) {
            Log.w(TAG, "$cloneUrl would not send trees by id; fetching every tree of the window", e)
            return false
        }
    }

    /**
     * Fetches [wants] (trees) with `tree:0`, so nothing below them comes. git leaves out a wanted
     * tree that is also inside another wanted one, so what is missing is asked for again, a few
     * rounds at most. False when a round brings none of what is missing.
     */
    private suspend fun fetchTrees(
        cloneUrl: String,
        caps: GitCapabilities,
        window: Window,
        wants: List<String>,
    ): Boolean {
        var missing = wants
        repeat(MAX_TREE_ROUNDS) {
            if (missing.isEmpty()) return true
            missing.chunked(MAX_WANTS_PER_REQUEST).forEach { chunk ->
                val wanted = chunk.toHashSet()
                Packfile.parse(transport.fetchPack(cloneUrl, caps, chunk, deepen = null, filter = "tree:0")).values.forEach { obj ->
                    if (obj.type == GitObjectType.TREE && obj.oid in wanted) window.trees[obj.oid] = obj.data
                }
            }
            val left = missing.filter { !window.trees.containsKey(it) }
            if (left.size == missing.size) {
                Log.w(TAG) { "$cloneUrl left out ${left.size} wanted trees; fetching every tree of the window" }
                return false
            }
            missing = left
        }
        return missing.isEmpty()
    }

    /**
     * The window as it was fetched before trees could be asked for by id: every commit and tree
     * (`blob:none`; everything, from a server that can't filter). Only the trees along [path]
     * are kept, into [window] when the commits were already read.
     */
    private suspend fun fetchWholeWindow(
        cloneUrl: String,
        caps: GitCapabilities,
        tip: String,
        path: List<String>,
        depth: Int,
        window: Window?,
    ): Window {
        val objects = Packfile.parse(transport.fetchPack(cloneUrl, caps, listOf(tip), deepen = depth, filter = "blob:none"))
        val target = window ?: windowOf(objects).also { it.treesById = false }
        var level: Set<String> = target.commits.values.mapTo(HashSet()) { it.treeOid }
        for (index in path.indices) {
            val next = HashSet<String>()
            level.forEach { oid ->
                val tree = objects[oid]?.takeIf { it.type == GitObjectType.TREE } ?: return@forEach
                target.trees[oid] = tree.data
                if (index < path.lastIndex) GitObjectParser.findTreeEntry(tree.data, path[index])?.takeIf { it.isFolder }?.let { next.add(it.oid) }
            }
            level = next
        }
        return target
    }

    /** True when [window] holds every tree along [path] from each of its commits. */
    private fun holdsPath(
        window: Window,
        path: List<String>,
    ): Boolean {
        var level: Set<String> = window.commits.values.mapTo(HashSet()) { it.treeOid }
        for (index in path.indices) {
            val next = HashSet<String>()
            level.forEach { oid ->
                val tree = window.trees[oid] ?: return false
                if (index < path.lastIndex) GitObjectParser.findTreeEntry(tree, path[index])?.takeIf { it.isFolder }?.let { next.add(it.oid) }
            }
            level = next
        }
        return true
    }

    /** The entry at [path] from the tree [root], a file and not a folder, or null where [trees] lacks a step. */
    private fun pathEntry(
        trees: Map<String, ByteArray>,
        root: String,
        path: List<String>,
    ): GitTreeEntry? {
        var tree = root
        path.forEachIndexed { index, name ->
            val entry = trees[tree]?.let { GitObjectParser.findTreeEntry(it, name) } ?: return null
            if (index == path.lastIndex) return entry.takeIf { !entry.isFolder }
            if (!entry.isFolder) return null
            tree = entry.oid
        }
        return null
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
        private const val TAG = "GitFileHistory"

        /** History windows kept, each with the path trees read through it. */
        private const val MAX_WINDOWS = 2

        /** Rounds of asking again for trees a server left out, before falling back to every tree. */
        private const val MAX_TREE_ROUNDS = 3

        /** Wants per request when asking for trees by id, to keep request bodies small. */
        private const val MAX_WANTS_PER_REQUEST = 4000

        /** Generations of history read by default (git's `deepen`), not commits. */
        const val DEFAULT_DEPTH = 300
        const val DEFAULT_BLAME_VERSIONS = 60
    }
}
