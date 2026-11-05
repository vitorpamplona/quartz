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
package com.vitorpamplona.quartz.nip34Git

import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import com.vitorpamplona.quartz.nip34Git.state.tags.RefTag
import kotlin.test.Test
import kotlin.test.assertEquals

class GitStateChangeHeadTest {
    private val previous =
        GitRepositoryStateEvent
            .build(
                dTag = "repo",
                refs = listOf(RefTag.branch("main", "a".repeat(40)), RefTag.branch("dev", "b".repeat(40))),
                head = "main",
                createdAt = 2_000,
            ).let { GitRepositoryStateEvent("0".repeat(64), "1".repeat(64), it.createdAt, it.tags, it.content, "") }

    @Test
    fun headMovesAndRefsStay() {
        val next = GitRepositoryStateEvent.changeHead(previous, "dev", createdAt = 1_000)
        val event = GitRepositoryStateEvent("0".repeat(64), "1".repeat(64), next.createdAt, next.tags, next.content, "")
        assertEquals("dev", event.head())
        assertEquals(previous.refs().map { it.name to it.commit }, event.refs().map { it.name to it.commit })
        assertEquals("repo", event.dTag())
        assertEquals(1, event.tags.count { it[0] == "HEAD" })
        // Dated after the state it replaces, even with a clock behind it.
        assertEquals(2_001, event.createdAt)
    }
}
