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
package com.vitorpamplona.quartz.utils.okhttp

import com.vitorpamplona.quartz.utils.PublicAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/**
 * Keeps this client off every address that isn't public (see [PublicAddress]), for fetching
 * URLs that come from other people's events: NIP-05 domains, lightning addresses, media. A server
 * doing that without a guard can be pointed at its own cloud metadata service or internal
 * network; a client, at the user's router.
 *
 * - Host names resolve through [PublicOnlyDns] over [dns], and OkHttp connects to exactly the
 *   addresses it returns, so a host can't pass the check and then rebind to an internal address.
 *   The builder's own resolver can't be read back, so pass it as [dns] to keep it (DoH, say).
 * - IP-literal hosts, which OkHttp never sends to a [Dns], are checked by [PublicOnlyInterceptor],
 *   as are the ports in [allowedPorts] when given (`setOf(443)` for an https-only fetcher).
 * - No redirects: OkHttp follows them past application interceptors, so a redirect to an IP
 *   literal would go unchecked. NIP-05 forbids following them anyway.
 *
 * [direct] is required because no default is safe for both kinds of caller. Through a proxy,
 * OkHttp resolves only the proxy and the proxy resolves the target, so host names go unchecked
 * here; only IP literals are still refused.
 * - A server fencing off its own network passes `true`: no proxy, every host name checked. The
 *   JVM's proxy settings would otherwise apply silently.
 * - A client behind Tor (a SOCKS proxy) passes `false`: `true` would send the request around
 *   Tor and reveal the user's address. Host names then resolve at the exit, on its network,
 *   not the user's.
 *
 * A refused request fails with [NonPublicAddressException].
 */
fun OkHttpClient.Builder.publicAddressesOnly(
    direct: Boolean,
    allowedPorts: Set<Int>? = null,
    dns: Dns = Dns.SYSTEM,
): OkHttpClient.Builder {
    if (direct) proxy(Proxy.NO_PROXY)
    return dns(PublicOnlyDns(dns))
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(PublicOnlyInterceptor(allowedPorts))
}

/** A request refused because its host is, or resolves to, an address that isn't public. */
class NonPublicAddressException(
    message: String,
) : UnknownHostException(message)

/** Resolves a host only when every address it has is public; one internal address refuses all. */
class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.any { !PublicAddress.isPublic(it.address) }) {
            throw NonPublicAddressException("$hostname resolves to an address that isn't public")
        }
        return addresses
    }
}

/** Refuses IP-literal hosts that aren't public, and ports outside [allowedPorts] when given. */
class PublicOnlyInterceptor(
    private val allowedPorts: Set<Int>? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (allowedPorts != null && url.port !in allowedPorts) {
            throw NonPublicAddressException("port ${url.port} is not allowed")
        }
        literalAddress(url.host)?.let { literal ->
            if (literal.isEmpty() || !PublicAddress.isPublic(literal)) {
                throw NonPublicAddressException("${url.host} is not a public address")
            }
        }
        return chain.proceed(chain.request())
    }

    private companion object {
        val DIGITS_AND_DOTS = Regex("[0-9.]+")

        /**
         * The address an IP-literal host names, empty when it is a malformed literal, or null for
         * a host name. "Literal" is what OkHttp treats as one and so never resolves: anything
         * with a colon, or only digits and dots, which `2130706433` and `127.1` (both
         * 127.0.0.1 to Java) also are.
         */
        fun literalAddress(host: String): ByteArray? {
            if (':' !in host && !DIGITS_AND_DOTS.matches(host)) return null
            return try {
                InetAddress.getByName(host).address
            } catch (e: UnknownHostException) {
                ByteArray(0)
            }
        }
    }
}
