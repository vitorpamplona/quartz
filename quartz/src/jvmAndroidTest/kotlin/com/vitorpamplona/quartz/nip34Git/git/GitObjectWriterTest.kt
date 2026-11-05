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

/** Object ids checked against `git write-tree` / `git commit-tree` (git 2.43). */
class GitObjectWriterTest {
    private val hello = GitObjectWriter.blob("hello\n".encodeToByteArray())
    private val b = GitObjectWriter.blob("b\n".encodeToByteArray())
    private val x = GitObjectWriter.blob("x".encodeToByteArray())

    @Test
    fun blobsHashAsGitHashObject() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", hello.oid)
        assertEquals("61780798228d17af2d34fce4cfbdf35556832472", b.oid)
    }

    @Test
    fun treesSortAndHashAsGitWriteTree() {
        val sub = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "b.txt", b.oid)))
        assertEquals("f8f7aefc2900a3d737cea9eee45729fd55761e1a", sub.oid)

        // Given out of order: git sorts "a-b" before "a.txt", and writes 40000 without a zero.
        val root =
            GitObjectWriter.tree(
                listOf(
                    GitTreeEntry("040000", "sub", sub.oid),
                    GitTreeEntry("100644", "a.txt", hello.oid),
                    GitTreeEntry("100644", "a-b", x.oid),
                ),
            )
        assertEquals("1a453296b32826fc35987f3f4fa081a5e0fe9ae3", root.oid)
        assertEquals(listOf("a-b", "a.txt", "sub"), GitObjectParser.parseTree(root.data).map { it.name })
    }

    @Test
    fun commitsHashAsGitCommitTree() {
        val tree = "1a453296b32826fc35987f3f4fa081a5e0fe9ae3"
        val first =
            GitObjectWriter.commit(
                treeOid = tree,
                parents = emptyList(),
                author = GitPerson("Ann", "ann@x.org", 1700000000, tzOffsetMinutes = 60),
                committer = GitPerson("Bob", "bob@x.org", 1700000100),
                message = "first",
            )
        assertEquals("252e08232db49c7279f7a9484d1919225efb81b5", first.oid)

        val second =
            GitObjectWriter.commit(
                treeOid = tree,
                parents = listOf(first.oid),
                author = GitPerson("Ann", "ann@x.org", 1700000200, tzOffsetMinutes = -150),
                committer = GitPerson("Ann", "ann@x.org", 1700000200, tzOffsetMinutes = -150),
                message = "Merge two\n",
            )
        assertEquals("9775d90632e0681780723ca4b18f5ca6b80841b2", second.oid)
    }

    @Test
    fun packsRoundTripThroughTheReader() {
        val objects = listOf(hello, b, x, GitObjectWriter.tree(listOf(GitTreeEntry("100644", "a.txt", hello.oid))))
        val parsed = Packfile.parse(PackfileWriter.write(objects))
        assertEquals(objects.map { it.oid }.toSet(), parsed.keys)
        objects.forEach { assertEquals(it.data.decodeToString(), parsed.getValue(it.oid).data.decodeToString()) }
    }

    @Test
    fun aLargeObjectsSizeHeaderSpansBytes() {
        val big = GitObjectWriter.blob(ByteArray(70_000) { (it % 251).toByte() })
        assertEquals(
            70_000,
            Packfile
                .parse(PackfileWriter.write(listOf(big)))
                .getValue(big.oid)
                .data.size,
        )
    }
}
