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
package com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedDnsTest {
    @Test
    fun `a burst of lookups never has more than the permits in flight and every one is answered`() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val slow =
            Dns { host ->
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(5)
                inFlight.decrementAndGet()
                listOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 1)))
            }
        val dns = BoundedDns(slow, permits = 8)
        val pool = Executors.newFixedThreadPool(200)
        val answered = AtomicInteger()
        val done = CountDownLatch(1_000)
        repeat(1_000) { i ->
            pool.execute {
                if (dns.lookup("relay$i.example").isNotEmpty()) answered.incrementAndGet()
                done.countDown()
            }
        }
        done.await(60, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(1_000, answered.get())
        assertEquals(8, peak.get())
    }
}
