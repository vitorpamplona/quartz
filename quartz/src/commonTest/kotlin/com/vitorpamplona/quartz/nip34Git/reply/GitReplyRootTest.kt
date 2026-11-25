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
package com.vitorpamplona.quartz.nip34Git.reply

import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
class GitReplyRootTest {
    private val bob = "b".repeat(64)
    private val issueId = "1".repeat(64)
    private val otherId = "2".repeat(64)
    private val parentId = "3".repeat(64)
    private val sig = "00".repeat(64)

    /** NIP-10: `e` tags are sorted from the root to the direct parent, so the first `root` wins. */
    @Test
    fun theFirstRootMarkedETagIsTheIssueOrPatch() {
        val reply =
            GitReplyEvent(
                "4".repeat(64),
                bob,
                2,
                arrayOf(arrayOf("e", issueId, "", "root"), arrayOf("e", otherId, "", "root"), arrayOf("e", parentId, "", "reply")),
                "same here",
                sig,
            )

        assertEquals(issueId, reply.rootIssueOrPatch())
    }
}
