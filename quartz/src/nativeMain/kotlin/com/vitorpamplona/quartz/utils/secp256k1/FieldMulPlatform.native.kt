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

/**
 * Native field multiply/square: the same unfused path as the JVM (mulWide + reduceWide).
 *
 * That path makes 16 multiply-high calls per multiply (10 per square) plus 4-5 in the
 * reduction, and on Kotlin/Native each one is now a single inlined `mul` / `umulh`
 * (MultiplyHigh.native.kt, @GCUnsafeCall). Before that, native used a fused path that
 * shared 32-bit sub-products across each product's low and high halves, the better choice
 * only while every multiply-high cost four IMULs; it was removed once nothing called it.
 */
internal actual fun fieldMulReduce(
    out: Fe4,
    a: Fe4,
    b: Fe4,
    w: Wide8,
) {
    U256.mulWide(w, a, b)
    FieldP.reduceWide(out, w)
}

internal actual fun fieldSqrReduce(
    out: Fe4,
    a: Fe4,
    w: Wide8,
) {
    U256.sqrWide(w, a)
    FieldP.reduceWide(out, w)
}
