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
package com.vitorpamplona.quartz.nip01Core.store

import kotlin.test.Test
import kotlin.test.assertTrue

class RawEventTest {
    private fun raw(
        content: String,
        createdAt: Long = Long.MAX_VALUE,
        kind: Int = Int.MAX_VALUE,
    ) = RawEvent("a".repeat(64), "b".repeat(64), createdAt, kind, """[["p","${"c".repeat(64)}"],["t","nostr"]]""", content, "d".repeat(128))

    private fun written(event: RawEvent) = buildString { event.appendJsonObjectTo(this) }.length

    @Test
    fun estimateBoundsTheWrittenObject() {
        for (event in listOf(raw(""), raw("gm"), raw("x".repeat(5000)), raw("a line of a note, and a break at its end\n".repeat(40)), raw("été 你好"), raw("", createdAt = 0, kind = 1))) {
            assertTrue(written(event) <= event.jsonObjectLengthEstimate(), "wrote ${written(event)}, estimated ${event.jsonObjectLengthEstimate()}")
        }
    }
}
