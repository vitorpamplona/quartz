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

/** Object ids as `git hash-object` computes them: SHA-1 of `"<type> <size>\0" + data`. */
class GitObjectIdTest {
    @Test
    fun emptyBlob() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitObjectWriter.oidOf(GitObjectType.BLOB, ByteArray(0)))
    }

    @Test
    fun helloBlob() {
        // printf 'hello\n' | git hash-object --stdin
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitObjectWriter.blob("hello\n".encodeToByteArray()).oid)
    }

    @Test
    fun emptyTree() {
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", GitObjectWriter.tree(emptyList()).oid)
    }

    @Test
    fun packedObjectsKeepTheirIds() {
        val blob = GitObjectWriter.blob("hello\n".encodeToByteArray())
        val tree = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "README", blob.oid)))
        val parsed = Packfile.parse(PackfileWriter.write(listOf(blob, tree)))
        assertEquals(setOf(blob.oid, tree.oid), parsed.keys)
        assertEquals("hello\n", parsed.getValue(blob.oid).data.decodeToString())
        assertEquals("README", GitObjectParser.parseTree(parsed.getValue(tree.oid).data).single().name)
    }
}
