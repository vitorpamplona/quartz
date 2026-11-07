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

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import fr.acinq.secp256k1.Secp256k1 as NativeSecp256k1

/**
 * Bytes allocated per secp256k1 operation after JIT warm-up, printed, with a budget for the paths
 * that must not allocate at all (verify, field multiply, inversion): GC pauses on Android come
 * from exactly this kind of per-call garbage.
 *
 * Also worth running with -XX:-DoEscapeAnalysis, which is closer to Android: ART does not remove
 * short-lived allocations by escape analysis. On 2026-11-07 both runs gave the same numbers.
 * Each figure includes ~16 bytes of measurement overhead (the boxed loop index passed to [op]).
 */
class Secp256k1AllocationTest {
    private val mx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val tid = Thread.currentThread().id

    private fun bytesPerOp(
        name: String,
        n: Int,
        op: (Int) -> Unit,
    ): Double {
        repeat(20_000) { op(it % n) } // warm up to C2
        val before = mx.getThreadAllocatedBytes(tid)
        val reps = 5_000
        for (i in 0 until reps) op(i % n)
        val per = (mx.getThreadAllocatedBytes(tid) - before).toDouble() / reps
        println("ALLOC %-40s %8.0f bytes/op".format(name, per))
        return per
    }

    /** Allocation-free paths: the measurement overhead only. */
    private fun assertNoAllocation(
        name: String,
        perOp: Double,
    ) = assertTrue(perOp < 32.0, "$name allocates $perOp bytes per call; it should allocate nothing")

    @Test
    fun bytesPerOperation() {
        val r = Random(99)

        fun key(): ByteArray {
            while (true) {
                val k = r.nextBytes(32)
                if (Secp256k1.secKeyVerify(k)) return k
            }
        }
        val n = 4096 // more distinct pubkeys than the 1024-entry caches hold
        val secs = List(n) { key() }
        val pubs = secs.map { Secp256k1.pubkeyCreate(it).copyOfRange(1, 33) }
        val msgs = List(n) { r.nextBytes(32) }
        val aux = r.nextBytes(32)
        val sigs = List(n) { Secp256k1.signSchnorr(msgs[it], secs[it], aux) }
        val acinq = NativeSecp256k1.get()

        assertNoAllocation("verifySchnorr, same pubkey", bytesPerOp("verifySchnorr, same pubkey", n) { Secp256k1.verifySchnorr(sigs[0], msgs[0], pubs[0]) })
        bytesPerOp("verifySchnorr, 4096 pubkeys (cache misses)", n) { Secp256k1.verifySchnorr(sigs[it], msgs[it], pubs[it]) }
        assertNoAllocation("verifySchnorrFast, same pubkey", bytesPerOp("verifySchnorrFast, same pubkey", n) { Secp256k1.verifySchnorrFast(sigs[0], msgs[0], pubs[0]) })
        bytesPerOp("signSchnorr", n) { Secp256k1.signSchnorr(msgs[it], secs[it], aux) }
        bytesPerOp("signSchnorrWithXOnlyPubKey", n) { Secp256k1.signSchnorrWithXOnlyPubKey(msgs[it], secs[it], pubs[it], aux) }
        bytesPerOp("pubkeyCreate", n) { Secp256k1.pubkeyCreate(secs[it]) }
        bytesPerOp("ecdhXOnly", n) { Secp256k1.ecdhXOnly(pubs[(it + 1) % n], secs[it]) }
        val compressed = secs.take(64).map { Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(it)) }
        bytesPerOp("pubKeyTweakMul", 64) { Secp256k1.pubKeyTweakMul(compressed[it], secs[it]) }
        bytesPerOp("privKeyTweakAdd", n) { Secp256k1.privKeyTweakAdd(secs[it], secs[(it + 1) % n]) }
        val bm = List(32) { r.nextBytes(32) }
        val bs = List(32) { Secp256k1.signSchnorr(bm[it], secs[0], aux) }
        bytesPerOp("verifySchnorrBatch(32), per call", 1) { Secp256k1.verifySchnorrBatch(pubs[0], bs, bm) }
        val fe = List(n) { Fe4(r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong() ushr 1) }
        val out = Fe4()
        assertNoAllocation("FieldP.inv", bytesPerOp("FieldP.inv", n) { FieldP.inv(out, fe[it]) })
        assertNoAllocation("FieldP.mul", bytesPerOp("FieldP.mul", n) { FieldP.mul(out, fe[it], fe[(it + 1) % n]) })
        bytesPerOp("ACINQ verifySchnorr", n) { acinq.verifySchnorr(sigs[it], msgs[it], pubs[it]) }
        bytesPerOp("ACINQ signSchnorr", n) { acinq.signSchnorr(msgs[it], secs[it], aux) }
        bytesPerOp("ACINQ pubkeyCreate", n) { acinq.pubkeyCreate(secs[it]) }
    }
}
