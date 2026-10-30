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
import java.net.InetAddress
import java.util.concurrent.Semaphore

/**
 * [delegate], asked at most [permits] names at once. Dialling a whole relay pool at once (a cold
 * start, a network change) hands the resolver one lookup per host in the same instant, and a
 * resolver given ~1,300 together drops a quarter of them where the same names 128 at a time all
 * resolve, in the same wall time. Callers past the limit wait their turn instead.
 */
class BoundedDns(
    private val delegate: Dns = Dns.SYSTEM,
    permits: Int = DEFAULT_PERMITS,
) : Dns {
    private val gate = Semaphore(permits)

    override fun lookup(hostname: String): List<InetAddress> {
        gate.acquire()
        try {
            return delegate.lookup(hostname)
        } finally {
            gate.release()
        }
    }

    companion object {
        const val DEFAULT_PERMITS = 64
    }
}
