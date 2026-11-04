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
package com.vitorpamplona.quartz.nip01Core.crypto

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [verify] checks the id and the signature with buffers it reuses across events: it must give
 * the answer `verifyId() && verifySignature()` gives, valid or not, and nothing may carry over
 * from one event to the next.
 */
class EventVerifierTest {
    private val signer = NostrSignerSync()

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, arrayOf(arrayOf("t", "kite")), content)

    private fun Event.copy(
        id: String = this.id,
        pubKey: String = this.pubKey,
        content: String = this.content,
        sig: String = this.sig,
    ) = Event(id, pubKey, createdAt, kind, tags, content, sig)

    /** The answer before the reused buffers, exceptions counted as false (as verify() does). */
    private fun Event.reference(): Boolean =
        try {
            verifyId() && verifySignature()
        } catch (_: Exception) {
            false
        }

    private fun assertSameAnswer(
        event: Event,
        expected: Boolean,
    ) {
        assertEquals(expected, event.reference(), "reference")
        assertEquals(expected, event.verify(), "verify()")
    }

    @Test
    fun aValidEventVerifies() = assertSameAnswer(note("hello"), true)

    @Test
    fun changedContentFails() = assertSameAnswer(note("hello").copy(content = "hello (forged)"), false)

    @Test
    fun aChangedIdFails() {
        val e = note("hello")
        val flipped = e.id.dropLast(1) + (if (e.id.last() == '0') '1' else '0')
        assertSameAnswer(e.copy(id = flipped), false)
    }

    @Test
    fun anotherEventsSignatureFails() = assertSameAnswer(note("hello").copy(sig = note("other").sig), false)

    @Test
    fun anUpperCaseIdFails() {
        val e = note("hello")
        assertSameAnswer(e.copy(id = e.id.uppercase()), false)
    }

    @Test
    fun wrongLengthsFail() {
        val e = note("hello")
        assertSameAnswer(e.copy(sig = ""), false)
        assertSameAnswer(e.copy(sig = e.sig.dropLast(2)), false)
        assertSameAnswer(e.copy(id = e.id.dropLast(2)), false)
        assertSameAnswer(e.copy(pubKey = e.pubKey.dropLast(2)), false)
    }

    @Test
    fun charactersOutsideHexFailWithoutThrowing() {
        val e = note("hello")
        // A sig of the right length holding non-ASCII: the hex table has no entry for it.
        assertSameAnswer(e.copy(sig = "\u00e9".repeat(128)), false)
        assertSameAnswer(e.copy(pubKey = "z".repeat(64)), false)
    }

    @Test
    fun nothingCarriesOverBetweenEvents() {
        val a = note("a")
        val b = note("b")
        assertTrue(a.verify())
        assertFalse(b.copy(sig = a.sig).verify())
        assertTrue(b.verify())
        assertFalse(a.copy(content = "a!").verify())
        assertTrue(a.verify())
    }
}
