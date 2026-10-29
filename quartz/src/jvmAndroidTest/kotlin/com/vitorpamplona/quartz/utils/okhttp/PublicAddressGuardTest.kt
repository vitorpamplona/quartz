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

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** No socket is opened: a stub interceptor after the guard answers whatever gets through. */
class PublicAddressGuardTest {
    private val answered = mutableListOf<String>()

    private val stub =
        Interceptor { chain ->
            answered += chain.request().url.toString()
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }

    private fun client(allowedPorts: Set<Int>? = null) =
        OkHttpClient
            .Builder()
            .publicAddressesOnly(direct = true, allowedPorts = allowedPorts)
            .addInterceptor(stub)
            .build()

    private fun get(
        url: String,
        allowedPorts: Set<Int>? = null,
    ) = client(allowedPorts).newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun ipLiteralsThatAreNotPublicAreRefused() {
        listOf(
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.1/",
            "https://[::ffff:127.0.0.1]/",
            "https://[fd00::1]/",
            // Java reads both as 127.0.0.1, and OkHttp dials them without asking the Dns.
            "https://2130706433/",
            "https://127.1/",
        ).forEach { url -> assertFailsWith<NonPublicAddressException>(url) { get(url) } }
        assertEquals(emptyList(), answered)
    }

    @Test
    fun aPublicIpLiteralGoesThrough() {
        assertEquals(200, get("https://1.1.1.1/"))
        assertEquals(listOf("https://1.1.1.1/"), answered)
    }

    @Test
    fun portsOutsideTheAllowedSetAreRefused() {
        assertFailsWith<NonPublicAddressException> { get("https://1.1.1.1:8443/", allowedPorts = setOf(443)) }
        assertEquals(200, get("https://1.1.1.1/", allowedPorts = setOf(443)))
    }

    @Test
    fun aDirectClientUsesNoProxyAndFollowsNoRedirects() {
        val c = client()
        assertEquals(Proxy.NO_PROXY, c.proxy)
        assertEquals(false, c.followRedirects)
        assertEquals(false, c.followSslRedirects)
    }

    @Test
    fun aTorClientKeepsItsProxy() {
        // Regression: the guard used to force NO_PROXY, sending a Tor client's requests around Tor.
        val tor = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val c =
            OkHttpClient
                .Builder()
                .proxy(tor)
                .publicAddressesOnly(direct = false)
                .build()
        assertEquals(tor, c.proxy)
        assertEquals(false, c.followRedirects)
        // Literals are still refused before any proxy is involved.
        val call =
            c
                .newBuilder()
                .addInterceptor(stub)
                .build()
                .newCall(Request.Builder().url("https://192.168.1.1/").build())
        assertFailsWith<NonPublicAddressException> { call.execute() }
    }

    @Test
    fun theGivenResolverIsWrappedNotReplaced() {
        val asked = mutableListOf<String>()
        val doh =
            Dns { host ->
                asked += host
                listOf(InetAddress.getByName("127.0.0.1"))
            }
        val c = OkHttpClient.Builder().publicAddressesOnly(direct = true, dns = doh).build()
        // A real call, refused at resolution: no socket is opened.
        assertFailsWith<NonPublicAddressException> { c.newCall(Request.Builder().url("https://rebind.example/").build()).execute() }
        assertEquals(listOf("rebind.example"), asked)
    }

    @Test
    fun aHostWithAnyNonPublicAddressIsRefused() {
        val fake =
            Dns { host ->
                when (host) {
                    "public.example" -> listOf(InetAddress.getByName("1.1.1.1"))
                    "mixed.example" -> listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("10.0.0.1"))
                    else -> listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
        val dns = PublicOnlyDns(fake)
        assertEquals(1, dns.lookup("public.example").size)
        assertFailsWith<NonPublicAddressException> { dns.lookup("mixed.example") }
        assertFailsWith<NonPublicAddressException> { dns.lookup("rebind.example") }
    }

    @Test
    fun relayNamesThatResolveToPrivateAddressesAreRefusedAtResolveTime() {
        // Seen in production relay lists. Both are public names, so the url classifier
        // (isDialableFromOthers) lets them through; sslip.io and nip.io answer with the address
        // spelled in the name, which this stub does without touching the network.
        val wildcardDns =
            Dns { host ->
                when (host) {
                    "192-168-0-12.sslip.io" -> listOf(InetAddress.getByName("192.168.0.12"))
                    "10.0.2.2.nip.io" -> listOf(InetAddress.getByName("10.0.2.2"))
                    else -> listOf(InetAddress.getByName("1.1.1.1"))
                }
            }
        listOf("ws://192-168-0-12.sslip.io:7447/", "ws://10.0.2.2.nip.io:7447/").forEach { relay ->
            assertEquals(true, RelayUrlNormalizer.normalizeOrNull(relay)?.isDialableFromOthers(), relay)
            // A real call, refused at resolution: no socket is opened.
            val call =
                OkHttpClient
                    .Builder()
                    .publicAddressesOnly(direct = true, dns = wildcardDns)
                    .build()
                    .newCall(Request.Builder().url(relay).build())
            assertFailsWith<NonPublicAddressException>(relay) { call.execute() }
        }
    }
}
