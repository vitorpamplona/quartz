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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 6455 section 3: `ws` defaults to port 80 and `wss` to 443, so `wss://relay/` and
 * `wss://relay:443/` are one relay written two ways. RFC 3986 section 6.2.3 says a normalizer
 * drops a port equal to the scheme's default. Keeping it made the two spellings two
 * [com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl]s: two connections
 * in the pool, two NIP-66 `d` tags, two AUTH relay urls that failed to match each other.
 */
class RelayUrlDefaultPortTest {
    @Test
    fun theSchemeDefaultPortIsDropped() {
        assertEquals("wss://relay.example.com/", "wss://relay.example.com:443".normalizeRelayUrl().url)
        assertEquals("wss://relay.example.com/", "wss://relay.example.com:443/".normalizeRelayUrl().url)
        assertEquals("wss://relay.example.com/path", "wss://relay.example.com:443/path".normalizeRelayUrl().url)
        assertEquals("ws://relay.example.com/", "ws://relay.example.com:80/".normalizeRelayUrl().url)
        assertEquals("wss://relay.example.com/?q=1", "wss://Relay.Example.COM:443/?q=1".normalizeRelayUrl().url)
    }

    @Test
    fun theOtherSchemesDefaultIsAPortLikeAnyOther() {
        // 80 is not wss's default, nor 443 ws's: those name a different socket.
        assertEquals("wss://relay.example.com:80/", "wss://relay.example.com:80".normalizeRelayUrl().url)
        assertEquals("ws://relay.example.com:443/", "ws://relay.example.com:443".normalizeRelayUrl().url)
        assertEquals("wss://relay.example.com:4430/", "wss://relay.example.com:4430".normalizeRelayUrl().url)
        assertEquals("wss://relay.example.com:8443/", "wss://relay.example.com:8443".normalizeRelayUrl().url)
    }

    @Test
    fun anHttpUrlLosesItsDefaultPortOnConversion() {
        assertEquals("wss://relay.example.com/", "https://relay.example.com:443/".normalizeRelayUrl().url)
        assertEquals("ws://relay.example.com/", "http://relay.example.com:80".normalizeRelayUrl().url)
        // https on 80 becomes wss on 80, which is not wss's default.
        assertEquals("wss://relay.example.com:80/", "https://relay.example.com:80".normalizeRelayUrl().url)
    }

    @Test
    fun anIpv6LiteralKeepsItsColonsAndLosesOnlyTheDefaultPort() {
        assertEquals("wss://[2001:db8::1]/", "wss://[2001:db8::1]:443".normalizeRelayUrl().url)
        assertEquals("ws://[200:1234::1]/", "ws://[200:1234::1]:80/".normalizeRelayUrl().url)
        assertEquals("wss://[2001:db8::1]:7777/", "wss://[2001:db8::1]:7777".normalizeRelayUrl().url)
        // The address's last group is 443, not a port: nothing to drop.
        assertEquals("wss://[2001:db8::443]/", "wss://[2001:db8::443]".normalizeRelayUrl().url)
    }

    @Test
    fun bothSpellingsAreTheSameRelay() {
        listOf(
            "wss://nos.lol" to "wss://nos.lol:443/",
            "ws://127.0.0.1" to "ws://127.0.0.1:80",
            "abc.onion" to "ws://abc.onion:80/",
            "wss://[2001:db8::1]/x" to "wss://[2001:db8::1]:443/x",
        ).forEach { (a, b) ->
            assertEquals(a.normalizeRelayUrl(), b.normalizeRelayUrl(), "$a vs $b")
        }
    }

    @Test
    fun theCanonicalFormIsAFixedPoint() {
        listOf("wss://relay.example.com:443", "ws://[2001:db8::1]:80/x").forEach { raw ->
            val once = raw.normalizeRelayUrl()
            RelayUrlNormalizer.clearCaches()
            assertEquals(once, once.url.normalizeRelayUrl(), raw)
        }
    }
}
