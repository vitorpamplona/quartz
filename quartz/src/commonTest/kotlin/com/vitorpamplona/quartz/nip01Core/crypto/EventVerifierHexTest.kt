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
import com.vitorpamplona.quartz.utils.Hex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The verifier decoded sig and pubkey with a table that maps a non-hex char to -1, so `g` in a
 * high nibble decoded as `f`: a mutated signature string still verified, as did an uppercase one.
 * NIP-01 fields are lowercase hex; anything else must fail.
 */
class EventVerifierHexTest {
    private val priv = ByteArray(32).also { it[31] = 7 }
    private val pub = Hex.encode(Nip01Crypto.pubKeyCreate(priv))

    private fun signed(
        pubKey: String = pub,
        content: String = "hello",
    ): Event {
        val at = 1_700_000_000L
        val tags = emptyArray<Array<String>>()
        val id = EventHasher.hashIdBytes(pubKey, at, 1, tags, content)
        val sig = Nip01Crypto.sign(id, priv, ByteArray(32))
        return Event(Hex.encode(id), pubKey, at, 1, tags, content, Hex.encode(sig))
    }

    private fun Event.withSig(sig: String) = Event(id, pubKey, createdAt, kind, tags, content, sig)

    @Test
    fun theOriginalVerifies() {
        assertTrue(signed().verify())
    }

    @Test
    fun aSignatureWithNonHexCharsDoesNotVerify() {
        // Find an event whose signature has an `f` in a high nibble, then turn it into `g`.
        var e = signed()
        var n = 0
        while (e.sig.indices.none { it % 2 == 0 && e.sig[it] == 'f' }) e = signed(content = "hello ${n++}")
        val chars = e.sig.toCharArray()
        for (i in chars.indices step 2) if (chars[i] == 'f') chars[i] = 'g'
        val mutated = e.withSig(chars.concatToString())
        assertFalse(mutated.verify(), "a sig with 'g' for 'f' verified")
        assertFalse(mutated.verifySignature())
    }

    @Test
    fun anUppercaseSignatureDoesNotVerify() {
        val e = signed()
        assertFalse(e.withSig(e.sig.uppercase()).verify())
    }

    @Test
    fun anEventSignedOverAnUppercasePubkeyDoesNotVerify() {
        // The id hashes the pubkey string as written, so the key holder can sign over an
        // uppercase spelling: the same author under a second, non-NIP-01 name.
        assertFalse(signed(pubKey = pub.uppercase()).verify())
    }

    @Test
    fun aSignatureWithCharsAboveLatin1DoesNotVerify() {
        val e = signed()
        val mutated = e.withSig("一" + e.sig.substring(1))
        assertFalse(mutated.verify())
    }
}
