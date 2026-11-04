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

import com.vitorpamplona.quartz.nip34Git.git.GitCapabilities
import com.vitorpamplona.quartz.nip34Git.git.GitCommit
import com.vitorpamplona.quartz.nip34Git.git.GitHttpException
import com.vitorpamplona.quartz.nip34Git.git.GitObject
import com.vitorpamplona.quartz.nip34Git.git.GitObjectParser
import com.vitorpamplona.quartz.nip34Git.git.GitObjectType
import com.vitorpamplona.quartz.nip34Git.git.GitSmartHttpTransport
import com.vitorpamplona.quartz.nip34Git.git.GitTreeEntry
import com.vitorpamplona.quartz.nip34Git.git.Packfile
import java.util.PriorityQueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * The objects a merge reads, fetched on demand from the repository's git servers ([cloneUrls],
 * tried in order) and kept: commit history without trees, trees without blobs, then only the
 * blobs a merge has to open. GRASP servers advertise `allow-reachable-sha1-in-want`, so any
 * object can be asked for by id.
 */
class GitObjectSource(
    private val transport: GitSmartHttpTransport,
    private val cloneUrls: List<String>,
) {
    private val objects = HashMap<String, GitObject>()
    private val commits = HashMap<String, GitCommit>()
    private val capabilities = HashMap<String, GitCapabilities>()

    fun known(oid: String): GitObject? = objects[oid]

    fun add(obj: GitObject) {
        objects[obj.oid] = obj
    }

    /** Fetches [wants] (and what they reach, minus [haves]) from the first server that answers. */
    suspend fun fetch(
        wants: List<String>,
        haves: List<String> = emptyList(),
        filter: String? = null,
    ): Map<String, GitObject> {
        var last: Exception? = null
        for (url in cloneUrls) {
            try {
                val caps = capabilities.getOrPut(url) { transport.fetchCapabilities(url) }
                val pack = transport.fetchPack(url, caps, wants, haves, deepen = null, filter = filter)
                val fetched = Packfile.parse(pack)
                objects.putAll(fetched)
                if (wants.all { it in objects }) return fetched
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw GitHttpException("no server holds ${wants.firstOrNull()}" + (last?.message?.let { ": $it" } ?: ""))
    }

    /** The history of [tips] as commits only (`tree:0`), parsed. */
    suspend fun loadHistory(tips: List<String>) {
        val missing = tips.filter { it !in commits && it !in objects }
        if (missing.isNotEmpty()) fetch(missing, filter = "tree:0")
        objects.values.forEach { obj ->
            if (obj.type == GitObjectType.COMMIT && obj.oid !in commits) commits[obj.oid] = GitObjectParser.parseCommit(obj.oid, obj.data)
        }
    }

    suspend fun commit(oid: String): GitCommit {
        commits[oid]?.let { return it }
        loadHistory(listOf(oid))
        return commits[oid] ?: throw GitHttpException("commit $oid not found")
    }

    /** A tree's entries; a missing tree comes down with its subtrees (`blob:none`). */
    suspend fun tree(oid: String): List<GitTreeEntry> {
        val obj = objects[oid] ?: fetch(listOf(oid), filter = "blob:none")[oid] ?: objects[oid] ?: throw GitHttpException("tree $oid not found")
        require(obj.type == GitObjectType.TREE) { "$oid is not a tree" }
        return GitObjectParser.parseTree(obj.data)
    }

    suspend fun blob(oid: String): ByteArray {
        val obj = objects[oid] ?: fetch(listOf(oid))[oid] ?: objects[oid] ?: throw GitHttpException("blob $oid not found")
        return obj.data
    }

    /** Every ancestor of [tip] in the loaded history, [tip] included. */
    fun ancestors(tip: String): Set<String> {
        val seen = HashSet<String>()
        val stack = ArrayDeque(listOf(tip))
        while (stack.isNotEmpty()) {
            val oid = stack.removeLast()
            if (!seen.add(oid)) continue
            commits[oid]?.parents?.forEach { stack.addLast(it) }
        }
        return seen
    }

    /**
     * The merge base of [a] and [b]: the newest commit of [b]'s history that is also in [a]'s, as
     * `git merge-base` finds it for the usual single-base case. Null when they share none.
     */
    suspend fun mergeBase(
        a: String,
        b: String,
    ): String? {
        loadHistory(listOf(a, b))
        val inA = ancestors(a)
        val seen = HashSet<String>()
        val frontier = PriorityQueue<GitCommit>(compareByDescending { it.authorTimeSec })
        commits[b]?.let { frontier.add(it) }
        while (frontier.isNotEmpty()) {
            val commit = frontier.poll()
            if (!seen.add(commit.oid)) continue
            if (commit.oid in inA) return commit.oid
            commit.parents.forEach { parent -> commits[parent]?.let { frontier.add(it) } }
        }
        return null
    }
}
