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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [PackfileStream] and the streaming sideband demux, against real `git pack-objects` output and hand-built packs. */
class PackfileStreamTest {
    private class Read(
        val objects: MutableMap<String, GitObject> = LinkedHashMap(),
        val skipped: MutableList<Pair<String?, GitPackSkip>> = ArrayList(),
    )

    private fun read(
        pack: ByteArray,
        maxObjectBytes: Long = Long.MAX_VALUE,
        retainBytes: Long = PackfileStream.DEFAULT_RETAIN_BYTES,
        input: InputStream = OneByteAtATime(pack),
    ): Read {
        val out = Read()
        PackfileStream.read(input, maxObjectBytes, retainBytes, onSkipped = { oid, reason -> out.skipped.add(oid to reason) }) { out.objects[it.oid] = it }
        return out
    }

    /** Hands out the pack in small, uneven reads, as a network stream does. */
    private class OneByteAtATime(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        private var turn = 0

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, minOf(len, if (turn++ % 2 == 0) 1 else 997))
    }

    private fun run(
        dir: File,
        input: ByteArray?,
        vararg args: String,
    ): ByteArray {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).start()
        if (input != null) process.outputStream.use { it.write(input) } else process.outputStream.close()
        val out = process.inputStream.readBytes()
        process.waitFor(60, TimeUnit.SECONDS)
        return out
    }

    /** A repository whose `big.txt` changed 30 times, so a pack of it holds delta chains. */
    private fun repoWithDeltas(git: GitHttpBackendFixture): File {
        val (bare, _) = git.bare("deltas")
        val work = git.work(bare, "work")
        val common = (0 until 400).joinToString("") { "common line $it, padded to make the file larger than a few bytes\n" }
        repeat(30) { i ->
            File(work, "big.txt").writeText(common + (0..i).joinToString("") { "added line $it\n" })
            File(work, "small-$i.txt").writeText("small $i\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "v$i")
        }
        return work
    }

    private fun packOf(
        work: File,
        offsetDeltas: Boolean,
    ): ByteArray {
        val args = mutableListOf("pack-objects", "--stdout", "--revs", "--window=50", "--depth=50")
        if (offsetDeltas) args.add("--delta-base-offset")
        return run(work, "HEAD\n".encodeToByteArray(), *args.toTypedArray())
    }

    /** The longest delta chain git reports for [pack], so a test knows it holds deltas. */
    private fun deltaChains(
        work: File,
        pack: ByteArray,
    ): Boolean {
        val file = File(work, "check.pack").also { it.writeBytes(pack) }
        run(work, null, "index-pack", file.path)
        val verify = run(work, null, "verify-pack", "-v", File(work, "check.idx").path).decodeToString()
        return verify.contains("chain length = 1")
    }

    @Test
    fun readsOffsetAndRefDeltasLikeTheArrayParser() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val work = repoWithDeltas(git)
            for (offsetDeltas in listOf(true, false)) {
                val pack = packOf(work, offsetDeltas)
                assertTrue(deltaChains(work, pack), "git made deltas (offset=$offsetDeltas)")
                val expected = Packfile.parse(pack)
                val streamed = read(pack)
                assertTrue(streamed.skipped.isEmpty())
                assertEquals(expected.keys, streamed.objects.keys)
                expected.forEach { (oid, obj) ->
                    assertEquals(obj.type, streamed.objects[oid]!!.type)
                    assertContentEquals(obj.data, streamed.objects[oid]!!.data)
                }
            }
        }

    @Test
    fun skipsLargeObjectsAndTheDeltasBuiltOnThem() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val work = repoWithDeltas(git)
            for (offsetDeltas in listOf(true, false)) {
                val pack = packOf(work, offsetDeltas)
                val expected = Packfile.parse(pack)
                // Newer versions of big.txt are over the limit, older ones under: git keeps the newest whole.
                val limit = 26_100L
                assertTrue(expected.values.any { it.data.size > limit } && expected.values.any { it.data.size in 20_000..limit })
                val streamed = read(pack, maxObjectBytes = limit)

                // What came out is exactly right; nothing over the limit is held.
                streamed.objects.forEach { (oid, obj) ->
                    assertContentEquals(expected[oid]!!.data, obj.data)
                    assertTrue(obj.data.size <= limit)
                }
                // Every object is either handed out or reported, and a large one by its own oid.
                assertEquals(expected.size, streamed.objects.size + streamed.skipped.size)
                val large =
                    expected.values
                        .filter { it.data.size > limit }
                        .map { it.oid }
                        .toSet()
                val tooLarge =
                    streamed.skipped
                        .filter { it.second == GitPackSkip.TOO_LARGE }
                        .mapNotNull { it.first }
                        .toSet()
                assertTrue(tooLarge.isNotEmpty())
                assertTrue(large.containsAll(tooLarge))
                assertTrue(streamed.skipped.any { it.second == GitPackSkip.MISSING_BASE }, "a smaller version deltified on a skipped one")
                expected.values.filter { it.data.size <= limit && it.type != GitObjectType.BLOB }.forEach { assertTrue(it.oid in streamed.objects) }
            }
        }

    @Test
    fun aDroppedBaseFailsItsDeltasCleanly() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val work = repoWithDeltas(git)
            val pack = packOf(work, offsetDeltas = true)
            val expected = Packfile.parse(pack)
            // Keep only the latest object as a base.
            val streamed = read(pack, retainBytes = 1)
            assertTrue(streamed.skipped.isNotEmpty())
            assertTrue(streamed.skipped.all { it.second == GitPackSkip.MISSING_BASE && it.first == null })
            assertEquals(expected.size, streamed.objects.size + streamed.skipped.size)
            streamed.objects.forEach { (oid, obj) -> assertContentEquals(expected[oid]!!.data, obj.data) }
        }

    // Hand-built packs, for orders git doesn't write.

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun entry(
        type: Int,
        payload: ByteArray,
        prefix: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var size = payload.size.toLong()
        var b = (type shl 4) or (size and 0x0F).toInt()
        size = size ushr 4
        while (size > 0) {
            out.write(b or 0x80)
            b = (size and 0x7F).toInt()
            size = size ushr 7
        }
        out.write(b)
        out.write(prefix)
        out.write(deflate(payload))
        return out.toByteArray()
    }

    private fun pack(vararg entries: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PACK".encodeToByteArray())
        out.write(byteArrayOf(0, 0, 0, 2))
        val n = entries.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        entries.forEach { out.write(it) }
        out.write(ByteArray(20))
        return out.toByteArray()
    }

    /** A delta that copies all of [base] and appends [tail]. */
    private fun appendDelta(
        base: ByteArray,
        tail: String,
    ): ByteArray {
        require(base.size in 1..127 && tail.length in 1..127)
        return byteArrayOf(base.size.toByte(), (base.size + tail.length).toByte(), 0x90.toByte(), base.size.toByte(), tail.length.toByte()) + tail.encodeToByteArray()
    }

    private fun hexBytes(oid: String) = ByteArray(20) { oid.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun aRefDeltaBeforeItsBaseWaitsForIt() {
        val base = GitObjectWriter.blob("the base text\n".encodeToByteArray())
        val target = GitObjectWriter.blob("the base text\nmore\n".encodeToByteArray())
        val orphanBase = GitObjectWriter.blob("never sent\n".encodeToByteArray())
        val pack =
            pack(
                entry(7, appendDelta(base.data, "more\n"), hexBytes(base.oid)),
                entry(7, appendDelta(orphanBase.data, "x\n"), hexBytes(orphanBase.oid)),
                entry(3, base.data),
            )
        val streamed = read(pack)
        assertEquals(listOf(base.oid, target.oid), streamed.objects.keys.toList())
        assertContentEquals(target.data, streamed.objects[target.oid]!!.data)
        assertEquals(listOf<Pair<String?, GitPackSkip>>(null to GitPackSkip.MISSING_BASE), streamed.skipped)
    }

    @Test
    fun aRefDeltaOnASkippedBaseIsNotRebuilt() {
        val base = GitObjectWriter.blob("a base over the limit\n".encodeToByteArray())
        val small = GitObjectWriter.blob("ok\n".encodeToByteArray())
        val pack =
            pack(
                entry(3, base.data),
                entry(7, appendDelta(base.data, "y\n"), hexBytes(base.oid)),
                entry(3, small.data),
            )
        val streamed = read(pack, maxObjectBytes = 10)
        assertEquals(listOf(small.oid), streamed.objects.keys.toList())
        assertEquals(listOf(base.oid to GitPackSkip.TOO_LARGE, null to GitPackSkip.MISSING_BASE), streamed.skipped)
    }

    @Test
    fun aDeltaTooLargeForTheLimitIsReportedByItsOid() {
        val base = GitObjectWriter.blob("0123456789".encodeToByteArray())
        val target = GitObjectWriter.blob("0123456789 and a longer tail".encodeToByteArray())
        val pack = pack(entry(3, base.data), entry(7, appendDelta(base.data, " and a longer tail"), hexBytes(base.oid)))
        // The instructions fit the limit; what they build does not.
        val streamed = read(pack, maxObjectBytes = 25)
        assertEquals(listOf(base.oid), streamed.objects.keys.toList())
        assertEquals(listOf<Pair<String?, GitPackSkip>>(target.oid to GitPackSkip.TOO_LARGE), streamed.skipped)
    }

    @Test
    fun aTruncatedPackFails() {
        val blob = GitObjectWriter.blob("some text that compresses\n".encodeToByteArray())
        val pack = pack(entry(3, blob.data), entry(3, blob.data))
        assertFailsWith<Exception> { read(pack.copyOf(pack.size - 30)) }
    }

    @Test
    fun demuxesSidebandAsItArrives() {
        val blob = GitObjectWriter.blob("streamed\n".encodeToByteArray())
        val pack = PackfileWriter.write(listOf(blob))
        val half = pack.size / 2
        val response =
            PktLineCodec.build {
                write(PktLineCodec.dataLine("acknowledgments\n"))
                write(PktLineCodec.dataLine("ready\n"))
                write(PktLineCodec.DELIM)
                write(PktLineCodec.dataLine("packfile\n"))
                write(PktLineCodec.dataLine(byteArrayOf(1) + pack.copyOfRange(0, half)))
                write(PktLineCodec.dataLine(byteArrayOf(2) + "Counting objects\n".encodeToByteArray()))
                write(PktLineCodec.dataLine(byteArrayOf(1) + pack.copyOfRange(half, pack.size)))
                write(PktLineCodec.FLUSH)
            }
        val stream = GitUploadPackV2.packStream(OneByteAtATime(response))
        assertContentEquals(pack, stream.readBytes())
        val objects = read(pack, input = GitUploadPackV2.packStream(ByteArrayInputStream(response))).objects
        assertEquals(listOf(blob.oid), objects.keys.toList())
    }

    @Test
    fun serverErrorsSurfaceFromTheStream() {
        val refused = PktLineCodec.build { write(PktLineCodec.dataLine("ERR upload-pack: not our ref abc\n")) }
        val error = assertFailsWith<GitHttpException> { GitUploadPackV2.packStream(ByteArrayInputStream(refused)).read() }
        assertTrue(error.message!!.contains("not our ref"))

        val fatal =
            PktLineCodec.build {
                write(PktLineCodec.dataLine("packfile\n"))
                write(PktLineCodec.dataLine(byteArrayOf(3) + "out of memory".encodeToByteArray()))
            }
        assertFailsWith<GitHttpException> { GitUploadPackV2.packStream(ByteArrayInputStream(fatal)).readBytes() }
        assertFailsWith<GitHttpException> { GitUploadPackV2.extractPack(PktLineCodec.parse(refused)) }
    }
}
