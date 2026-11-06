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
package com.vitorpamplona.quartz.utils

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EXPERIMENT: the table builds exactly what the `when` parts build, for every kind and shape. */
class EventFactoryTableTest {
    private val id = "a".repeat(64)
    private val pk = "b".repeat(64)

    private val shapes: List<Pair<Array<Array<String>>, String>> =
        listOf(
            emptyArray<Array<String>>() to "",
            arrayOf(arrayOf("d", "x")) to "{}",
            arrayOf(arrayOf("h", "group"), arrayOf("e", id), arrayOf("d", id)) to "hello",
            arrayOf(arrayOf("t", "x"), arrayOf("p", pk), arrayOf("k", "1"), arrayOf("alt", "x")) to "[]",
            arrayOf(arrayOf("d", "drss"), arrayOf("title", "t"), arrayOf("url", "https://x")) to "{\"items\":[]}",
        )

    private fun same(
        e: Event?,
        t: Event?,
        what: String,
    ) {
        assertEquals(e?.let { it::class }, t?.let { it::class }, "$what: class")
        if (e != null && t != null) {
            assertEquals(e.id, t.id, "$what: id")
            assertEquals(e.kind, t.kind, "$what: kind")
        }
    }

    @Test
    fun everyKindAndShapeBuildsTheSameClass() {
        var typed = 0
        for (kind in 0..70_000) {
            for ((n, shape) in shapes.withIndex()) {
                val (tags, content) = shape
                val e = EventFactory.createWithWhen(id, pk, 1L, kind, tags, content, "c".repeat(128))
                val t = EventFactoryTable.create(id, pk, 1L, kind, tags, content, "c".repeat(128))
                same(e, t, "kind $kind shape $n")
                if (n == 0 && e != null) typed++
            }
        }
        assertEquals(typed, EventFactoryTable.size, "every compiled-in kind is in the table, and only those")
        // A blank id makes NIP-17's kinds hash one: both paths must agree on it.
        for (kind in listOf(14, 15)) {
            same(
                EventFactory.createWithWhen("", pk, 1L, kind, emptyArray(), "x", ""),
                EventFactoryTable.create("", pk, 1L, kind, emptyArray(), "x", ""),
                "blank id, kind $kind",
            )
        }
    }

    @Test
    fun everyCorpusEventBuildsTheSameClass() {
        val path = System.getenv("KITE_CORPUS") ?: return
        val lines = File(path).readLines().filter { it.isNotBlank() }
        for ((n, line) in lines.withIndex()) {
            val e = KiteReader.readEvent(line)
            same(
                EventFactory.createWithWhen(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig),
                EventFactoryTable.create(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig),
                "corpus line $n",
            )
        }
        assertTrue(lines.isNotEmpty())
    }
}
