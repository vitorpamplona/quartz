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

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * Someone in a commit's `author` or `committer` line: `Name <email> 1712345678 +0100`.
 * [tzOffsetMinutes] is the offset from UTC the time was written in (0 for UTC).
 */
class GitPerson(
    val name: String,
    val email: String,
    val timeSec: Long,
    val tzOffsetMinutes: Int = 0,
) {
    fun format(): String {
        val sign = if (tzOffsetMinutes < 0) '-' else '+'
        val abs = abs(tzOffsetMinutes)
        val tz = sign + (abs / 60).toString().padStart(2, '0') + (abs % 60).toString().padStart(2, '0')
        return "$name <$email> $timeSec $tz"
    }
}

/**
 * Builds git objects: their canonical payloads and object ids, as `git hash-object` and
 * `git commit-tree` produce them, so what is pushed hashes to what the server expects.
 */
object GitObjectWriter {
    /** The id of an object of [type] with [data]: SHA-1 of `"<type> <size>\0" + data`, in hex. */
    fun oidOf(
        type: GitObjectType,
        data: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("${type.header} ${data.size}".encodeToByteArray())
        digest.update(0)
        digest.update(data)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun blob(data: ByteArray): GitObject = GitObject(GitObjectType.BLOB, data, oidOf(GitObjectType.BLOB, data))

    /**
     * A tree of [entries], in git's order: by name as bytes, a directory compared as if its name
     * ended with `/`. Modes are written as git writes them (`40000`, not `040000`).
     */
    fun tree(entries: List<GitTreeEntry>): GitObject {
        val sorted = entries.sortedWith { a, b -> compareBytes(sortKey(a), sortKey(b)) }
        val out = ByteArrayOutputStream()
        sorted.forEach { entry ->
            out.write(
                entry.mode
                    .trimStart('0')
                    .ifEmpty { "0" }
                    .encodeToByteArray(),
            )
            out.write(' '.code)
            out.write(entry.name.encodeToByteArray())
            out.write(0)
            out.write(hexToBytes(entry.oid))
        }
        val data = out.toByteArray()
        return GitObject(GitObjectType.TREE, data, oidOf(GitObjectType.TREE, data))
    }

    /**
     * A commit of [treeOid] on [parents] (first parent first, as git orders a merge), with
     * [message] ending in a newline as `git commit` leaves it.
     */
    fun commit(
        treeOid: String,
        parents: List<String>,
        author: GitPerson,
        committer: GitPerson,
        message: String,
    ): GitObject {
        val text =
            buildString {
                append("tree ").append(treeOid).append('\n')
                parents.forEach { append("parent ").append(it).append('\n') }
                append("author ").append(author.format()).append('\n')
                append("committer ").append(committer.format()).append('\n')
                append('\n')
                append(message)
                if (!message.endsWith('\n')) append('\n')
            }
        val data = text.encodeToByteArray()
        return GitObject(GitObjectType.COMMIT, data, oidOf(GitObjectType.COMMIT, data))
    }

    private fun sortKey(entry: GitTreeEntry): ByteArray = (if (entry.isDirectory) entry.name + "/" else entry.name).encodeToByteArray()

    private fun compareBytes(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    internal fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 40) { "not a SHA-1 object id: $hex" }
        return ByteArray(20) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Writes a version-2 packfile of whole objects (no deltas): what a push sends. Each object is
 * its type-and-size header and its zlib-deflated payload; the pack ends with the SHA-1 of
 * everything before it.
 */
object PackfileWriter {
    fun write(objects: Collection<GitObject>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PACK".encodeToByteArray())
        writeUInt32(out, 2)
        writeUInt32(out, objects.size.toLong())
        objects.forEach { obj ->
            writeHeader(out, typeCode(obj.type), obj.data.size.toLong())
            out.write(deflate(obj.data))
        }
        val body = out.toByteArray()
        val trailer = MessageDigest.getInstance("SHA-1").digest(body)
        return body + trailer
    }

    private fun typeCode(type: GitObjectType): Int =
        when (type) {
            GitObjectType.COMMIT -> 1
            GitObjectType.TREE -> 2
            GitObjectType.BLOB -> 3
            GitObjectType.TAG -> 4
        }

    /** `1TTTSSSS` then 7 bits per byte, low first, high bit set while more follow. */
    private fun writeHeader(
        out: ByteArrayOutputStream,
        type: Int,
        size: Long,
    ) {
        var rest = size ushr 4
        var byte = (type shl 4) or (size and 0x0f).toInt()
        while (rest != 0L) {
            out.write(byte or 0x80)
            byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
        }
        out.write(byte)
    }

    private fun writeUInt32(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        out.write(((value ushr 24) and 0xff).toInt())
        out.write(((value ushr 16) and 0xff).toInt())
        out.write(((value ushr 8) and 0xff).toInt())
        out.write((value and 0xff).toInt())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
