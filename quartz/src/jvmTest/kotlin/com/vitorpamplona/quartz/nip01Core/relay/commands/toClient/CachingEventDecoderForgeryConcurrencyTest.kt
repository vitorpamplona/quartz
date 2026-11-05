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
package com.vitorpamplona.quartz.nip01Core.relay.commands.toClient

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several relays' delivery threads send copies of a scrutinized id at once: a forged copy is
 * never handed out, the scrutiny ends once, and each relay is charged at most once.
 */
class CachingEventDecoderForgeryConcurrencyTest {
    private val signer = NostrSignerSync()

    private fun randomSig() = Random.nextBytes(64).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun frame(e: Event) = "[\"EVENT\",\"sub\",${e.toJson()}]"

    @Test
    fun concurrentCopiesOfAScrutinizedIdChargeEachRelayOnce() {
        val rogue = NormalizedRelayUrl("wss://rogue.example/")
        val badRelays = List(2) { NormalizedRelayUrl("wss://bad$it.example/") }
        val honestRelays = List(6) { NormalizedRelayUrl("wss://honest$it.example/") }
        repeat(30) { round ->
            val d = CachingEventDecoder()
            val forgeries = CopyOnWriteArrayList<CachingEventDecoder.Forgery>()
            d.onForgery = { forgeries += it }
            val genuine: Event = signer.sign(1_700_000_000L + round, 1, emptyArray(), "genuine $round")
            d.decodeFrom(frame(Event(genuine.id, genuine.pubKey, genuine.createdAt, genuine.kind, genuine.tags, "forged", genuine.sig)), rogue)
            d.reportForged((d.decodeFrom(frame(genuine), honestRelays[0]) as EventMessage).event)

            val delivered = AtomicInteger(0)
            val forgedHandedOut = AtomicInteger(0)
            val barrier = CyclicBarrier(badRelays.size + honestRelays.size)
            // 2 relays x 3 bad copies, plus the reported one: under the per-id cap, so it resolves.
            val workers =
                badRelays.map { relay ->
                    thread {
                        barrier.await()
                        repeat(3) { k ->
                            val bad = if (k % 2 == 0) Event(genuine.id, genuine.pubKey, genuine.createdAt, genuine.kind, genuine.tags, "forged $k", genuine.sig) else Event(genuine.id, genuine.pubKey, genuine.createdAt, genuine.kind, genuine.tags, genuine.content, randomSig())
                            val got = (d.decodeFrom(frame(bad), relay) as EventMessage?)?.event
                            if (got != null && !got.verify()) forgedHandedOut.incrementAndGet()
                        }
                    }
                } +
                    honestRelays.map { relay ->
                        thread {
                            barrier.await()
                            repeat(50) {
                                val got = (d.decodeFrom(frame(genuine), relay) as EventMessage?)?.event
                                if (got != null) {
                                    if (got.verify()) delivered.incrementAndGet() else forgedHandedOut.incrementAndGet()
                                }
                            }
                        }
                    }
            workers.forEach { it.join() }

            assertEquals(0, forgedHandedOut.get(), "round $round: a forged copy was handed out")
            assertTrue(delivered.get() > 0, "round $round: the genuine copy never came through")
            val charges = forgeries.groupingBy { it.relay }.eachCount()
            assertEquals(1, charges[rogue], "round $round: the reported relay is charged exactly once")
            assertTrue(charges.values.all { it == 1 }, "round $round: a relay charged twice: $charges")
            assertTrue(honestRelays.none { it in charges }, "round $round: an honest relay was charged: $charges")
            charges.keys.forEach { assertEquals(1, d.record(it).let { r -> r.badIds + r.badSigs }, "round $round: ${it.url}") }
        }
    }
}
