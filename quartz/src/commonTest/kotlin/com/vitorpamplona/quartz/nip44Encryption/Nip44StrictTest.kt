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
package com.vitorpamplona.quartz.nip44Encryption

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strict half of NIP-44 for channels whose authenticity rests on the cipher (seals, ContextVM
 * wraps, NIP-46): [Nip44.decrypt] opens NIP-04 and NIP-44 v1 too, which carry no MAC.
 */
class Nip44StrictTest {
    private val alice = KeyPair()
    private val bob = KeyPair()

    private fun v2Payload() = Nip44.encrypt("hello", alice.privKey!!, bob.pubKey).encodePayload()

    @Test
    fun payloadVersionReadsEachForm() {
        assertEquals(2, Nip44.payloadVersion(v2Payload()))
        // NIP-44 v1's base64 form: version byte 1, a 24-byte nonce, the ciphertext.
        val v1 = Base64.encode(byteArrayOf(1) + ByteArray(24) + ByteArray(16))
        assertEquals(1, Nip44.payloadVersion(v1))
        // A NIP-04 ciphertext lifted into the JSON form with v 0.
        val nip04 = Nip04.encrypt("hello", alice.privKey!!, bob.pubKey).substringBefore("?iv=")
        assertEquals(0, Nip44.payloadVersion("{\"v\":0,\"nonce\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"ciphertext\":\"$nip04\"}"))
        assertEquals(-1, Nip44.payloadVersion(""))
        assertEquals(-1, Nip44.payloadVersion("#not-a-payload"))
    }

    // A NIP-04 string is base64 then `?iv=`: its first byte is random, so about 3 in 256 of them
    // used to read as version 0, 1 or 2. Base64 has no `?`, so no NIP-44 payload carries one.
    @Test
    fun aNip04StringIsNeverReadAsAVersion() {
        val iv = Base64.encode(ByteArray(16))
        for (first in 0..2) {
            val nip04 = Base64.encode(byteArrayOf(first.toByte()) + ByteArray(15)) + "?iv=" + iv
            assertEquals(-1, Nip44.payloadVersion(nip04), "a NIP-04 ciphertext starting with byte $first")
            assertFalse(Nip44.isV2Payload(nip04))
        }
        repeat(64) { assertEquals(-1, Nip44.payloadVersion(Nip04.encrypt("hello $it", alice.privKey!!, bob.pubKey))) }
    }

    @Test
    fun onlyTheBase64V2FormIsAV2Payload() {
        assertTrue(Nip44.isV2Payload(v2Payload()))
        assertFalse(Nip44.isV2Payload("{\"v\":2,\"nonce\":\"AA==\",\"ciphertext\":\"AA==\",\"mac\":\"AA==\"}"))
        assertFalse(Nip44.isV2Payload(Base64.encode(byteArrayOf(1) + ByteArray(40))))
    }

    @Test
    fun decryptV2OnlyOpensV2() {
        assertEquals("hello", Nip44.decryptV2Only(v2Payload(), bob.privKey!!, alice.pubKey))
    }

    @Test
    fun decryptV2OnlyRefusesNip04InTheJsonForm() {
        // The lenient decrypt opens this: CBC with no MAC, so anyone holding an old kind-4
        // ciphertext between two users could reshape it into a "seal" from one to the other.
        val encrypted = Nip04.encrypt("hello", alice.privKey!!, bob.pubKey)
        val ciphertext = encrypted.substringBefore("?iv=")
        val iv = encrypted.substringAfter("?iv=")
        val json = "{\"v\":0,\"nonce\":\"$iv\",\"ciphertext\":\"$ciphertext\"}"
        assertEquals("hello", Nip44.decrypt(json, bob.privKey!!, alice.pubKey))
        assertFailsWith<IllegalArgumentException> { Nip44.decryptV2Only(json, bob.privKey!!, alice.pubKey) }
    }

    @Test
    fun decryptOnceOpensWhatEncryptOnceSealed() {
        val sealed = Nip44.encryptOnce("hello", alice.privKey!!, bob.pubKey).encodePayload()
        assertEquals("hello", Nip44.decryptOnce(sealed, bob.privKey!!, alice.pubKey))
    }

    @Test
    fun aMacFailureDoesNotPrintTheExpectedMac() {
        val payload = v2Payload()
        val decoded = Base64.decode(payload)
        decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 1).toByte()
        val tampered = Base64.encode(decoded)
        val error = assertFailsWith<IllegalStateException> { Nip44.v2.decrypt(tampered, bob.privKey!!, alice.pubKey) }
        val message = error.message ?: ""
        assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(message), "the error leaks a MAC: $message")
    }
}
