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
package com.vitorpamplona.quartz.utils

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.utils.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Secp256k1InstanceKotlin (schnorr256k1-kotlin) gives the same results as Secp256k1Instance
 * (libsecp256k1 through ACINQ), and it and the deprecated Secp256k1 keep throwing on invalid
 * input, as before they delegated to the library.
 */
class Secp256k1InstanceKotlinTest {
    private fun key(): ByteArray {
        while (true) {
            val k = RandomInstance.bytes(32)
            if (Secp256k1Instance.isPrivateKeyValid(k)) return k
        }
    }

    @Test
    fun matchesLibsecp256k1() {
        repeat(50) {
            val d = key()
            val pub = Secp256k1Instance.compressedPubKeyFor(d)
            assertContentEquals(pub, Secp256k1InstanceKotlin.compressedPubKeyFor(d))
            val xOnly = pub.copyOfRange(1, 33)
            val msg = RandomInstance.bytes(32)
            val aux = RandomInstance.bytes(32)
            val sig = Secp256k1Instance.signSchnorr(msg, d, aux)
            assertContentEquals(sig, Secp256k1InstanceKotlin.signSchnorr(msg, d, aux))
            assertContentEquals(sig, Secp256k1InstanceKotlin.signSchnorrWithPubKey(msg, d, pub, aux))
            assertContentEquals(sig, Secp256k1InstanceKotlin.signSchnorrWithXOnlyPubKey(msg, d, xOnly, aux))
            assertTrue(Secp256k1InstanceKotlin.verifySchnorr(sig, msg, xOnly))
            assertTrue(Secp256k1InstanceKotlin.verifySchnorrFast(sig, msg, xOnly))
            val other = key()
            val peer = Secp256k1Instance.compressedPubKeyFor(other).copyOfRange(1, 33)
            assertContentEquals(Secp256k1Instance.pubKeyTweakMulCompact(peer, d), Secp256k1InstanceKotlin.pubKeyTweakMulCompact(peer, d))
            assertContentEquals(Secp256k1Instance.privateKeyAdd(d, other), Secp256k1InstanceKotlin.privateKeyAdd(d, other))
        }
    }

    @Test
    fun batchVerifyRejectsCancellingSignatures() {
        val d = key()
        val xOnly = Secp256k1Instance.compressedPubKeyFor(d).copyOfRange(1, 33)
        val msgs = List(2) { RandomInstance.bytes(32) }
        val sigs = msgs.map { Secp256k1InstanceKotlin.signSchnorr(it, d, null) }
        assertTrue(Secp256k1InstanceKotlin.verifySchnorrBatch(xOnly, sigs, msgs))
        // s1 + 1 and s2 - 1: each invalid, and they cancel in an unweighted sum
        val s1 = sigs[0].copyOf().also { it[63] = (it[63] + 1).toByte() }
        val s2 = sigs[1].copyOf().also { it[63] = (it[63] - 1).toByte() }
        if (sigs[0][63] != 0xFF.toByte() && sigs[1][63] != 0.toByte()) {
            assertFalse(Secp256k1InstanceKotlin.verifySchnorrBatch(xOnly, listOf(s1, s2), msgs))
        }
    }

    @Test
    fun invalidInputStillThrows() {
        val zero = ByteArray(32)
        assertFailsWith<IllegalArgumentException> { Secp256k1InstanceKotlin.compressedPubKeyFor(zero) }
        assertFailsWith<IllegalArgumentException> { Secp256k1InstanceKotlin.signSchnorr(ByteArray(32), zero) }
        assertFailsWith<IllegalArgumentException> { Secp256k1InstanceKotlin.pubKeyTweakMulCompact(ByteArray(31), key()) }
        assertFalse(Secp256k1InstanceKotlin.verifySchnorr(ByteArray(64), ByteArray(32), ByteArray(32)))
        assertFailsWith<IllegalArgumentException> { Secp256k1InstanceKotlin.verifySchnorrBatch(ByteArray(32), listOf(ByteArray(64)), emptyList()) }
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedSecp256k1StillWorks() {
        val d = key()
        val pub65 = Secp256k1.pubkeyCreate(d)
        assertContentEquals(Secp256k1Instance.compressedPubKeyFor(d), Secp256k1.pubKeyCompress(pub65))
        val msg = RandomInstance.bytes(32)
        val sig = Secp256k1.signSchnorr(msg, d, null)
        assertTrue(Secp256k1.verifySchnorr(sig, msg, pub65.copyOfRange(1, 33)))
        assertFailsWith<IllegalArgumentException> { Secp256k1.pubkeyCreate(ByteArray(32)) }
        // x = 5 is not on the curve (5³ + 7 is not a square mod p)
        val x5 = ByteArray(32).also { it[31] = 5 }
        assertFailsWith<IllegalArgumentException> { Secp256k1.pubKeyCompress(byteArrayOf(2) + x5) }
        assertFailsWith<IllegalArgumentException> { Secp256k1.ecdhXOnly(x5, d) }
    }
}
