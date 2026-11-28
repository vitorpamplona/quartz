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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryHeapTest {
    @Test
    fun pollsInComparatorOrder() {
        val random = Random(42)
        val values = List(1000) { random.nextInt(0, 200) }
        val heap = BinaryHeap<Int>(naturalOrder())
        values.forEach { heap.add(it) }
        assertEquals(values.size, heap.size)
        val out = ArrayList<Int>()
        while (heap.isNotEmpty()) out.add(heap.poll()!!)
        assertEquals(values.sorted(), out)
        assertTrue(heap.isEmpty())
        assertNull(heap.poll())
    }

    @Test
    fun interleavedAddsAndPolls() {
        val heap = BinaryHeap<Int>(compareByDescending { it })
        heap.add(3)
        heap.add(9)
        assertEquals(9, heap.poll())
        heap.add(1)
        heap.add(7)
        assertEquals(7, heap.poll())
        assertEquals(3, heap.poll())
        assertEquals(1, heap.poll())
        assertNull(heap.poll())
    }

    @Test
    fun tiesComeOutAsJavaPriorityQueueOrdersThem() {
        // Pairs compared by the first value only. This order is java.util.PriorityQueue's for the
        // same adds, so a history walk orders same-second commits alike on every platform.
        val heap = BinaryHeap<Pair<Int, String>>(compareByDescending { it.first })
        listOf(1 to "a", 1 to "b", 1 to "c", 2 to "d", 1 to "e", 2 to "f").forEach { heap.add(it) }
        val out = ArrayList<String>()
        while (heap.isNotEmpty()) out.add(heap.poll()!!.second)
        assertEquals(listOf("d", "f", "e", "b", "c", "a"), out)
    }
}
