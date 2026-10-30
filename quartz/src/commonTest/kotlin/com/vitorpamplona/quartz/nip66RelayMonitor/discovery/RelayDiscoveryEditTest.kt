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
package com.vitorpamplona.quartz.nip66RelayMonitor.discovery

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip11RelayInfo.Nip11RelayInformation.RelayInformationLimitation
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.tags.NetworkType
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.tags.NetworkTypeTag
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.tags.RequirementTag
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.tags.RttType
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A 30166 has one slot per (monitor, relay), and more than one writer may keep knowledge in it:
 * every update must be an edit that keeps what the writer does not own, stamped strictly newer
 * than what it replaces.
 */
class RelayDiscoveryEditTest {
    private val signer = NostrSignerSync(KeyPair())
    private val relay = "wss://relay.example.com/".normalizeRelayUrl()

    private fun record(
        createdAt: Long,
        content: String = "",
        vararg tags: Array<String>,
    ): RelayDiscoveryEvent =
        signer.sign(
            RelayDiscoveryEvent.build(relay, content, createdAt) {
                tags.forEach { add(it) }
            },
        )

    private val ownsRtt: (Array<String>) -> Boolean = { it.firstOrNull() == RttType.OPEN.tagName || it.firstOrNull() == NetworkTypeTag.TAG_NAME }

    @Test
    fun aFirstRecordIsStampedNowWithOnlyItsOwnTags() {
        val t = RelayDiscoveryEvent.edit(relay, null, ownsRtt, createdAt = 1_000) { rtt(RttType.OPEN, 42) }
        assertEquals(1_000, t.createdAt)
        assertEquals("", t.content)
        assertEquals(listOf(listOf("d", relay.url), listOf("rtt-open", "42")), t.tags.map { it.toList() })
    }

    @Test
    fun anEditKeepsWhatItDoesNotOwnAndReplacesWhatItDoes() {
        val current =
            record(
                500,
                "kept content",
                arrayOf("rtt-open", "900"),
                arrayOf("n", "tor"),
                arrayOf("same-as", "wss://other.example.com/"),
                arrayOf("R", "pow"),
                arrayOf("x-unknown", "a", "b"),
            )
        val t =
            RelayDiscoveryEvent.edit(relay, current, ownsRtt, createdAt = 1_000) {
                networkType(NetworkType.CLEARNET)
                rtt(RttType.OPEN, 42)
            }
        val tags = t.tags.map { it.toList() }
        assertEquals("kept content", t.content)
        assertEquals(1, tags.count { it[0] == "d" }, "exactly one d tag: $tags")
        assertTrue(listOf("same-as", "wss://other.example.com/") in tags)
        assertTrue(listOf("R", "pow") in tags)
        assertTrue(listOf("x-unknown", "a", "b") in tags, "a tag this version has never heard of is carried across")
        assertTrue(listOf("rtt-open", "42") in tags)
        assertTrue(listOf("n", "clearnet") in tags)
        assertTrue(listOf("rtt-open", "900") !in tags, "an owned tag is replaced, never doubled")
        assertTrue(listOf("n", "tor") !in tags)
    }

    @Test
    fun anEditIsStrictlyNewerThanWhatItReplaces() {
        assertEquals(1_000, RelayDiscoveryEvent.edit(relay, record(999), ownsRtt, createdAt = 1_000) {}.createdAt)
        assertEquals(1_001, RelayDiscoveryEvent.edit(relay, record(1_000), ownsRtt, createdAt = 1_000) {}.createdAt)
        // Not capped: a record ahead of the clock must still be replaceable.
        assertEquals(5_001, RelayDiscoveryEvent.edit(relay, record(5_000), ownsRtt, createdAt = 1_000) {}.createdAt)
    }

    @Test
    fun nextCreatedAtIsNowUnlessThePreviousIsNotOlder() {
        assertEquals(1_000, TimeUtils.nextCreatedAt(null, now = 1_000))
        assertEquals(1_000, TimeUtils.nextCreatedAt(999, now = 1_000))
        assertEquals(1_001, TimeUtils.nextCreatedAt(1_000, now = 1_000))
        assertEquals(2_001, TimeUtils.nextCreatedAt(2_000, now = 1_000))
    }

    @Test
    fun nip11LimitationsBecomeNip66Requirements() {
        val limitation = RelayInformationLimitation(auth_required = false, payment_required = true, min_pow_difficulty = 0, restricted_writes = true)
        val reqs = RequirementTag.fromNip11(limitation).map { RequirementTag.assemble(it)[1] }
        // The key names the restriction, not the permission: `restricted_writes: true` is `writes`.
        assertEquals(setOf("!auth", "payment", "!pow", "writes"), reqs.toSet())
        assertEquals(listOf("!writes"), RequirementTag.fromNip11(RelayInformationLimitation(restricted_writes = false)).map { RequirementTag.assemble(it)[1] })
    }

    @Test
    fun aPowDifficultyOfZeroIsAClaimNotASilence() {
        assertEquals(listOf("pow"), RequirementTag.fromNip11(RelayInformationLimitation(min_pow_difficulty = 28)).map { RequirementTag.assemble(it)[1] })
        assertEquals(listOf("!pow"), RequirementTag.fromNip11(RelayInformationLimitation(min_pow_difficulty = 0)).map { RequirementTag.assemble(it)[1] })
    }

    @Test
    fun aLimitationThatSaysNothingGivesNoRequirement() {
        assertEquals(emptyList(), RequirementTag.fromNip11(RelayInformationLimitation(max_limit = 500)))
        assertEquals(emptyList(), RequirementTag.fromNip11(null))
    }
}
