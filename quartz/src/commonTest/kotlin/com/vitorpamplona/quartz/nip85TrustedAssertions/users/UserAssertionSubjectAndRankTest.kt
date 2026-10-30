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
package com.vitorpamplona.quartz.nip85TrustedAssertions.users

import com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags.RankTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserAssertionSubjectAndRankTest {
    private val provider = "7d7ffd720b907fe597a7f454afe02f2dc1eca440baa029e9117b1c3209839377"
    private val subject = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"

    private fun card(vararg tags: Array<String>) =
        UserAssertionEvent(
            id = "00".repeat(32),
            pubKey = provider,
            createdAt = 1700000000,
            tags = arrayOf(*tags),
            content = "",
            sig = "00".repeat(64),
        )

    @Test
    fun theSubjectIsTheDTagWhenItIsALowercasePubKey() {
        assertEquals(subject, card(arrayOf("d", subject)).subjectPubKey())
    }

    @Test
    fun aDTagThatIsNotExactlyAPubKeyHasNoSubject() {
        // Hex.isHex64 alone reads only the first 64 characters: this is another address.
        assertNull(card(arrayOf("d", "$subject:v2")).subjectPubKey())
        assertNull(card(arrayOf("d", subject.uppercase())).subjectPubKey())
        assertNull(card(arrayOf("d", subject.dropLast(1))).subjectPubKey())
        assertNull(card(arrayOf("d", "z" + subject.drop(1))).subjectPubKey())
        assertNull(card(arrayOf("d", "")).subjectPubKey())
        assertNull(card().subjectPubKey())
    }

    @Test
    fun subjectCheckIsExposedForATagSubjectsToo() {
        assertTrue(UserAssertionEvent.isSubjectPubKey(subject))
        assertFalse(UserAssertionEvent.isSubjectPubKey("$subject:v2"))
        assertFalse(UserAssertionEvent.isSubjectPubKey(null))
    }

    @Test
    fun boundedRankClampsToTheNip85Scale() {
        assertEquals(89, card(arrayOf("rank", "89")).boundedRank())
        assertEquals(100, card(arrayOf("rank", "250")).boundedRank())
        assertEquals(0, card(arrayOf("rank", "-3")).boundedRank())
        // Zero is a value here, not a removal (TrustNetworkBuilder's reading).
        assertEquals(0, card(arrayOf("rank", "0")).boundedRank())
        assertNull(card(arrayOf("rank", "high")).boundedRank())
        assertNull(card().boundedRank())
        assertEquals(100, RankTag.parseBounded(arrayOf("rank", "101")))
    }
}
