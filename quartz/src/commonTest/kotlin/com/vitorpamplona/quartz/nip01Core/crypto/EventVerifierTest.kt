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
import com.vitorpamplona.quartz.utils.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [verify] and [verifySignature] check with buffers they reuse across events: they must give the
 * answers the allocating code gave, valid or not, and nothing may carry over from one event to
 * the next. Large events go through the streamed id hash (16 KB chunks on the JVM).
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

    /** verifySignature() before the reused buffers. */
    private fun Event.referenceSignature(): Boolean {
        if (id.length != 64 || pubKey.length != 64 || sig.length != 128) return false
        return Nip01Crypto.verify(Hex.decode(sig), Hex.decode(id), Hex.decode(pubKey))
    }

    /** The answer, or the exception's class: a throw must stay the same throw. */
    private inline fun outcome(check: () -> Boolean): Any =
        try {
            check()
        } catch (e: Exception) {
            e::class
        }

    /** What verify() answered before the reused buffers, exceptions counted as false (as it does). */
    private fun Event.reference(): Boolean =
        try {
            verifyId() && referenceSignature()
        } catch (_: Exception) {
            false
        }

    private fun assertSameAnswer(
        event: Event,
        expected: Boolean,
    ) {
        assertEquals(expected, event.reference(), "reference")
        assertEquals(expected, event.verify(), "verify()")
        assertEquals(outcome { event.referenceSignature() }, outcome { event.verifySignature() }, "verifySignature()")
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

    @Test
    fun verifySignatureChecksTheSignatureOverTheIdAsGiven() {
        val e = note("hello")
        assertTrue(e.verifySignature())
        // The signature is over the id, not the content: a changed content still passes here.
        assertTrue(e.copy(content = "forged").verifySignature())
        assertFalse(e.copy(sig = note("other").sig).verifySignature())
    }

    @Test
    fun aLargeEventVerifies() {
        // Past the hash's 16 KB chunks many times over, so the id is hashed streamed on the JVM.
        val e = note("x".repeat(200_000))
        assertSameAnswer(e, true)
        assertSameAnswer(e.copy(content = "x".repeat(199_999) + "y"), false)
    }

    @Test
    fun aLargeEventWithMultiByteCharactersAndEscapesVerifies() {
        // Two- and four-byte UTF-8 and escaped characters, so chunk ends fall inside them.
        val e = note("\u00e9\uD83D\uDE00\"\\\n\u0001".repeat(20_000))
        assertSameAnswer(e, true)
        assertSameAnswer(e.copy(content = e.content.dropLast(1)), false)
    }

    @Test
    fun anEventWithManyTagsVerifies() {
        val other = note("p")
        val tags = Array(5_000) { arrayOf("p", other.pubKey, "wss://relay$it.example.com/", "mention") }
        val e = signer.sign<Event>(1_700_000_000L, 3, tags, "")
        assertSameAnswer(e, true)
        val dropped = Event(e.id, e.pubKey, e.createdAt, e.kind, tags.copyOf(tags.size - 1).requireNoNulls(), e.content, e.sig)
        assertSameAnswer(dropped, false)
    }

    @Test
    fun aLargeEventDoesNotSpoilTheNextSmallOne() {
        val big = note("x".repeat(100_000))
        val small = note("small")
        assertTrue(big.verify())
        assertTrue(small.verify())
        assertFalse(big.copy(content = "x").verify())
        assertTrue(small.verify())
    }
}
