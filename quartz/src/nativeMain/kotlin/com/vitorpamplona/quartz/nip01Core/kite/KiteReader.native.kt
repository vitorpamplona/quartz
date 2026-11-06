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

import kotlin.native.concurrent.ThreadLocal

// One reader per thread, as the JVM's ThreadLocal gives, so its buffers outlive a parse.
@ThreadLocal
private val threadReader = KiteReader()

internal actual fun threadKiteReader(): KiteReader? = threadReader

// Kotlin/Native strings are UTF-16 and its indexOf has no intrinsic, so a Latin-1 view would
// only double the bytes: native decodes the frame instead (LATIN1_VIEW is false).
internal actual fun latin1String(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String = CharArray(length) { (bytes[offset + it].toInt() and 0xFF).toChar() }.concatToString()

internal actual val LATIN1_VIEW: Boolean = false
