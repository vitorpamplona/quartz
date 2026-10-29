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
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isLocalHost
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isOnion
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isOverlayNetwork
import com.vitorpamplona.quartz.nip01Core.tags.references.HttpUrlFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [RelayUrlNormalizer.isLocalHost] and the policy built on it,
 * [com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers], over the relay
 * urls a production monitor found in kind 10002/10009/10040 lists. The ones marked "seen" are
 * verbatim from that corpus.
 */
class RelayLocalHostClassifierTest {
    private fun norm(url: String) = assertNotNull(RelayUrlNormalizer.normalizeOrNull(url), "$url must normalize")

    /** Local or private: the user's own relay is fine, a stranger's is never dialed. */
    private val local =
        listOf(
            // 100.64.0.0/10, the Tailscale/CGNAT range, both ends
            "ws://100.100.10.10:4848/", // seen
            "ws://100.64.0.1/",
            "ws://100.127.255.254/",
            // loopback name, with a path, with a trailing dot
            "ws://localhost/",
            "ws://localhost/relay", // seen
            // loopback IPv6 written non-canonically
            "ws://[0:0::1]:7777/path", // seen
            // IPv4-mapped and IPv4-compatible IPv6
            "ws://[::ffff:192.168.1.5]/",
            "ws://[::ffff:7f00:1]/",
            "ws://[::127.0.0.1]/",
            // mDNS
            "ws://atari.local:4848/", // seen
            // local-only suffixes
            "ws://calavera.lan:7777/", // seen
            "ws://apollo.home:3355/", // seen
            "ws://host.docker.internal:7777/", // seen
            "ws://router.home.arpa/",
            "ws://wiki.intranet/",
            // single-label LAN hostnames
            "ws://beelink:7777/", // seen
            "ws://chestnut:3300/", // seen
            "ws://artshop:4848/", // seen
            // onion-looking, but .local: local, not onion
            "ws://6amyhf3sjvgxe5qzbx4xn52pcnqresdmi7szxurp6umkvz6mthxjdcad.local/", // seen
            // the classic private ranges
            "ws://127.0.0.1:7777/",
            "ws://10.0.0.5/",
            "ws://172.16.3.4/",
            "ws://192.168.1.10:4848/",
            "ws://169.254.169.254/",
            "ws://0.0.0.0/",
            "ws://[fd12:3456::1]:8080/",
            "ws://[fe80::1]/",
        )

    /** Public: both questions answer "not local" / "dialable". */
    private val public =
        listOf(
            // registrable domain; anchoring, not substring
            "wss://localhost.me/",
            // the cases the isLocalHost KDoc names
            "wss://evil.example.com/127.0.0.1",
            "wss://192.168.evil.com/",
            // names that start like hex IPs
            "wss://0x-nostr-relay.fly.dev/", // seen
            "wss://0x10.host/", // seen
            // Yggdrasil overlay: reachable from anywhere on the mesh
            "ws://[201:166d:b24c:9a59:90b5:9149:656e:3b5c]/", // seen
            // public IPv6
            "ws://[2a00:6020:a79f:8700:5463:5336:54bb:961e]:4848/", // seen
            // a ts.net name can be public through Tailscale Funnel; only the 100.64/10 address never is
            "wss://aloha-volcano.fable-ilish.ts.net:4848/", // seen
            "wss://nos.lol/",
            "wss://relay.damus.io/",
            "wss://8.8.8.8/",
        )

    @Test
    fun localAndPrivateHostsAreLocal() {
        local.forEach { assertTrue(norm(it).isLocalHost(), "$it must be local") }
    }

    @Test
    fun ipv4ShorthandSpellingsAreLocalWhetherOrNotTheyNormalize() {
        // inet_aton, and so the resolver under OkHttp, reads every one of them as 127.0.0.1.
        listOf("ws://2130706433/", "ws://0x7f000001/", "ws://127.1/", "ws://0x7f.0.0.1/", "ws://0177.0.0.1/", "ws://0xc0.0xa8.1.1/")
            .forEach {
                val url = RelayUrlNormalizer.normalizeOrNull(it)?.url ?: it
                assertTrue(RelayUrlNormalizer.isLocalHost(url), "$it must be local")
                assertFalse(RelayUrlNormalizer.isDialableFromOthers(url), "$it must not be dialed")
            }
    }

    @Test
    fun hostsTheNormalizerRefusesAreStillLocal() {
        // The normalizer refuses a trailing dot and an underscore in the host, so ask the
        // classifier directly: it is what fix() and the Tor exemption call on raw input.
        listOf(
            "ws://localhost.:7777/",
            "ws://nas.lan.:7777/",
            "ws://_relay._nostr._http._tcp.local:25174/", // seen: a DNS-SD service name
        ).forEach {
            assertTrue(RelayUrlNormalizer.isLocalHost(it), it)
            assertFalse(RelayUrlNormalizer.isDialableFromOthers(it), it)
        }
    }

    @Test
    fun publicHostsAreNotLocal() {
        public.forEach { assertFalse(norm(it).isLocalHost(), "$it must not be local") }
    }

    @Test
    fun theYggdrasilAddressIsAnOverlayNotALocalHost() {
        assertTrue(norm("ws://[201:166d:b24c:9a59:90b5:9149:656e:3b5c]/").isOverlayNetwork())
    }

    @Test
    fun anOnionLookingDotLocalNameIsNotOnion() {
        assertFalse(norm("ws://6amyhf3sjvgxe5qzbx4xn52pcnqresdmi7szxurp6umkvz6mthxjdcad.local/").isOnion())
    }

    @Test
    fun othersLocalRelaysAreNeverDialable() {
        local.forEach { assertFalse(norm(it).isDialableFromOthers(), "$it from someone else must not be dialed") }
    }

    @Test
    fun othersPublicRelaysAreDialable() {
        public.forEach { assertTrue(norm(it).isDialableFromOthers(), "$it must be dialable") }
        // Onion stays the app's Tor flag, not this helper's.
        assertTrue(norm("ws://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvw.onion/").isDialableFromOthers())
    }

    @Test
    fun aSchemePastedTwiceIsRefusedNotRepaired() {
        mapOf(
            "wss://wss/nos.lol" to "wss://wss/nos.lol", // seen
            "wss://https/nostr.noderunners.network" to "wss://https/nostr.noderunners.network", // seen
            "wss://relay/jp.shino3.net" to "wss://relay/jp.shino3.net", // seen
        ).forEach { (input, expected) ->
            val relay = norm(input)
            // The normalizer keeps the url as written: the host is the scheme word.
            assertEquals(expected, relay.url)
            assertFalse(relay.isDialableFromOthers(), "$input must not be dialed")
        }
    }

    @Test
    fun anEmptyLabelIsNotAHost() {
        assertFalse(RelayUrlNormalizer.isDialableFromOthers("ws://.tailed9020.ts.net/")) // seen
        assertFalse(RelayUrlNormalizer.isDialableFromOthers("wss://relay..example.com/"))
        assertFalse(RelayUrlNormalizer.isDialableFromOthers("wss:///"))
    }

    @Test
    fun publicIpLiteralsOutsideTheInternetAreNotDialable() {
        // Not local, but not a relay anyone can host either: multicast, documentation.
        listOf("ws://224.0.0.1/", "ws://203.0.113.5/", "ws://[2001:db8::1]/", "ws://[ff02::1]/").forEach {
            assertFalse(RelayUrlNormalizer.isDialableFromOthers(it), "$it must not be dialed")
        }
    }

    @Test
    fun namesThatResolvePrivatelyPassTheUrlCheck() {
        // Public names that resolve to private addresses: no url classifier can see that.
        // PublicAddressGuardTest pins the resolve-time check that refuses them.
        assertTrue(norm("ws://192-168-0-12.sslip.io:7447/").isDialableFromOthers()) // seen
        assertTrue(norm("ws://10.0.2.2.nip.io:7447/").isDialableFromOthers()) // seen
    }

    @Test
    fun aSchemelessLocalHostStillGetsPlainWs() {
        // fix() picks ws:// for a host that cannot hold a CA certificate.
        assertEquals("ws://beelink:7777/", norm("beelink:7777").url)
        assertEquals("ws://nas.lan/", norm("nas.lan").url)
        assertEquals("ws://100.100.10.10:4848/", norm("100.100.10.10:4848").url)
        assertEquals("wss://nos.lol/", norm("nos.lol").url)
    }

    @Test
    fun httpUrlFormatterReadsTheHostNotThePath() {
        assertEquals("https://example.com/localhost", HttpUrlFormatter.addSchemeIfNeeded("example.com/localhost"))
        assertEquals("https://example.com/?u=127.0.0.1", HttpUrlFormatter.addSchemeIfNeeded("example.com/?u=127.0.0.1"))
        assertEquals("http://localhost:3000/x", HttpUrlFormatter.addSchemeIfNeeded("localhost:3000/x"))
        assertEquals("http://127.0.0.1/", HttpUrlFormatter.addSchemeIfNeeded("127.0.0.1/"))
        assertEquals("http://abc.onion:8080/", HttpUrlFormatter.addSchemeIfNeeded("abc.onion:8080/"))
        assertEquals("https://localhost.me", HttpUrlFormatter.addSchemeIfNeeded("localhost.me"))
    }
}
