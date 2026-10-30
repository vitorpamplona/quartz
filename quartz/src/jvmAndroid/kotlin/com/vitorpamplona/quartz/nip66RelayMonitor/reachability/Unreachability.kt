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
package com.vitorpamplona.quartz.nip66RelayMonitor.reachability

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Whether a failure may be published as "this relay is unreachable".
 *
 * The distinction matters because the answer is PUBLISHED: a negative NIP-66
 * record is a signed, public statement about someone else's server. A relay
 * that completes a handshake and then hangs up mid-page is emphatically
 * reachable, and an exception thrown by the caller's own code says nothing
 * about the relay at all. So this asks only about the connection itself —
 * name resolution, routing, refusal, TLS — and, within those, only failures
 * whose words put the fault on the relay's side: see [ourSide].
 *
 * Unknown failures stay quiet: the cost of silence is one retry next cycle,
 * the cost of being wrong is a false record carrying the monitor's signature.
 */
object Unreachability {
    fun proves(e: Exception): Boolean =
        when {
            ourSide(e) -> false

            // Only a resolver that answered "no such name" is evidence. A temporary failure
            // (EAI_AGAIN) is our resolver, and a bare host name is the JVM's negative lookup
            // cache replaying an earlier failure without its reason.
            e is UnknownHostException -> saysNoSuchName(e.message)

            e is ConnectException ||
                e is NoRouteToHostException ||
                e is PortUnreachableException ||
                e is SSLHandshakeException -> true

            else -> false
        }

    /**
     * True when [e], or any exception in its cause chain, says the failure is on THIS machine:
     * a resolver that could not answer (EAI_AGAIN, "try again") or no route out of our own
     * network. Such a failure hits every relay at once, so it is the monitor's transport that is
     * down, never evidence about the relay. The chain matters because OkHttp rethrows a dial
     * failure as `Failed to connect to <address>` with the socket's own exception as the cause.
     *
     * On Android an offline device also reports a lookup as "No address associated with
     * hostname" (EAI_NODATA), which [proves] reads as a real answer; a monitor on a phone should
     * check connectivity before publishing.
     */
    fun ourSide(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val said = current.message
            if (said != null && OUR_SIDE.any { said.contains(it, ignoreCase = true) }) return true
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return false
    }

    private fun saysNoSuchName(message: String?): Boolean = message != null && NO_SUCH_NAME.any { message.contains(it, ignoreCase = true) }

    private const val MAX_CAUSE_DEPTH = 8

    private val OUR_SIDE = listOf("temporary failure in name resolution", "try again", "network is unreachable")

    /** glibc's, macOS's and Windows' words for a name the resolver knows does not exist. */
    private val NO_SUCH_NAME = listOf("name or service not known", "no address associated with hostname", "nodename nor servname provided", "no such host is known")
}
