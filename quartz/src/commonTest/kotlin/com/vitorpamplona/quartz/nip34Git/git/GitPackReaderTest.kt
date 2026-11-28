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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitPackReaderTest {
    private val small = GitObjectWriter.blob("small file\n".encodeToByteArray())
    private val large = GitObjectWriter.blob((0 until 2000).joinToString("") { "row $it\n" }.encodeToByteArray())
    private val tree =
        GitObjectWriter.tree(
            listOf(
                GitTreeEntry("100644", "small.txt", small.oid),
                GitTreeEntry("100644", "large.txt", large.oid),
            ),
        )
    private val commit =
        GitObjectWriter.commit(tree.oid, emptyList(), GitPerson("Ann", "ann@x.org", 1_700_000_000), GitPerson("Ann", "ann@x.org", 1_700_000_000), "first")
    private val pack = PackfileWriter.write(listOf(commit, tree, small, large))

    /** A source that hands out at most [step] bytes per read. */
    private fun trickle(
        bytes: ByteArray,
        step: Int,
    ): GitByteSource {
        val all = GitByteSource.of(bytes)
        return GitByteSource { dest, offset, length -> all.read(dest, offset, minOf(step, length)) }
    }

    private class Read {
        val objects = LinkedHashMap<String, GitObject>()
        val skipped = ArrayList<Pair<String?, GitPackSkip>>()
    }

    private fun read(
        source: GitByteSource,
        maxObjectBytes: Long = Long.MAX_VALUE,
    ): Read {
        val out = Read()
        GitPackReader.read(source, maxObjectBytes, onSkipped = { oid, reason -> out.skipped.add(oid to reason) }) { out.objects[it.oid] = it }
        return out
    }

    @Test
    fun readsWhatTheWholePackParserReads() {
        val whole = Packfile.parse(pack)
        for (step in listOf(1, 3, 4096, pack.size)) {
            val streamed = read(trickle(pack, step))
            assertEquals(whole.keys, streamed.objects.keys, "step $step")
            whole.forEach { (oid, obj) ->
                assertEquals(
                    obj.data.decodeToString(),
                    streamed.objects
                        .getValue(oid)
                        .data
                        .decodeToString(),
                )
            }
            assertTrue(streamed.skipped.isEmpty())
        }
        // Pack order is kept.
        assertEquals(listOf(commit.oid, tree.oid, small.oid, large.oid), read(GitByteSource.of(pack)).objects.keys.toList())
    }

    @Test
    fun passesOverObjectsOverTheLimitButNamesThem() {
        val streamed = read(trickle(pack, 7), maxObjectBytes = 1000)
        assertEquals(setOf(commit.oid, tree.oid, small.oid), streamed.objects.keys)
        assertEquals(listOf<Pair<String?, GitPackSkip>>(large.oid to GitPackSkip.TOO_LARGE), streamed.skipped)
    }

    @Test
    fun aTruncatedPackFails() {
        assertFails { read(GitByteSource.of(pack.copyOf(pack.size - 40))) }
        assertFailsWith<IllegalArgumentException> { read(GitByteSource.of("JUNKJUNKJUNK".encodeToByteArray())) }
    }

    @Test
    fun demuxesASidebandResponse() {
        val half = pack.size / 2
        val response =
            PktLines.build {
                write(PktLines.dataLine("acknowledgments\n"))
                write(PktLines.dataLine("ready\n"))
                write(PktLines.DELIM)
                write(PktLines.dataLine("packfile\n"))
                write(PktLines.dataLine(byteArrayOf(1) + pack.copyOfRange(0, half)))
                write(PktLines.dataLine(byteArrayOf(2) + "Counting objects\n".encodeToByteArray()))
                write(PktLines.dataLine(byteArrayOf(1) + pack.copyOfRange(half, pack.size)))
                write(PktLines.FLUSH)
            }
        val streamed = read(GitUploadPack.packSource(trickle(response, 5)))
        assertEquals(4, streamed.objects.size)
        // The same bytes, demuxed whole.
        assertEquals(pack.toList(), GitUploadPack.extractPack(PktLines.parse(response)).toList())
    }

    @Test
    fun aServerErrorInTheStreamIsAGitHttpException() {
        val refused = PktLines.build { write(PktLines.dataLine("ERR upload-pack: not our ref abc\n")) }
        val error = assertFailsWith<GitHttpException> { GitUploadPack.packSource(GitByteSource.of(refused)).read(ByteArray(8), 0, 8) }
        assertTrue(error.message!!.contains("not our ref"))

        val fatal =
            PktLines.build {
                write(PktLines.dataLine("packfile\n"))
                write(PktLines.dataLine(byteArrayOf(3) + "out of memory".encodeToByteArray()))
            }
        assertFailsWith<GitHttpException> { read(GitUploadPack.packSource(GitByteSource.of(fatal))) }
    }
}
