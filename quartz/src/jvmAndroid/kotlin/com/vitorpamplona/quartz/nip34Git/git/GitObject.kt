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

/** The four git object kinds we decode from a packfile. */
enum class GitObjectType(
    val header: String,
) {
    COMMIT("commit"),
    TREE("tree"),
    BLOB("blob"),
    TAG("tag"),
}

/** A fully resolved git object: its kind, raw payload, and computed SHA-1 oid (hex). */
class GitObject(
    val type: GitObjectType,
    val data: ByteArray,
    val oid: String,
)

/**
 * One entry of a git tree object.
 *
 * @param mode octal file mode as stored, e.g. `40000` (directory), `100644`
 *   (regular file), `100755` (executable), `120000` (symlink), `160000`
 *   (gitlink / submodule).
 */
class GitTreeEntry(
    val mode: String,
    val name: String,
    val oid: String,
) {
    val isDirectory: Boolean get() = mode == "40000"
    val isSubmodule: Boolean get() = mode == "160000"
    val isSymlink: Boolean get() = mode == "120000"
    val isExecutable: Boolean get() = mode == "100755"

    /** Browsable as a folder (a tree). Submodules point at a commit and are not walked. */
    val isFolder: Boolean get() = isDirectory
}

/** A parsed git commit object. */
class GitCommit(
    val oid: String,
    val treeOid: String,
    val parents: List<String>,
    val authorName: String,
    val authorEmail: String,
    val authorTimeSec: Long,
    val message: String,
) {
    val shortOid: String get() = oid.take(7)
    val summary: String get() = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
}

object GitObjectParser {
    /**
     * Parses a tree object payload into its entries. The wire format is a
     * concatenation of `<octal-mode> <name>\u0000<20-byte-oid>` records, sorted
     * by git's own ordering (which we preserve, then re-sort for display in the UI).
     */
    fun parseTree(data: ByteArray): List<GitTreeEntry> {
        val entries = ArrayList<GitTreeEntry>()
        var i = 0
        while (i < data.size) {
            val space = indexOf(data, ' '.code.toByte(), i)
            require(space >= 0) { "malformed tree entry: missing mode separator" }
            val mode = data.decodeToString(i, space)

            val nul = indexOf(data, 0, space + 1)
            require(nul >= 0) { "malformed tree entry: missing name terminator" }
            val name = data.decodeToString(space + 1, nul)

            val oidStart = nul + 1
            require(oidStart + 20 <= data.size) { "malformed tree entry: truncated oid" }
            val oid = toHex(data, oidStart, 20)

            entries.add(GitTreeEntry(mode, name, oid))
            i = oidStart + 20
        }
        return entries
    }

    /**
     * The entry called [name] in a tree object payload, or null, read without building the
     * other entries: walking one path through many trees needs one entry of each.
     */
    fun findTreeEntry(
        data: ByteArray,
        name: String,
    ): GitTreeEntry? {
        val wanted = name.encodeToByteArray()
        var i = 0
        while (i < data.size) {
            val space = indexOf(data, ' '.code.toByte(), i)
            require(space >= 0) { "malformed tree entry: missing mode separator" }
            val nul = indexOf(data, 0, space + 1)
            require(nul >= 0) { "malformed tree entry: missing name terminator" }
            val oidStart = nul + 1
            require(oidStart + 20 <= data.size) { "malformed tree entry: truncated oid" }
            if (nul - space - 1 == wanted.size && regionEquals(data, space + 1, wanted)) {
                return GitTreeEntry(data.decodeToString(i, space), name, toHex(data, oidStart, 20))
            }
            i = oidStart + 20
        }
        return null
    }

    private fun regionEquals(
        data: ByteArray,
        offset: Int,
        other: ByteArray,
    ): Boolean {
        for (k in other.indices) if (data[offset + k] != other[k]) return false
        return true
    }

    /**
     * Parses a commit object payload into a [GitCommit]. Header lines run until
     * the first blank line; multi-line headers (e.g. `gpgsig`) use leading-space
     * continuations which we skip.
     */
    fun parseCommit(
        oid: String,
        data: ByteArray,
    ): GitCommit {
        val text = data.decodeToString()
        val sep = text.indexOf("\n\n")
        val header = if (sep >= 0) text.substring(0, sep) else text
        val message = if (sep >= 0) text.substring(sep + 2) else ""

        var tree = ""
        val parents = ArrayList<String>()
        var authorName = ""
        var authorEmail = ""
        var authorTime = 0L
        for (line in header.lineSequence()) {
            when {
                line.startsWith("tree ") -> tree = line.substring(5).trim()
                line.startsWith("parent ") -> parents.add(line.substring(7).trim())
                line.startsWith("author ") -> {
                    val ident = parseIdentity(line.substring(7))
                    authorName = ident.name
                    authorEmail = ident.email
                    authorTime = ident.timeSec
                }
            }
        }
        return GitCommit(oid, tree, parents, authorName, authorEmail, authorTime, message)
    }

    private class Identity(
        val name: String,
        val email: String,
        val timeSec: Long,
    )

    /** Parses `Name <email> 1700000000 +0000`. */
    private fun parseIdentity(value: String): Identity {
        val lt = value.indexOf('<')
        val gt = value.indexOf('>', startIndex = if (lt >= 0) lt else 0)
        val name = if (lt > 0) value.substring(0, lt).trim() else value.trim()
        val email = if (lt >= 0 && gt > lt) value.substring(lt + 1, gt) else ""
        val timeSec =
            if (gt >= 0) {
                value
                    .substring(gt + 1)
                    .trim()
                    .substringBefore(' ')
                    .toLongOrNull() ?: 0L
            } else {
                0L
            }
        return Identity(name, email, timeSec)
    }

    /** Extracts the root tree oid referenced by a commit object payload. */
    fun parseCommitTree(data: ByteArray): String? {
        // The `tree <oid>` header is always the first line of a commit object.
        val text = data.decodeToString()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) break // header section ended
            if (line.startsWith("tree ")) return line.substring(5).trim()
        }
        return null
    }

    private fun indexOf(
        data: ByteArray,
        target: Byte,
        from: Int,
    ): Int {
        var i = from
        while (i < data.size) {
            if (data[i] == target) return i
            i++
        }
        return -1
    }

    private fun toHex(
        data: ByteArray,
        offset: Int,
        len: Int,
    ): String {
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val v = data[offset + i].toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
