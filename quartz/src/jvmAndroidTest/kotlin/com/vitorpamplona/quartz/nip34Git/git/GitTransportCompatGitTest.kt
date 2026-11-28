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

import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The deprecated OkHttp and `InputStream` forms of the transport, over real git, against the common ones they wrap. */
@Suppress("DEPRECATION")
class GitTransportCompatGitTest {
    @Test
    fun oldAndNewTransportsFetchTheSamePack() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            File(work, "a.txt").writeText((0 until 300).joinToString("") { "line $it\n" })
            File(work, "b.txt").writeText("b\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "first")
            git.git(work, "push", "-q", "origin", "HEAD:main")
            val tip = git.git(work, "rev-parse", "HEAD")

            val old = GitSmartHttpTransport { git.client }
            val new = GitSmartHttpTransport(OkHttpGitTransport { git.client })
            runBlocking {
                val caps = new.fetchCapabilities(url)
                assertTrue(caps.supportsFetch)
                assertEquals(new.lsRefs(url, caps).map { it.oid to it.name }, old.lsRefs(url, old.fetchCapabilities(url)).map { it.oid to it.name })

                val whole = Packfile.parse(new.fetchPack(url, caps, listOf(tip), deepen = null, filter = null))
                assertEquals(whole.keys, Packfile.parse(old.fetchPack(url, caps, listOf(tip), deepen = null, filter = null)).keys)

                val viaStream = HashMap<String, GitObject>()
                old.fetchPackStream(url, caps, listOf(tip), deepen = null, filter = null) { input -> PackfileStream.read(input) { viaStream[it.oid] = it } }
                val viaSource = HashMap<String, GitObject>()
                new.fetchPackSource(url, caps, listOf(tip), deepen = null, filter = null) { source -> GitPackReader.read(source) { viaSource[it.oid] = it } }

                assertEquals(whole.keys, viaStream.keys)
                assertEquals(whole.keys, viaSource.keys)
                whole.forEach { (oid, obj) ->
                    assertContentEquals(obj.data, viaStream.getValue(oid).data)
                    assertContentEquals(obj.data, viaSource.getValue(oid).data)
                }
            }
        }

    @Test
    fun pktLineCodecReadsFromAnInputStream() {
        val bytes = PktLineCodec.build { write(PktLineCodec.dataLine("hello\n")) } + PktLineCodec.FLUSH
        val input = bytes.inputStream()
        assertEquals("hello", (PktLineCodec.read(input) as PktLine.Data).text())
        assertEquals(PktLine.Flush, PktLineCodec.read(input))
        assertEquals(null, PktLineCodec.read(input))
        // A frame cut short is still a java.io.EOFException, as it was.
        assertFailsWith<EOFException> { PktLineCodec.read(bytes.copyOf(6).inputStream()) }
    }
}
