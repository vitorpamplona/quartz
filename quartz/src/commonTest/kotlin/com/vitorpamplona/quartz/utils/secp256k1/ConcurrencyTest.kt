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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sign, verify and ECDH from many threads at once must give the single-threaded answers.
 *
 * The secp256k1 code works in per-thread scratch buffers (ScratchLocal). If two threads ever
 * share one, they overwrite each other's intermediate field elements mid-operation and the
 * results come out wrong (or, for signing, secret-dependent state crosses threads).
 */
class ConcurrencyTest {
    private class Case(
        val seckey: ByteArray,
        val xOnly: ByteArray,
        val msg: ByteArray,
        val aux: ByteArray,
        val sig: ByteArray,
        val peerXOnly: ByteArray,
        val shared: ByteArray,
    )

    private fun cases(n: Int): List<Case> {
        val r = Random(0x5EC9)

        fun seckey(): ByteArray {
            while (true) {
                val k = r.nextBytes(32)
                if (Secp256k1.secKeyVerify(k)) return k
            }
        }
        return List(n) {
            val sk = seckey()
            val xOnly = Secp256k1.pubkeyCreate(sk).copyOfRange(1, 33)
            val msg = r.nextBytes(32)
            val aux = r.nextBytes(32)
            val peer = Secp256k1.pubkeyCreate(seckey()).copyOfRange(1, 33)
            Case(sk, xOnly, msg, aux, Secp256k1.signSchnorr(msg, sk, aux), peer, Secp256k1.ecdhXOnly(peer, sk))
        }
    }

    @Test
    fun parallelOperationsMatchSequentialResults() =
        runTest {
            val cases = cases(48)
            val threads = 8
            val rounds = 6
            val failures =
                withContext(Dispatchers.Default) {
                    (0 until threads)
                        .map { t ->
                            async {
                                var bad = 0
                                repeat(rounds) { round ->
                                    for (i in cases.indices) {
                                        val c = cases[(i + t * 7 + round) % cases.size]
                                        if (!Secp256k1.verifySchnorr(c.sig, c.msg, c.xOnly)) bad++
                                        val tampered = c.sig.copyOf().also { it[63] = (it[63] + 1).toByte() }
                                        if (Secp256k1.verifySchnorr(tampered, c.msg, c.xOnly)) bad++
                                        if (!Secp256k1.signSchnorr(c.msg, c.seckey, c.aux).contentEquals(c.sig)) bad++
                                        if (!Secp256k1.ecdhXOnly(c.peerXOnly, c.seckey).contentEquals(c.shared)) bad++
                                    }
                                }
                                bad
                            }
                        }.awaitAll()
                }
            assertEquals(List(threads) { 0 }, failures, "wrong results per thread under concurrency")
            // And the single-threaded path still agrees afterwards.
            for (c in cases) {
                assertTrue(Secp256k1.verifySchnorr(c.sig, c.msg, c.xOnly))
                assertContentEquals(c.sig, Secp256k1.signSchnorr(c.msg, c.seckey, c.aux))
            }
        }
}
