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
package com.vitorpamplona.quartz.utils.secp256k1

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every signing entry point gives the same, valid BIP-340 signature, for keys whose public key has
 * even y and for keys whose public key has odd y (BIP-340 signs with n - d for the latter).
 */
class SignEntryPointsTest {
    private val r = Random(0x516E)

    private fun seckey(): ByteArray {
        while (true) {
            val s = r.nextBytes(32)
            if (Secp256k1.secKeyVerify(s)) return s
        }
    }

    @Test
    fun allSignPathsAgreeAndVerify() {
        var even = 0
        var odd = 0
        repeat(200) {
            val sk = seckey()
            val compressed = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(sk))
            val xOnly = compressed.copyOfRange(1, 33)
            if (compressed[0] == 0x02.toByte()) even++ else odd++
            val msg = r.nextBytes(if (it % 2 == 0) 32 else r.nextInt(0, 300))
            val aux = if (it % 5 == 0) null else r.nextBytes(32)

            val full = Secp256k1.signSchnorr(msg, sk, aux)
            assertTrue(Secp256k1.verifySchnorr(full, msg, xOnly), "signSchnorr")
            assertContentEquals(full, Secp256k1.signSchnorrWithPubKey(msg, sk, compressed, aux), "signSchnorrWithPubKey")
            assertContentEquals(full, Secp256k1.signSchnorrWithXOnlyPubKey(msg, sk, xOnly, aux), "signSchnorrWithXOnlyPubKey")
            assertContentEquals(full, Secp256k1.signSchnorrWithPubKey(msg, sk, compressed, aux), "repeat")
        }
        assertTrue(even > 50 && odd > 50, "both parities covered: even=$even odd=$odd")
    }

    @Test
    fun xOnlyOfAnotherKeyIsRejected() {
        val sk = seckey()
        val other = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(seckey())).copyOfRange(1, 33)
        assertFailsWith<IllegalArgumentException> { Secp256k1.signSchnorrWithXOnlyPubKey(ByteArray(32), sk, other, null) }
    }
}
