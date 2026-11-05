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

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Why [PackfileStream.read] passed over an object of a pack instead of handing it out. */
enum class GitPackSkip {
    /** The object is larger than the reader's limit: it was run through the inflater and dropped, never held. */
    TOO_LARGE,

    /** A delta whose base was skipped, dropped from memory, or never sent: it could not be rebuilt. */
    MISSING_BASE,
}

/**
 * A packfile reader that parses objects as the bytes arrive ([Packfile.parse] needs the whole pack
 * in one array) and hands each one to a callback, so memory follows the largest object kept, not
 * the pack.
 *
 * - An object whose size is over `maxObjectBytes` (as its entry header declares, or a delta's
 *   result size) is never held: its zlib stream is inflated through a small buffer only to find
 *   where the next entry starts, and hashed on the way, so its oid is still reported.
 * - Delta bases are kept, most recently used first, up to `retainBytes`. A delta whose base was
 *   skipped or dropped is reported as [GitPackSkip.MISSING_BASE], never rebuilt from the wrong
 *   bytes; asking for that object alone gets it whole, since a one-object pack holds no delta.
 * - A `REF_DELTA` that comes before its base waits for it.
 *
 * The pack's trailing checksum is not checked: each object's oid is computed from its own bytes.
 */
object PackfileStream {
    private const val OBJ_COMMIT = 1
    private const val OBJ_TREE = 2
    private const val OBJ_BLOB = 3
    private const val OBJ_TAG = 4
    private const val OBJ_OFS_DELTA = 6
    private const val OBJ_REF_DELTA = 7

    /** Delta bases kept by default while a pack is read. */
    const val DEFAULT_RETAIN_BYTES: Long = 16L * 1024 * 1024

    /**
     * Reads the pack in [input] to its last object, calling [onObject] for each one rebuilt (in
     * pack order, a waiting delta right after its base) and [onSkipped] for each one passed over,
     * with its oid when it could be computed. Throws on a malformed or truncated pack.
     */
    fun read(
        input: InputStream,
        maxObjectBytes: Long = Long.MAX_VALUE,
        retainBytes: Long = DEFAULT_RETAIN_BYTES,
        onSkipped: (oid: String?, reason: GitPackSkip) -> Unit = { _, _ -> },
        onObject: (GitObject) -> Unit,
    ) {
        Reader(Source(input), minOf(maxObjectBytes, Int.MAX_VALUE.toLong() - 64), retainBytes, onSkipped, onObject).run()
    }

    private class PendingDelta(
        val offset: Long,
        val delta: ByteArray,
    )

    private class Reader(
        val src: Source,
        val maxObjectBytes: Long,
        val retainBytes: Long,
        val onSkipped: (String?, GitPackSkip) -> Unit,
        val onObject: (GitObject) -> Unit,
    ) {
        val inflater = Inflater()

        // Bases kept for later deltas, least recently used first.
        val retained = LinkedHashMap<Long, GitObject>(64, 0.75f, true)
        var retainedBytes = 0L
        val offsetOf = HashMap<String, Long>()

        // Oids that could not be kept: deltas on them can't be rebuilt.
        val lostOids = HashSet<String>()
        val waiting = HashMap<String, MutableList<PendingDelta>>()

        fun run() {
            try {
                val header = src.readBytes(12)
                if (header[0] != 'P'.code.toByte() || header[1] != 'A'.code.toByte() || header[2] != 'C'.code.toByte() || header[3] != 'K'.code.toByte()) {
                    throw IllegalArgumentException("missing PACK signature")
                }
                val version = readUInt32(header, 4)
                require(version == 2L || version == 3L) { "unsupported packfile version $version" }
                val count = readUInt32(header, 8)
                for (i in 0 until count) entry()
                waiting.values.forEach { list -> list.forEach { _ -> onSkipped(null, GitPackSkip.MISSING_BASE) } }
            } finally {
                inflater.end()
            }
        }

        fun entry() {
            val start = src.offset
            var b = src.readByte()
            val type = (b ushr 4) and 0x07
            var size = (b and 0x0F).toLong()
            var shift = 4
            while (b and 0x80 != 0) {
                b = src.readByte()
                size = size or ((b and 0x7F).toLong() shl shift)
                shift += 7
                require(shift < 64) { "pack entry size overflows" }
            }
            when (type) {
                OBJ_COMMIT, OBJ_TREE, OBJ_BLOB, OBJ_TAG -> {
                    val kind = baseType(type)
                    if (size > maxObjectBytes) {
                        // Hashed on the way through, so the caller learns which object it was.
                        val digest = objectDigest(kind, size)
                        src.inflate(inflater, size) { chunk, n -> digest.update(chunk, 0, n) }
                        lose(digest.digest().toHex(), GitPackSkip.TOO_LARGE)
                    } else {
                        val data = src.inflateToArray(inflater, size.toInt())
                        deliver(start, GitObject(kind, data, oidOf(kind, data)))
                    }
                }
                OBJ_OFS_DELTA -> {
                    b = src.readByte()
                    var rel = (b and 0x7F).toLong()
                    while (b and 0x80 != 0) {
                        b = src.readByte()
                        rel = ((rel + 1) shl 7) or (b and 0x7F).toLong()
                    }
                    val baseOffset = start - rel
                    val delta = readDelta(size) ?: return
                    val base = retained[baseOffset]
                    if (base == null) {
                        lose(null, GitPackSkip.MISSING_BASE)
                    } else {
                        rebuild(start, base, delta)
                    }
                }
                OBJ_REF_DELTA -> {
                    val baseOid = src.readBytes(20).toHex()
                    val delta = readDelta(size) ?: return
                    val base = offsetOf[baseOid]?.let { retained[it] }
                    when {
                        base != null -> rebuild(start, base, delta)
                        // Seen and gone, or never rebuilt: the delta can't be applied.
                        baseOid in lostOids || baseOid in offsetOf -> lose(null, GitPackSkip.MISSING_BASE)
                        else -> waiting.getOrPut(baseOid) { ArrayList() }.add(PendingDelta(start, delta))
                    }
                }
                else -> throw IllegalArgumentException("invalid pack object type $type at offset $start")
            }
        }

        /** A delta's instructions, or null (reported) when even they are over the limit. */
        fun readDelta(size: Long): ByteArray? {
            if (size > maxObjectBytes) {
                src.inflate(inflater, size) { _, _ -> }
                lose(null, GitPackSkip.TOO_LARGE)
                return null
            }
            return src.inflateToArray(inflater, size.toInt())
        }

        fun rebuild(
            start: Long,
            base: GitObject,
            delta: ByteArray,
        ) {
            val targetSize = deltaTargetSize(delta)
            if (targetSize > maxObjectBytes) {
                val digest = objectDigest(base.type, targetSize)
                applyToDigest(base.data, delta, digest)
                lose(digest.digest().toHex(), GitPackSkip.TOO_LARGE)
                return
            }
            val data = GitDelta.apply(base.data, delta)
            deliver(start, GitObject(base.type, data, oidOf(base.type, data)))
        }

        fun deliver(
            start: Long,
            obj: GitObject,
        ) {
            onObject(obj)
            offsetOf[obj.oid] = start
            retained[start] = obj
            retainedBytes += obj.data.size
            // Keep the newest even when it alone is over the budget: its deltas usually follow it.
            val it = retained.entries.iterator()
            while (retainedBytes > retainBytes && retained.size > 1) {
                val eldest = it.next()
                if (eldest.key == start) continue
                retainedBytes -= eldest.value.data.size
                it.remove()
            }
            waiting.remove(obj.oid)?.forEach { rebuild(it.offset, obj, it.delta) }
        }

        fun lose(
            oid: String?,
            reason: GitPackSkip,
        ) {
            if (oid != null) lostOids.add(oid)
            onSkipped(oid, reason)
            if (oid != null) waiting.remove(oid)?.forEach { lose(null, GitPackSkip.MISSING_BASE) }
        }
    }

    /** Buffered pack bytes with their absolute offset, and zlib streams inflated in place. */
    private class Source(
        val input: InputStream,
    ) {
        val buf = ByteArray(64 * 1024)
        var pos = 0
        var limit = 0
        var before = 0L
        val chunk = ByteArray(64 * 1024)

        val offset: Long get() = before + pos

        fun fill(): Boolean {
            if (pos < limit) return true
            before += limit
            pos = 0
            limit = 0
            while (limit == 0) {
                val n = input.read(buf)
                if (n < 0) return false
                limit = n
            }
            return true
        }

        fun readByte(): Int {
            if (!fill()) throw EOFException("truncated packfile")
            return buf[pos++].toInt() and 0xFF
        }

        fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (!fill()) throw EOFException("truncated packfile")
                val take = minOf(n - got, limit - pos)
                buf.copyInto(out, got, pos, pos + take)
                pos += take
                got += take
            }
            return out
        }

        fun inflateToArray(
            inflater: Inflater,
            size: Int,
        ): ByteArray {
            val out = ByteArray(size)
            var at = 0
            inflate(inflater, size.toLong()) { data, n ->
                data.copyInto(out, at, 0, n)
                at += n
            }
            return out
        }

        /** Inflates one zlib stream of [expected] bytes, handing out each chunk, and leaves [pos] right after it. */
        inline fun inflate(
            inflater: Inflater,
            expected: Long,
            sink: (ByteArray, Int) -> Unit,
        ) {
            inflater.reset()
            var total = 0L
            try {
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        if (!fill()) throw EOFException("truncated packfile")
                        inflater.setInput(buf, pos, limit - pos)
                        pos = limit
                    }
                    val n = inflater.inflate(chunk)
                    if (n > 0) {
                        total += n
                        if (total > expected) throw IllegalStateException("pack object inflates past its declared $expected bytes")
                        sink(chunk, n)
                    } else if (inflater.needsDictionary()) {
                        throw IllegalStateException("pack object needs a zlib dictionary")
                    }
                }
            } catch (e: DataFormatException) {
                throw IllegalStateException("corrupt zlib stream in packfile", e)
            }
            // What the inflater was given beyond the stream's end starts the next entry.
            pos -= inflater.remaining
            if (total != expected) throw IllegalStateException("inflate short read: $total of $expected")
        }
    }

    /** The result size a delta declares: its second size header, after the base's. */
    private fun deltaTargetSize(delta: ByteArray): Long {
        var pos = 0

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(pos < delta.size) { "truncated delta header" }
                val b = delta[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                require(shift < 64) { "delta size overflows" }
            }
        }
        varint()
        return varint()
    }

    /** [GitDelta.apply] into [digest] without building the result, for a result too large to hold. */
    private fun applyToDigest(
        base: ByteArray,
        delta: ByteArray,
        digest: MessageDigest,
    ) {
        var pos = 0

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = delta[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
        require(varint() == base.size.toLong()) { "delta base size mismatch" }
        val target = varint()
        var written = 0L
        while (pos < delta.size) {
            val cmd = delta[pos++].toInt() and 0xFF
            if (cmd and 0x80 != 0) {
                var copyOffset = 0L
                var copySize = 0
                if (cmd and 0x01 != 0) copyOffset = copyOffset or (delta[pos++].toLong() and 0xFF)
                if (cmd and 0x02 != 0) copyOffset = copyOffset or ((delta[pos++].toLong() and 0xFF) shl 8)
                if (cmd and 0x04 != 0) copyOffset = copyOffset or ((delta[pos++].toLong() and 0xFF) shl 16)
                if (cmd and 0x08 != 0) copyOffset = copyOffset or ((delta[pos++].toLong() and 0xFF) shl 24)
                if (cmd and 0x10 != 0) copySize = copySize or (delta[pos++].toInt() and 0xFF)
                if (cmd and 0x20 != 0) copySize = copySize or ((delta[pos++].toInt() and 0xFF) shl 8)
                if (cmd and 0x40 != 0) copySize = copySize or ((delta[pos++].toInt() and 0xFF) shl 16)
                if (copySize == 0) copySize = 0x10000
                require(copyOffset + copySize <= base.size) { "delta copy past the end of its base" }
                digest.update(base, copyOffset.toInt(), copySize)
                written += copySize
            } else if (cmd != 0) {
                require(pos + cmd <= delta.size) { "delta insert past the end of the delta" }
                digest.update(delta, pos, cmd)
                pos += cmd
                written += cmd
            } else {
                throw IllegalArgumentException("invalid delta opcode 0x00")
            }
        }
        require(written == target) { "delta produced $written bytes, expected $target" }
    }

    private fun baseType(type: Int): GitObjectType =
        when (type) {
            OBJ_COMMIT -> GitObjectType.COMMIT
            OBJ_TREE -> GitObjectType.TREE
            OBJ_BLOB -> GitObjectType.BLOB
            else -> GitObjectType.TAG
        }

    private fun objectDigest(
        type: GitObjectType,
        size: Long,
    ): MessageDigest =
        MessageDigest.getInstance("SHA-1").apply {
            update("${type.header} $size".encodeToByteArray())
            update(0)
        }

    private fun oidOf(
        type: GitObjectType,
        data: ByteArray,
    ): String = objectDigest(type, data.size.toLong()).apply { update(data) }.digest().toHex()

    private fun readUInt32(
        data: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
