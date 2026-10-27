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
package com.vitorpamplona.quartz.nip01Core.kite

import com.vitorpamplona.crypto.sha2.sha256
import kotlin.native.concurrent.ThreadLocal

// One writer per thread, as the JVM's ThreadLocal gives, so its buffers outlive a write.
@ThreadLocal
private val threadWriter = KiteWriter()

internal actual fun threadKiteWriter(): KiteWriter? = threadWriter

internal actual fun copyAsciiInto(
    s: String,
    start: Int,
    end: Int,
    dest: ByteArray,
    at: Int,
) {
    for (k in start until end) dest[at + k - start] = s[k].code.toByte()
}

// crypto-kmp has no public streaming SHA-256 on native: the writer keeps every byte.
internal actual fun sha256Feed(
    data: ByteArray,
    length: Int,
): Boolean = false

internal actual fun sha256Abort() {}

// crypto-kmp has no public streaming SHA-256 on native, so this hashes a copy.
internal actual fun sha256Of(
    data: ByteArray,
    length: Int,
): ByteArray = sha256(data.copyOf(length))
