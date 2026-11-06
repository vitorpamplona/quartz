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

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/** Reads 8 bytes of a `ByteArray` as one little-endian `long`: one load once the JIT compiles it. */
private val LONGS: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

private const val ONES = 0x0101010101010101L
private const val HIGHS = -0x7F7F7F7F7F7F7F80L // 0x8080808080808080

/**
 * SWAR: xor each 8-byte word with [target] repeated, so a matching byte becomes zero, and find
 * the lowest zero byte with `(x - 0x01…) & ~x & 0x80…`. Its lowest set bit is exact (a borrow can
 * only mark bytes above a real zero), and the word is little-endian, so it is the first match.
 * Two words per turn: one branch per 16 bytes.
 */
internal actual fun indexOfByte(
    bytes: ByteArray,
    from: Int,
    to: Int,
    target: Byte,
): Int {
    val pattern = (target.toLong() and 0xFF) * ONES
    var i = from
    while (i + 16 <= to) {
        val x0 = (LONGS.get(bytes, i) as Long) xor pattern
        val x1 = (LONGS.get(bytes, i + 8) as Long) xor pattern
        val m0 = (x0 - ONES) and x0.inv() and HIGHS
        val m1 = (x1 - ONES) and x1.inv() and HIGHS
        if ((m0 or m1) != 0L) {
            return if (m0 != 0L) i + (m0.countTrailingZeroBits() ushr 3) else i + 8 + (m1.countTrailingZeroBits() ushr 3)
        }
        i += 16
    }
    if (i + 8 <= to) {
        val x = (LONGS.get(bytes, i) as Long) xor pattern
        val m = (x - ONES) and x.inv() and HIGHS
        if (m != 0L) return i + (m.countTrailingZeroBits() ushr 3)
        i += 8
    }
    while (i < to) {
        if (bytes[i] == target) return i
        i++
    }
    return -1
}
