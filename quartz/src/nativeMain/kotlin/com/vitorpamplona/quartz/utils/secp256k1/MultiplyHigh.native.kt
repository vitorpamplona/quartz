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

import kotlin.native.internal.GCUnsafeCall

/**
 * Hardware multiply-high on Kotlin/Native: one `mul` / `umulh` (unsigned) or
 * `imul` / `smulh` (signed), inlined into the caller.
 *
 * The C helpers live in `src/nativeInterop/cinterop/mulhi.def`. They are bound with
 * `@GCUnsafeCall`, the annotation the Kotlin/Native stdlib uses for its own small runtime
 * functions, instead of the generated cinterop bindings: a regular cinterop call switches
 * the thread's GC state around the call (~11 ns of atomic exchanges), which is what made
 * C interop slower than the 4-multiply Kotlin fallback here. Without the switch, LLVM
 * inlines the C body, because cinterop C code is linked into the program as bitcode.
 *
 * `GCUnsafeCall` is a Kotlin/Native internal API (opt-in set in build.gradle.kts). It is
 * safe only for short C functions that never block, call back into Kotlin or touch Kotlin
 * objects, which is all these do. Measured and disassembled in Long128-kmp
 * (github.com/vitorpamplona/Long128-kmp, verify-instructions.sh).
 */
@GCUnsafeCall("quartz_umulh")
private external fun hwUnsignedMultiplyHigh(
    a: Long,
    b: Long,
): Long

@GCUnsafeCall("quartz_smulh")
private external fun hwMultiplyHigh(
    a: Long,
    b: Long,
): Long

internal actual fun multiplyHigh(
    a: Long,
    b: Long,
): Long = hwMultiplyHigh(a, b)

internal actual fun unsignedMultiplyHigh(
    a: Long,
    b: Long,
): Long = hwUnsignedMultiplyHigh(a, b)
