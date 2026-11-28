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

import java.io.InputStream

/** The JVM form of [GitPackReader], reading the pack from an [InputStream]. */
@Deprecated("Use GitPackReader instead. Removed in 3.0.0.", ReplaceWith("GitPackReader"))
object PackfileStream {
    /** Delta bases kept by default while a pack is read. */
    const val DEFAULT_RETAIN_BYTES: Long = GitPackReader.DEFAULT_RETAIN_BYTES

    /**
     * Reads the pack in [input] to its last object, calling [onObject] for each one rebuilt (in
     * pack order, a waiting delta right after its base) and [onSkipped] for each one passed over,
     * with its oid when it could be computed. Throws on a malformed or truncated pack.
     */
    fun read(
        input: InputStream,
        maxObjectBytes: Long = Long.MAX_VALUE,
        retainBytes: Long = DEFAULT_RETAIN_BYTES,
        onSkipped: (oid: String?, reason: GitPackSkip) -> Unit = { _, _ -> },
        onObject: (GitObject) -> Unit,
    ) = GitPackReader.read(input.asGitByteSource(), maxObjectBytes, retainBytes, onSkipped, onObject)
}
