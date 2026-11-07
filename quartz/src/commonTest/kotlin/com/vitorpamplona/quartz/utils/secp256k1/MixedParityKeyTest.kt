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
import kotlin.test.assertTrue

/**
 * P and -P share an x coordinate. Verify works on the even-y point lifted from an x-only key, while
 * tweak-mul works on whatever point it is given: a per-x cache shared by the two would hand one of
 * them the other's tables (libschnorr256k1 has this bug). Interleave them on odd-y keys.
 */
class MixedParityKeyTest {
    @Test
    fun verifyAndTweakMulOnBothPointsOfOneX() {
        val r = Random(0x0DD)
        var n = 0
        while (n < 50) {
            val sk = r.nextBytes(32)
            if (!Secp256k1.secKeyVerify(sk)) continue
            val pub = Secp256k1.pubkeyCreate(sk)
            if ((pub[64].toInt() and 1) == 0) continue
            val tweak = r.nextBytes(32)
            if (!Secp256k1.secKeyVerify(tweak)) continue
            n++
            val xOnly = Secp256k1.pubKeyCompress(pub).copyOfRange(1, 33)
            val msg = r.nextBytes(32)
            val sig = Secp256k1.signSchnorr(msg, sk, null)

            val tweaked = Secp256k1.pubKeyTweakMul(pub, tweak)
            assertTrue(Secp256k1.verifySchnorr(sig, msg, xOnly), "verify after tweak-mul of the odd-y key")
            assertContentEquals(tweaked, Secp256k1.pubKeyTweakMul(pub, tweak), "tweak-mul after verify")
            val product = Secp256k1.pubkeyCreate(U256.toBytes(ScalarN.mul(U256.fromBytes(sk), U256.fromBytes(tweak))))
            assertContentEquals(product, tweaked, "tweak*P == (sk*tweak)*G")
        }
    }
}
