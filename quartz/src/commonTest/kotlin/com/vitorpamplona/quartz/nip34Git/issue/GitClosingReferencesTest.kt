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
package com.vitorpamplona.quartz.nip34Git.issue

import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitClosingReferencesTest {
    private val id = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun readsPrefixesAfterTheVerb() {
        val refs = GitClosingReferences.parse("Tidy the parser\n\nFixes #a1b2c3d4 and #0badc0de")
        assertEquals(listOf("a1b2c3d4", "0badc0de"), refs.map { it.id })
        assertTrue(refs.all { it.isPrefix })
        assertEquals("Fixes #a1b2c3d4 and #0badc0de", refs.first().line)
    }

    @Test
    fun ignoresReferencesBeforeTheVerbOrOnOtherLines() {
        assertEquals(emptyList(), GitClosingReferences.parse("#a1b2c3d4 was fixed\nsee #0badc0de").map { it.id })
    }

    @Test
    fun readsFullIdsAndBech32() {
        val note = NNote.create(id)
        val refs = GitClosingReferences.parse("Closed nostr:$note\nresolves $id")
        assertEquals(listOf(id), refs.map { it.id })
        assertTrue(refs.none { it.isPrefix })
    }

    @Test
    fun everyTense() {
        listOf("close", "closes", "closed", "closing", "fix", "fixed", "fixing", "resolve", "resolving", "implement", "implemented", "implements", "implementing").forEach {
            assertEquals(1, GitClosingReferences.parse("$it #a1b2c3d4").size, it)
        }
        assertEquals(0, GitClosingReferences.parse("prefix #a1b2c3d4").size)
    }
}
