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
import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import com.vitorpamplona.quartz.nip01Core.kite.KiteWriter
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Secp256k1Instance
import com.vitorpamplona.quartz.utils.secp256k1.Secp256k1
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EXPERIMENT: where the serial verify path spends its time, per corpus event. Runs only with KITE_CORPUS set. */
class SerialVerifyBreakdownBench {
    private inline fun time(
        label: String,
        events: Int,
        rounds: Int,
        body: () -> Long,
    ) {
        var sink = 0L
        repeat(maxOf(1, rounds / 2)) { sink += body() }
        val t0 = System.nanoTime()
        repeat(rounds) { sink += body() }
        val ns = (System.nanoTime() - t0).toDouble() / rounds / events
        println("BREAKDOWN %-46s %9.2f us/event  (%d)".format(label, ns / 1000, sink and 1))
    }

    @Test
    fun breakdown() {
        val path = System.getenv("KITE_CORPUS") ?: return
        val texts = File(path).readLines().filter { it.isNotBlank() }
        val events: List<Event> = texts.map { KiteReader.readEvent(it) }.filter { it.verify() }
        val n = events.size
        val preimages = events.map { KiteWriter.idPreimageBytes(it.pubKey, it.createdAt, it.kind, it.tags, it.content) }
        val ids = events.map { Hex.decode(it.id) }
        val sigs = events.map { Hex.decode(it.sig) }
        val pubs = events.map { Hex.decode(it.pubKey) }
        val frames = texts.map { "[\"EVENT\",\"s\",$it]" }
        val md = MessageDigest.getInstance("SHA-256")
        val out = ByteArray(32)
        println("BREAKDOWN $n valid events, preimage avg ${preimages.sumOf { it.size } / n} bytes")
        for (pass in 1..2) {
            println("BREAKDOWN pass $pass")
            time("Kite parse (frame String given)", frames.size, 20) {
                var s = 0L
                for (f in frames) s += KiteReader.readMessage(f).hashCode()
                s
            }
            time("id preimage serialize (copy out)", n, 20) {
                var s = 0L
                for (e in events) s += KiteWriter.idPreimageBytes(e.pubKey, e.createdAt, e.kind, e.tags, e.content).size
                s
            }
            time("SHA-256 of preimage (MessageDigest)", n, 20) {
                var s = 0L
                for (p in preimages) {
                    md.update(p)
                    md.digest(out, 0, 32)
                    s += out[0]
                }
                s
            }
            time("idHashInto (serialize + SHA-256)", n, 20) {
                var s = 0L
                for (e in events) {
                    KiteWriter.idHashInto(e.pubKey, e.createdAt, e.kind, e.tags, e.content, out)
                    s += out[0]
                }
                s
            }
            time("3 hex decodes", n, 20) {
                var s = 0L
                for (e in events) s += Hex.decode(e.sig)[0] + Hex.decode(e.id)[0] + Hex.decode(e.pubKey)[0]
                s
            }
            time("Schnorr: ACINQ libsecp256k1 (JNI)", n, 3) {
                var s = 0L
                for (i in 0 until n) if (Secp256k1Instance.verifySchnorr(sigs[i], ids[i], pubs[i])) s++
                s
            }
            time("Schnorr: Quartz Kotlin (strict BIP-340)", n, 3) {
                var s = 0L
                for (i in 0 until n) if (Secp256k1.verifySchnorr(sigs[i], ids[i], pubs[i])) s++
                s
            }
            time("Schnorr: Quartz Kotlin fast (no y parity)", n, 3) {
                var s = 0L
                for (i in 0 until n) if (Secp256k1.verifySchnorrFast(sigs[i], ids[i], pubs[i])) s++
                s
            }
            time("Event.verify() (all of it)", n, 3) {
                var s = 0L
                for (e in events) if (e.verify()) s++
                s
            }
        }
    }

    /** The unweighted batch check accepts two invalid signatures whose errors cancel. */
    @Test
    fun unweightedBatchAcceptsInvalidSignatures() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        val pub = Secp256k1.pubkeyCreate(priv).copyOfRange(1, 33)
        val m1 = ByteArray(32) { 1 }
        val m2 = ByteArray(32) { 2 }
        val s1 = Secp256k1.signSchnorr(m1, priv, null)
        val s2 = Secp256k1.signSchnorr(m2, priv, null)
        val order = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

        fun shift(
            sig: ByteArray,
            d: Long,
        ): ByteArray {
            val s = BigInteger(1, sig.copyOfRange(32, 64)).add(BigInteger.valueOf(d)).mod(order)
            val b = s.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
            return sig.copyOfRange(0, 32) + b
        }
        val bad1 = shift(s1, 1)
        val bad2 = shift(s2, -1)
        assertFalse(Secp256k1.verifySchnorr(bad1, m1, pub))
        assertFalse(Secp256k1.verifySchnorr(bad2, m2, pub))
        assertTrue(Secp256k1.verifySchnorrBatch(pub, listOf(bad1, bad2), listOf(m1, m2)), "batch accepted two invalid signatures")
    }
}
