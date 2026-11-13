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
package com.vitorpamplona.quartz.routstr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.routstr.node.RoutstrEndpoint
import com.vitorpamplona.quartz.routstr.node.RoutstrNodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every kind-38421 announcement live on relay.routstr.com, relay.damus.io and nos.lol on
 * 2026-11-13 (`resources/routstr/38421.json`, 82 events from 79 addresses), read through the
 * typed class.
 */
class RoutstrLiveAnnouncementsTest {
    private val nodes: List<RoutstrNodeEvent> by lazy {
        val text = javaClass.getResourceAsStream("/routstr/38421.json")!!.bufferedReader().use { it.readText() }
        (Json.parseToJsonElement(text) as JsonArray).map { Event.fromJson(it.toString()) as RoutstrNodeEvent }
    }

    @Test
    fun everyLiveAnnouncementParsesAndVerifies() {
        assertEquals(82, nodes.size)
        assertTrue(nodes.all { it.kind == RoutstrNodeEvent.KIND })
        assertEquals(emptyList<String>(), nodes.filterNot { it.verify() }.map { it.id })
        // Every accessor reads every event without throwing.
        nodes.forEach {
            it.endpoints()
            it.mints()
            it.version()
            it.models()
            it.topics()
            it.name()
            it.about()
            it.indexableContent()
        }
    }

    @Test
    fun everyAnnouncementHasAnIdAndAnEndpoint() {
        assertTrue(nodes.all { !it.dTag().isBlank() })
        assertTrue(nodes.all { it.endpoints().isNotEmpty() })
        assertEquals(110, nodes.sumOf { it.endpoints().size })
        assertEquals(78, nodes.count { it.version() != null })
        assertEquals(1, nodes.count { it.models().isNotEmpty() })
    }

    @Test
    fun everyNodeNamesItselfInItsContent() {
        // One node, on the FIPS mesh, writes `about` before `name` and adds fields of its own.
        assertEquals(82, nodes.count { it.name() != null })
    }

    @Test
    fun onlyHttpsAndTorOnionsAreUsable() {
        val all = nodes.flatMap { it.endpoints() }
        val usableWithTor = nodes.flatMap { it.usableEndpoints(torEnabled = true) }
        val usableClearnet = nodes.flatMap { it.usableEndpoints(torEnabled = false) }

        assertTrue(usableClearnet.all { it.url.startsWith("https://") && it.type == RoutstrEndpoint.Type.HTTPS })
        assertTrue(usableWithTor.all { it.url.startsWith("https://") || it.url.endsWith(".onion") })

        val other = all.filter { it.type == RoutstrEndpoint.Type.OTHER }.map { it.url }.toSet()
        // Plain http to clearnet or LAN, loopback, an IP-literal https host, FIPS names, a junk onion.
        assertEquals(
            setOf(
                "http://100.90.84.147:8011",
                "http://127.0.0.1:19003",
                "http://127.0.0.1:8010",
                "http://99.68.36.178:8000",
                "http://localchimera.com:8000",
                "http://npub15eparr6gjryvprzs20xnvv78mmj4p38439tsu3u535mr4ehgj6ksy0z6fx.fips:5679/routstr",
                "http://npub1pxd3pll90t2n8hd6ntcpx27wzr9yws4hspzfxfhg5329qkjnx2aqfmya5a.fips:28080",
                "http://paygate:8000",
                "http://test.onion",
                "http://warus.hldns.ru:8000",
                "https://bartly.eth64.de:17881",
                "https://140.245.192.194",
            ),
            other,
        )
        assertTrue(nodes.count { it.usableEndpoints(torEnabled = false).isNotEmpty() } > 50)
    }
}
