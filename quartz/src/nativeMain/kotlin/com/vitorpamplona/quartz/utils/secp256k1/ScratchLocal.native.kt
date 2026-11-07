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
package com.vitorpamplona.quartz.utils.secp256k1

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ThreadLocal

/**
 * Kotlin/Native: one scratch value per thread, like java.lang.ThreadLocal on the JVM.
 *
 * Kotlin/Native code runs on many threads (Dispatchers.Default is a thread pool), so a single
 * shared scratch buffer lets two concurrent signs, verifies or ECDHs overwrite each other's
 * intermediate field elements: wrong results, and secret-dependent state crossing threads.
 *
 * Each ScratchLocal takes a slot number when it is created (a handful, all in shared
 * objects). [slots] is a `@ThreadLocal` top-level property, so every thread sees its own
 * array; `get()` is one thread-local load, a bounds check and an array read. The value is
 * created on a thread's first `get()`.
 */
@OptIn(ExperimentalAtomicApi::class)
private val nextSlot = AtomicInt(0)

@ThreadLocal
private var slots: Array<Any?> = arrayOfNulls(8) // grows if more ScratchLocals are added

internal actual class ScratchLocal<T : Any> actual constructor(
    private val initializer: () -> T,
) {
    @OptIn(ExperimentalAtomicApi::class)
    private val slot = nextSlot.fetchAndAdd(1)

    @Suppress("UNCHECKED_CAST")
    actual fun get(): T {
        val s = slots
        if (slot < s.size) {
            val v = s[slot]
            if (v != null) return v as T
        }
        return create()
    }

    private fun create(): T {
        var s = slots
        if (slot >= s.size) {
            s = s.copyOf(maxOf(slot + 1, s.size * 2))
            slots = s
        }
        val v = initializer()
        s[slot] = v
        return v
    }
}
