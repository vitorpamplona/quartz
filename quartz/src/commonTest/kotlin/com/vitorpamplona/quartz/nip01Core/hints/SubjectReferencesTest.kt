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
package com.vitorpamplona.quartz.nip01Core.hints

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubjectReferencesTest {
    private val author = "7d7ffd720b907fe597a7f454afe02f2dc1eca440baa029e9117b1c3209839377"
    private val alice = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val bob = "99bb5591c9116600f845107d31f9b59e2f7c7e09a1ff802e84f1d43da557ca64"
    private val note = "c".repeat(64)
    private val address = "30023:$alice:my-article"

    // A plain Event, never a typed subclass: dispatch is on the kind.
    private fun event(
        kind: Int,
        vararg tags: Array<String>,
    ) = Event("a".repeat(64), author, 1L, kind, arrayOf(*tags), "", "b".repeat(128))

    @Test
    fun aLabelNamesItsEPAndATargetsButNotUrlsOrTopics() {
        val refs =
            SubjectReferences.of(
                event(
                    1985,
                    arrayOf("L", "ugc"),
                    arrayOf("l", "spam", "ugc"),
                    arrayOf("e", note),
                    arrayOf("p", alice),
                    arrayOf("a", "30023:${alice.uppercase()}:my-article"),
                    arrayOf("r", "https://example.com"),
                    arrayOf("t", "bitcoin"),
                ),
            )
        assertEquals(listOf(note), refs.eventIds)
        assertEquals(listOf(alice), refs.pubKeys)
        // The coordinate comes back canonical: lowercase pubkey.
        assertEquals(listOf(address), refs.addresses)
        assertTrue(refs.scores.isEmpty())
    }

    @Test
    fun anAssertionNamesItsDTagSubject() {
        assertEquals(listOf(alice), SubjectReferences.of(event(30382, arrayOf("d", alice), arrayOf("p", bob))).pubKeys)
        assertEquals(listOf(note), SubjectReferences.of(event(30383, arrayOf("d", note))).eventIds)
        assertEquals(listOf(address), SubjectReferences.of(event(30384, arrayOf("d", address))).addresses)
        // A d that is not exactly the subject's shape names nothing.
        assertTrue(SubjectReferences.of(event(30382, arrayOf("d", "$alice:v2"))).isEmpty())
        assertTrue(SubjectReferences.of(event(30383, arrayOf("d", "not-an-id"))).isEmpty())
        // NIP-73 external ids are not nostr records.
        assertTrue(SubjectReferences.of(event(30385, arrayOf("d", "isbn:9780765382030"))).isEmpty())
    }

    @Test
    fun aTrustedListNamesItsMemberTagsWithTheirScores() {
        val users = SubjectReferences.of(event(30392, arrayOf("d", "x"), arrayOf("p", alice, "", "87"), arrayOf("p", bob, "", "950"), arrayOf("e", note)))
        assertEquals(listOf(alice, bob), users.pubKeys)
        assertTrue(users.eventIds.isEmpty(), "a 30392 names people only")
        // 950 is off the 0..100 scale: the member stands, unscored.
        assertEquals(mapOf(alice to 87), users.scores)

        assertEquals(listOf(note), SubjectReferences.of(event(30393, arrayOf("e", note, "", "40"))).eventIds)
        val addresses = SubjectReferences.of(event(30394, arrayOf("a", address, "", "12")))
        assertEquals(listOf(address), addresses.addresses)
        assertEquals(mapOf(address to 12), addresses.scores)
    }

    @Test
    fun aPeopleListNamesItsPublicPTags() {
        for (kind in listOf(30000, 39089, 39092)) {
            assertEquals(listOf(alice, bob), SubjectReferences.of(event(kind, arrayOf("d", "x"), arrayOf("p", alice), arrayOf("p", bob))).pubKeys)
        }
    }

    @Test
    fun otherKindsNominateNothing() {
        // Follows and mutes are not nominations; a note's p tags are mentions.
        for (kind in listOf(1, 3, 10000, 7)) {
            assertTrue(SubjectReferences.of(event(kind, arrayOf("p", alice), arrayOf("e", note))).isEmpty())
        }
        assertTrue(1985 in SubjectReferences.KINDS && 30382 in SubjectReferences.KINDS && 3 !in SubjectReferences.KINDS)
    }
}
