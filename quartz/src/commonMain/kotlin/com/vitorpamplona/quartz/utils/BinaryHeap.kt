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

/**
 * A priority queue on a binary heap, the least element (by [comparator]) first: what
 * `java.util.PriorityQueue` is on the JVM, for common code. It sifts the way that class does, so
 * elements the comparator calls equal come out in the same order on every platform.
 */
internal class BinaryHeap<T>(
    private val comparator: Comparator<T>,
) {
    private var queue = arrayOfNulls<Any?>(16)

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size != 0

    fun add(element: T) {
        if (size >= queue.size) queue = queue.copyOf(queue.size * 2)
        siftUp(size, element)
        size++
    }

    /** Removes and returns the least element, or null when empty. */
    fun poll(): T? {
        if (size == 0) return null
        val result = elementAt(0)
        val n = --size
        val last = elementAt(n)
        queue[n] = null
        if (n > 0) siftDown(0, last, n)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun elementAt(index: Int): T = queue[index] as T

    private fun siftUp(
        start: Int,
        element: T,
    ) {
        var k = start
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val e = elementAt(parent)
            if (comparator.compare(element, e) >= 0) break
            queue[k] = e
            k = parent
        }
        queue[k] = element
    }

    private fun siftDown(
        start: Int,
        element: T,
        n: Int,
    ) {
        var k = start
        val half = n ushr 1
        while (k < half) {
            var child = (k shl 1) + 1
            var c = elementAt(child)
            val right = child + 1
            if (right < n && comparator.compare(c, elementAt(right)) > 0) {
                child = right
                c = elementAt(child)
            }
            if (comparator.compare(element, c) <= 0) break
            queue[k] = c
            k = child
        }
        queue[k] = element
    }
}
