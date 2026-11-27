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
package com.vitorpamplona.quartz.nip55AndroidSigner.payloads

import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.RetainedPayloads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedPayloadsTest {
    private val signer = "com.greenart7c3.nostrsigner"
    private val other = "com.example.signer"

    private fun registry() = RetainedPayloads<String>(ttlSeconds = 45, maxPerSigner = 2)

    @Test
    fun aKeptHandleIsHandedOutOnce() {
        val retained = registry()
        retained.keep(signer, "payload", "h1", now = 100)

        assertEquals("h1", retained.take(signer, "payload", now = 101))
        assertNull(retained.take(signer, "payload", now = 101))
    }

    @Test
    fun aHandleIsOnlyHandedOutForItsSignerAndPayload() {
        val retained = registry()
        retained.keep(signer, "payload", "h1", now = 100)

        assertNull(retained.take(other, "payload", now = 101))
        assertNull(retained.take(signer, "another payload", now = 101))
        assertEquals("h1", retained.take(signer, "payload", now = 101))
    }

    @Test
    fun anExpiredHandleIsForgotten() {
        val retained = registry()
        retained.keep(signer, "payload", "h1", now = 100)

        assertNull(retained.take(signer, "payload", now = 145))
        assertEquals(0, retained.size())
    }

    @Test
    fun forgettingExpiredHandlesFreesTheirPayloadsWithoutATake() {
        val retained = registry()
        retained.keep(signer, "old", "h1", now = 100)
        retained.keep(signer, "new", "h2", now = 130)

        retained.forgetExpired(now = 150)

        assertEquals(1, retained.size())
        assertEquals("h2", retained.take(signer, "new", now = 150))
    }

    @Test
    fun aSignerKeepsOnlyItsNewestHandlesAndHandsBackTheEvictedOnes() {
        val retained = registry()
        assertEquals(emptyList(), retained.keep(signer, "p1", "h1", now = 100))
        assertEquals(emptyList(), retained.keep(signer, "p2", "h2", now = 101))
        assertEquals(emptyList(), retained.keep(other, "p1", "o1", now = 101))
        assertEquals(listOf("h1"), retained.keep(signer, "p3", "h3", now = 102))

        assertNull(retained.take(signer, "p1", now = 103))
        assertEquals("h2", retained.take(signer, "p2", now = 103))
        assertEquals("h3", retained.take(signer, "p3", now = 103))
        assertEquals("o1", retained.take(other, "p1", now = 103))
    }
}
