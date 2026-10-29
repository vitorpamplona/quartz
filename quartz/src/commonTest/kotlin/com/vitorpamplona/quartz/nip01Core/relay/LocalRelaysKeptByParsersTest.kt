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

import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.allKeyPackageRelays
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.keyPackageRelays
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip01Core.hints.HintIndexer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.RelayListRecommendationProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsers and indexers return every relay a tag holds. Whether a local relay may be dialed
 * depends on whose data it came from, which only the app knows; it filters someone else's with
 * [isDialableFromOthers]. The deprecated filtered accessors keep filtering until 3.0.0.
 */
class LocalRelaysKeptByParsersTest {
    private val pubKey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val other = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

    private fun norm(url: String) = assertNotNull(RelayUrlNormalizer.normalizeOrNull(url))

    private val public = norm("wss://relay.example")
    private val lan = norm("ws://192.168.1.10:4848")
    private val tailnet = norm("ws://100.100.10.10:4848")
    private val mdns = norm("ws://umbrel.local:4848")

    private val nip65 =
        AdvertisedRelayListEvent(
            "a".repeat(64),
            pubKey,
            1,
            arrayOf(
                arrayOf("r", "wss://relay.example"),
                arrayOf("r", "ws://192.168.1.10:4848", "write"),
                arrayOf("r", "ws://100.100.10.10:4848"),
                arrayOf("r", "ws://umbrel.local:4848", "read"),
            ),
            "",
            "b".repeat(128),
        )

    @Test
    fun aRelayListKeepsEveryRelay() {
        assertEquals(listOf(public, lan, tailnet, mdns), nip65.relaysNorm())
        assertEquals(listOf(public, lan, tailnet, mdns), nip65.relays().map { it.relayUrl })
        assertEquals(listOf(public, tailnet, mdns), nip65.allReadRelaysNorm())
        assertEquals(listOf(public, lan, tailnet), nip65.allWriteRelaysNorm())
    }

    @Test
    fun othersRelayListFilteredByThePolicyKeepsOnlyThePublicRelay() {
        assertEquals(listOf(public), nip65.allWriteRelaysNorm()?.filter { it.isDialableFromOthers() })
        assertEquals(listOf(public), nip65.allReadRelaysNorm()?.filter { it.isDialableFromOthers() })
    }

    @Test
    @Suppress("DEPRECATION")
    fun theDeprecatedFilteredNamesStillDropLocalRelaysUntil300() {
        assertEquals(listOf(public), nip65.writeRelaysNorm())
        assertEquals(listOf(public), nip65.readRelaysNorm())
    }

    private fun dmList(vararg relays: String) = DmRelayListEvent("a".repeat(64), pubKey, 1, relays.map { arrayOf("relay", it) }.toTypedArray(), "", "b".repeat(128))

    @Test
    fun aDmListDiffReportsALocalRelay() {
        val older = dmList("wss://relay.example")
        val newer = dmList("wss://relay.example", "ws://localhost:7777")
        val diff = assertNotNull(newer.diffFrom(older))
        assertEquals(listOf(norm("ws://localhost:7777")), diff.relays.added)
        assertEquals(listOf(public, norm("ws://localhost:7777")), newer.allRelays())
    }

    @Test
    fun aKeyPackageRelayListDiffReportsALocalRelay() {
        fun list(vararg relays: String) = KeyPackageRelayListEvent("a".repeat(64), pubKey, 1, relays.map { arrayOf("relay", it) }.toTypedArray(), "", "b".repeat(128))
        val diff = assertNotNull(list("wss://relay.example", "ws://nas.lan:7777").diffFrom(list("wss://relay.example")))
        assertEquals(listOf(norm("ws://nas.lan:7777")), diff.relays.added)
    }

    @Test
    fun aWelcomeWhoseRelaysAreAllOnTheLanKeepsThem() {
        // The closed-network group: every member is on the same LAN, no internet at all.
        val welcome =
            WelcomeEvent(
                "a".repeat(64),
                pubKey,
                1,
                arrayOf(arrayOf("relays", "ws://192.168.1.10:4848", "ws://nas.lan:7777")),
                "",
                "b".repeat(128),
            )
        assertEquals(listOf(lan, norm("ws://nas.lan:7777")), welcome.allRelays())
        @Suppress("DEPRECATION")
        assertNull(welcome.relays(), "the deprecated name keeps dropping them until 3.0.0")
    }

    @Test
    fun aKeyPackageRelaysTagKeepsLocalRelays() {
        val tags = arrayOf(arrayOf("relays", "wss://relay.example", "ws://localhost:7777"))
        assertEquals(listOf(public, norm("ws://localhost:7777")), tags.allKeyPackageRelays())
        @Suppress("DEPRECATION")
        assertEquals(listOf(public), tags.keyPackageRelays())
    }

    @Test
    fun aVanishRequestNamingALocalRelayParses() {
        val vanish =
            RequestToVanishEvent(
                "a".repeat(64),
                pubKey,
                1,
                arrayOf(arrayOf("relay", "ws://localhost:7777"), arrayOf("relay", "ALL_RELAYS")),
                "",
                "b".repeat(128),
            )
        assertEquals(listOf(norm("ws://localhost:7777")), vanish.vanishFromRelays())
        assertTrue(vanish.vanishFromAllRelays())
    }

    @Test
    fun hintIndexerKeepsLocalHintsByDefaultAndDropsThemWithThePolicy() {
        val everything = HintIndexer()
        everything.addKey(other, lan)
        everything.addKey(other, public)
        assertEquals(setOf(lan, public), everything.hintsForKey(other).toSet())

        val dialable = HintIndexer { it.isDialableFromOthers() }
        dialable.addKey(other, lan)
        dialable.addKey(other, public)
        assertEquals(listOf(public), dialable.hintsForKey(other))
    }

    @Test
    fun recommendationsExcludeATailnetRelayWithThePolicy() {
        val list =
            AdvertisedRelayListEvent(
                "a".repeat(64),
                other,
                1,
                arrayOf(arrayOf("r", "ws://100.100.10.10:4848"), arrayOf("r", "wss://relay.example")),
                "",
                "b".repeat(128),
            )
        val all = RelayListRecommendationProcessor.reliableRelaySetFor(listOf(list)).map { it.relay }.toSet()
        assertEquals(setOf(tailnet, public), all)

        val filtered =
            RelayListRecommendationProcessor
                .reliableRelaySetFor(listOf(list), accept = { it.isDialableFromOthers() })
                .map { it.relay }
                .toSet()
        assertEquals(setOf(public), filtered)
    }
}
