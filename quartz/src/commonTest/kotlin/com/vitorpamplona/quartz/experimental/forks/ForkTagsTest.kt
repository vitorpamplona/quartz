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
package com.vitorpamplona.quartz.experimental.forks

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip54Wiki.WikiArticleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkTagsTest {
    private val original = WikiArticleEvent("ab".repeat(32), "cd".repeat(32), 1700000000, arrayOf(arrayOf("d", "hummingbird")), "", "00".repeat(64))

    private fun forkOf(relay: String?): WikiArticleEvent {
        val template =
            WikiArticleEvent.build("My take", "Hummingbird", dTag = "hummingbird") {
                forkOf(original, relay?.let { RelayUrlNormalizer.normalizeOrNull(it) })
            }
        return WikiArticleEvent("ef".repeat(32), "12".repeat(32), template.createdAt, template.tags, template.content, "00".repeat(64))
    }

    @Test
    fun aForkPointsAtTheAddressAndTheVersion() {
        val fork = forkOf(null)
        assertTrue(fork.isAFork())
        assertEquals(original.address(), fork.forkFromAddress())
        assertEquals(original.id, fork.forkFromVersion())
        assertTrue(fork.tags.any { it.contentEquals(arrayOf("a", original.address().toValue(), "", "fork")) })
        assertTrue(fork.tags.any { it.contentEquals(arrayOf("e", original.id, "", "fork", original.pubKey)) })
    }

    @Test
    fun theRelayHintGoesInBoth() {
        val fork = forkOf("wss://relay.example.com")
        assertTrue(fork.tags.any { it[0] == "a" && it[2] == "wss://relay.example.com/" && it[3] == "fork" })
        assertTrue(fork.tags.any { it[0] == "e" && it[2] == "wss://relay.example.com/" && it[3] == "fork" })
        assertEquals(original.address(), fork.forkFromAddress())
    }
}
