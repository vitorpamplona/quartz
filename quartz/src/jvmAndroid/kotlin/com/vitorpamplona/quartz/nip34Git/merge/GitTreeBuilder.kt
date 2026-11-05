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
import com.vitorpamplona.quartz.nip34Git.git.GitObjectWriter
import com.vitorpamplona.quartz.nip34Git.git.GitTreeEntry
import com.vitorpamplona.quartz.nip34Git.patch.LineMerge

/**
 * Builds trees for a merge over a [GitObjectSource], keeping every object it creates in
 * [created] (what a push must send) and every path it could not merge in [conflicts].
 */
class GitTreeBuilder(
    private val source: GitObjectSource,
) {
    val created = LinkedHashMap<String, GitObject>()
    val conflicts = ArrayList<String>()

    fun keep(obj: GitObject): GitObject {
        created[obj.oid] = obj
        source.add(obj)
        return obj
    }

    /**
     * Three-way merge of trees, as git's resolve does per path: one side's change wins over the
     * other's silence, equal changes agree, a file both changed is merged line by line
     * ([LineMerge.merge3]), and anything else (both added different files, a delete against an
     * edit, a file against a folder, a binary both changed) is a conflict. Returns the merged
     * tree's id; check [conflicts] before trusting it.
     */
    suspend fun mergeTrees(
        base: String?,
        ours: String,
        theirs: String,
        path: String = "",
    ): String {
        if (ours == theirs || base == theirs) return ours
        if (base == ours) return theirs

        val b = base?.let { source.tree(it) }.orEmpty().associateBy { it.name }
        val o = source.tree(ours).associateBy { it.name }
        val t = source.tree(theirs).associateBy { it.name }

        val merged = ArrayList<GitTreeEntry>()
        for (name in (b.keys + o.keys + t.keys)) {
            val be = b[name]
            val oe = o[name]
            val te = t[name]
            val full = if (path.isEmpty()) name else "$path/$name"
            when {
                same(oe, te) -> oe?.let(merged::add)
                same(be, oe) -> te?.let(merged::add)
                same(be, te) -> oe?.let(merged::add)
                oe != null && te != null && oe.isDirectory && te.isDirectory && (be == null || be.isDirectory) ->
                    merged.add(GitTreeEntry("40000", name, mergeTrees(be?.oid, oe.oid, te.oid, full)))
                oe != null && te != null && be != null && isFile(oe) && isFile(te) && isFile(be) -> {
                    val mergedBlob = mergeBlobs(be.oid, oe.oid, te.oid)
                    // A mode only one side changed wins; both changing it differently is a conflict.
                    val mode =
                        when {
                            oe.mode == te.mode -> oe.mode
                            oe.mode == be.mode -> te.mode
                            te.mode == be.mode -> oe.mode
                            else -> null
                        }
                    if (mergedBlob == null || mode == null) {
                        conflicts.add(full)
                        merged.add(oe)
                    } else {
                        merged.add(GitTreeEntry(mode, name, mergedBlob))
                    }
                }
                else -> {
                    conflicts.add(full)
                    oe?.let(merged::add)
                }
            }
        }
        return keep(GitObjectWriter.tree(merged)).oid
    }

    /**
     * Rewrites [treeOid] (null: an empty tree) with [changes]: each path set to an entry, or
     * removed with null. A folder left empty disappears, as git keeps no empty trees.
     */
    suspend fun rewrite(
        treeOid: String?,
        changes: Map<List<String>, GitTreeEntry?>,
    ): String? {
        val entries =
            treeOid
                ?.let { source.tree(it) }
                .orEmpty()
                .associateBy { it.name }
                .toMutableMap()
        changes.entries.groupBy { it.key.first() }.forEach { (name, group) ->
            val leaf = group.firstOrNull { it.key.size == 1 }
            if (leaf != null) {
                val entry = leaf.value
                if (entry == null) entries.remove(name) else entries[name] = GitTreeEntry(entry.mode, name, entry.oid)
            } else {
                val sub = entries[name]?.takeIf { it.isDirectory }?.oid
                val rewritten = rewrite(sub, group.associate { it.key.drop(1) to it.value })
                if (rewritten == null) entries.remove(name) else entries[name] = GitTreeEntry("40000", name, rewritten)
            }
        }
        if (entries.isEmpty()) return null
        return keep(GitObjectWriter.tree(entries.values.toList())).oid
    }

    /** The entry at [path] under [treeOid], or null. */
    suspend fun entryAt(
        treeOid: String,
        path: List<String>,
    ): GitTreeEntry? {
        var tree = treeOid
        path.forEachIndexed { index, name ->
            val entry = source.tree(tree).firstOrNull { it.name == name } ?: return null
            if (index == path.lastIndex) return entry
            if (!entry.isDirectory) return null
            tree = entry.oid
        }
        return null
    }

    private suspend fun mergeBlobs(
        base: String,
        ours: String,
        theirs: String,
    ): String? {
        source.prefetch(listOf(base, ours, theirs))
        val b = source.blob(base)
        val o = source.blob(ours)
        val t = source.blob(theirs)
        // Binary content has no lines to merge.
        if (listOf(b, o, t).any { it.contains(0) }) return null
        // ISO-8859-1 maps each byte to one char and back, so a file in any encoding merges byte
        // for byte; UTF-8 decoding would turn every invalid byte into U+FFFD.
        return when (val result = LineMerge.merge3(b.toString(Charsets.ISO_8859_1), o.toString(Charsets.ISO_8859_1), t.toString(Charsets.ISO_8859_1))) {
            is LineMerge.Result.Merged -> keep(GitObjectWriter.blob(result.text.toByteArray(Charsets.ISO_8859_1))).oid
            LineMerge.Result.Conflict -> null
        }
    }

    private fun same(
        a: GitTreeEntry?,
        b: GitTreeEntry?,
    ): Boolean = a?.oid == b?.oid && a?.mode?.trimStart('0') == b?.mode?.trimStart('0')

    private fun isFile(entry: GitTreeEntry): Boolean = !entry.isDirectory && !entry.isSubmodule
}
