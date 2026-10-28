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
package com.vitorpamplona.quartz.nip01Core.core

import com.vitorpamplona.quartz.cyberspace.bagCiphertext
import com.vitorpamplona.quartz.cyberspace.bagVersion
import com.vitorpamplona.quartz.cyberspace.coordinate
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.snoName
import com.vitorpamplona.quartz.cyberspace.height
import com.vitorpamplona.quartz.cyberspace.lookupId
import com.vitorpamplona.quartz.experimental.audio.header.blossomMimeType
import com.vitorpamplona.quartz.experimental.ballots.election
import com.vitorpamplona.quartz.experimental.ballots.hasElection
import com.vitorpamplona.quartz.experimental.ballots.proofHash
import com.vitorpamplona.quartz.experimental.birdstar.speciesCount
import com.vitorpamplona.quartz.experimental.birdstar.speciesNames
import com.vitorpamplona.quartz.experimental.edits.editSummary
import com.vitorpamplona.quartz.feedDefinition.feedEmoji
import com.vitorpamplona.quartz.feedDefinition.feedTitle
import com.vitorpamplona.quartz.marmot.mip02Welcome.welcomeGroupId
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.references.ReferenceTag
import com.vitorpamplona.quartz.nip28PublicChat.base.isEncrypted
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.buzzChannelType
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.supportedKinds
import com.vitorpamplona.quartz.nip34Git.repository.earliestUniqueCommit
import com.vitorpamplona.quartz.nip34Git.status.referenceCommits
import com.vitorpamplona.quartz.nip47WalletConnect.events.encryptionScheme
import com.vitorpamplona.quartz.nip57Zaps.bolt11
import com.vitorpamplona.quartz.nip57Zaps.zapRequestDescription
import com.vitorpamplona.quartz.nip60Cashu.history.spendingAmount
import com.vitorpamplona.quartz.nip60Cashu.history.spendingDirectionCode
import com.vitorpamplona.quartz.nip60Cashu.history.spendingUnit
import com.vitorpamplona.quartz.nip60Cashu.quote.mint
import com.vitorpamplona.quartz.nip60Cashu.wallet.privkey
import com.vitorpamplona.quartz.nip60Cashu.wallet.walletMints
import com.vitorpamplona.quartz.nip64Chess.jester.JesterEvent
import com.vitorpamplona.quartz.nip64Chess.jester.gameEventRefCount
import com.vitorpamplona.quartz.nip87Ecash.MintUrlTag
import com.vitorpamplona.quartz.nip87Ecash.recommendation.MintRecommendationEvent
import com.vitorpamplona.quartz.nip87Ecash.recommendation.mintEventKind
import com.vitorpamplona.quartz.nip90Dvms.tags.dvmResultEventOrAddresses
import com.vitorpamplona.quartz.nip90Dvms.tags.dvmResultPubKeys
import com.vitorpamplona.quartz.nipB7Blossom.blossomHashes
import com.vitorpamplona.quartz.nipB7Blossom.blossomServers
import com.vitorpamplona.quartz.nipB7Blossom.blossomVerb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vitorpamplona.quartz.nip87Ecash.recommendation.dTag as recommendationDTag

/**
 * Event-class cleanup, batch 3: tags that classes parsed inline now go through codecs. Each test
 * keeps the old inline lambda, verbatim, and checks the new reader returns exactly what it did, for
 * every list of up to three tags drawn from a pool of edge cases: a missing value, an empty or
 * blank one, extra slots, numbers, the values a reader looks for, and unrelated tags.
 */
class TagCodecParityTest {
    private fun pool(
        names: List<String>,
        values: List<String> = emptyList(),
    ): List<Array<String>> =
        names.flatMap { name ->
            listOf(arrayOf(name), arrayOf(name, ""), arrayOf(name, " "), arrayOf(name, "v"), arrayOf(name, "42", "x"), arrayOf(name, "w", "")) +
                values.flatMap { listOf(arrayOf(name, it), arrayOf(name, it, "extra")) }
        } + listOf(arrayOf("other", "v"))

    private fun sequences(pool: List<Array<String>>): List<TagArray> {
        val result = mutableListOf<TagArray>(emptyArray())
        var previous = listOf<TagArray>(emptyArray())
        repeat(3) {
            previous = previous.flatMap { prefix -> pool.map { prefix + arrayOf(it) } }
            result.addAll(previous)
        }
        return result
    }

    private fun <T> assertParity(
        pool: List<Array<String>>,
        old: (TagArray) -> T,
        new: (TagArray) -> T,
    ) {
        val cases = sequences(pool)
        assertTrue(cases.size > 100)
        cases.forEach { tags ->
            val expected = old(tags)
            val actual = new(tags)
            // The message is built only on a mismatch: some pools yield over 100k cases.
            if (expected != actual) assertEquals(expected, actual, tags.joinToString { it.toList().toString() })
        }
    }

    @Test
    fun gitCommitReference() {
        val pool = pool(listOf("r"))
        assertParity(pool, { t -> t.firstOrNull { it.size > 1 && it[0] == "r" && it[1].isNotEmpty() }?.get(1) }, { it.earliestUniqueCommit() })
        assertParity(pool, { t -> t.mapNotNull { tag -> if (tag.size > 1 && tag[0] == "r" && tag[1].isNotEmpty()) tag[1] else null } }, { it.referenceCommits() })
    }

    @Test
    fun jesterGameReferencesAndOpponent() {
        val pool = pool(listOf("e", "p"))

        fun jester(tags: TagArray) = JesterEvent("00".repeat(32), "11".repeat(32), 1, tags, "", "00".repeat(64))
        assertParity(pool, { t -> t.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) }, { jester(it).startEventId() })
        assertParity(pool, { t -> t.filter { it.size >= 2 && it[0] == "e" }.getOrNull(1)?.get(1) }, { jester(it).headEventId() })
        assertParity(pool, { t -> t.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) }, { jester(it).opponentPubkey() })
        assertParity(pool, { t -> t.filter { it.size >= 2 && it[0] == "e" }.map { it[1] } }, { jester(it).eTags() })
        assertParity(pool, { t -> t.count { it.size >= 2 && it[0] == "e" } }, { it.gameEventRefCount() })
    }

    @Test
    fun dvmResults() {
        val pool = pool(listOf("e", "a", "p"))
        assertParity(pool, { t -> t.mapNotNull { if (it.size > 1 && (it[0] == "e" || it[0] == "a")) it[1] else null } }, { it.dvmResultEventOrAddresses() })
        assertParity(pool, { t -> t.mapNotNull { if (it.size > 1 && it[0] == "p") it[1] else null } }, { it.dvmResultPubKeys() })
    }

    @Test
    fun channelEncryptedFlag() {
        assertParity(pool(listOf("encrypted"), listOf("true", "false")), { t -> t.any { it.has(1) && it[0] == "encrypted" && it[1] == "true" } }, { it.isEncrypted() })
    }

    @Test
    fun cyberspaceTags() {
        assertParity(pool(listOf("C")), { t -> t.firstOrNull { it.size > 1 && it[0] == "C" }?.get(1) }, { it.coordinate() })
        assertParity(pool(listOf("d")), { t -> t.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) }, { it.lookupId() })
        assertParity(pool(listOf("h"), listOf("7")), { t -> t.firstOrNull { it.size > 1 && it[0] == "h" }?.get(1)?.toIntOrNull() }, { it.height() })
        assertParity(pool(listOf("version"), listOf("2")), { t -> t.firstOrNull { it.size > 1 && it[0] == "version" }?.get(1) }, { it.bagVersion() })
        assertParity(
            pool(listOf("encrypted"), listOf("aes-256-gcm")),
            { t -> t.firstOrNull { it.size > 2 && it[0] == "encrypted" }?.let { it[1] to it[2] } },
            { t -> t.bagCiphertext()?.let { it.algorithm to it.payload } },
        )
        assertParity(pool(listOf("name")), { t -> t.firstOrNull { it.size > 1 && it[0] == "name" }?.get(1) }, { it.snoName() })
    }

    @Test
    fun blossomTags() {
        assertParity(pool(listOf("t")), { t -> t.firstOrNull { it.size > 1 && it[0] == "t" }?.get(1) }, { it.blossomVerb() })
        assertParity(pool(listOf("x")), { t -> t.mapNotNull { if (it.size > 1 && it[0] == "x") it[1] else null } }, { it.blossomHashes() })
        assertParity(pool(listOf("server")), { t -> t.mapNotNull { if (it.size > 1 && it[0] == "server") it[1] else null } }, { it.blossomServers() })
    }

    @Test
    fun cashuTags() {
        assertParity(pool(listOf("mint")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "mint" }?.get(1) }, { it.mint() })
        assertParity(pool(listOf("mint")), { t -> t.filter { it.size >= 2 && it[0] == "mint" }.map { it[1] } }, { it.walletMints() })
        assertParity(pool(listOf("privkey")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "privkey" }?.get(1) }, { it.privkey() })
        assertParity(pool(listOf("direction"), listOf("in", "out")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "direction" }?.get(1) }, { it.spendingDirectionCode() })
        assertParity(pool(listOf("amount"), listOf("21")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "amount" }?.get(1)?.toLongOrNull() }, { it.spendingAmount() })
        assertParity(pool(listOf("unit"), listOf("sat")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "unit" }?.get(1) ?: "sat" }, { it.spendingUnit() ?: "sat" })
    }

    @Test
    fun audioBlossomMimeType() {
        assertParity(pool(listOf("blossom")), { t -> t.firstOrNull { it.size > 2 && it[0] == "blossom" }?.get(2)?.ifBlank { null } }, { it.blossomMimeType() })
    }

    @Test
    fun ballotElectionAndProofHash() {
        fun TagArray.firstNonBlankValue(name: String): String? = fastFirstNotNullOfOrNull { if (it.size > 1 && it[0] == name && it[1].isNotBlank()) it[1] else null }
        assertParity(pool(listOf("election")), { t -> t.firstNonBlankValue("election") }, { it.election() })
        assertParity(pool(listOf("election")), { t -> t.fastAny { it.size > 1 && it[0] == "election" && it[1].isNotBlank() } }, { it.hasElection() })
        assertParity(pool(listOf("proof-hash", "proof_hash")), { t -> t.firstNonBlankValue("proof-hash") ?: t.firstNonBlankValue("proof_hash") }, { it.proofHash() })
    }

    @Test
    fun birdexSpecies() {
        assertParity(pool(listOf("n")), { t -> t.mapValueTagged("n") { it } }, { it.speciesNames() })
        assertParity(pool(listOf("n")), { t -> t.count { it.size > 1 && it[0] == "n" } }, { it.speciesCount() })
    }

    @Test
    fun feedDefinitionTitleAndEmoji() {
        assertParity(pool(listOf("title")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) }, { it.feedTitle() })
        assertParity(pool(listOf("emoji")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "emoji" }?.get(1) }, { it.feedEmoji() })
    }

    @Test
    fun groupMetadataChannelTypeAndSupportedKinds() {
        assertParity(
            pool(listOf("t"), listOf("stream", "dm", "nostr")),
            { t -> t.firstNotNullOfOrNull { if (it.size > 1 && it[0] == "t" && it[1] in GroupMetadataEvent.BUZZ_CHANNEL_TYPES) it[1] else null } },
            { it.buzzChannelType() },
        )
        assertParity(
            pool(listOf("supported_kinds"), listOf("9")),
            { t -> t.firstOrNull { it.isNotEmpty() && it[0] == "supported_kinds" }?.drop(1)?.mapNotNull { it.toIntOrNull() } },
            { it.supportedKinds() },
        )
    }

    @Test
    fun labelRelayUrls() {
        assertParity(pool(listOf("r")), { t -> t.filter { it.size >= 2 && it[0] == "r" && it[1].isNotEmpty() }.map { it[1] } }, { it.mapNotNull(ReferenceTag::parse) })
    }

    @Test
    fun mintRecommendationClassifierAndKind() {
        val cashu = "38172:" + "11".repeat(32) + ":mint"
        val pool = pool(listOf("k", "u", "a"), listOf("38172", "38173", "1", cashu, "38172:"))

        fun Int?.isMintKind() = this == 38172 || this == 38173

        val old: (TagArray) -> Boolean = { tags ->
            if (tags.fastAny { it.size > 1 && it[0] == "k" && it[1].toIntOrNull().isMintKind() }) {
                true
            } else if (tags.fastAny { it.size > 1 && it[0] == "k" && it[1].isNotBlank() }) {
                false
            } else {
                tags.fastAny {
                    it.size > 1 &&
                        (
                            (it[0] == MintUrlTag.TAG_NAME && it[1].isNotBlank()) ||
                                (it[0] == "a" && (it[1].startsWith("38172:") || it[1].startsWith("38173:")))
                        )
                }
            }
        }
        assertParity(pool, old) { MintRecommendationEvent.isMintRecommendation(it) }
        assertParity(pool, { t -> t.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1)?.toIntOrNull() }, { it.mintEventKind() })
        assertParity(pool(listOf("d")), { t -> t.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) }, { it.recommendationDTag() })
    }

    @Test
    fun nwcEncryptionScheme() {
        assertParity(pool(listOf("encryption"), listOf("nip44_v2")), { t -> t.firstOrNull { it.size > 1 && it[0] == "encryption" }?.get(1) }, { it.encryptionScheme() })
    }

    @Test
    fun editSummary() {
        assertParity(pool(listOf("summary")), { t -> t.firstOrNull { it.size > 1 && it[0] == "summary" }?.get(1) }, { it.editSummary() })
    }

    @Test
    fun userStatusKeepsTheDTags() {
        assertParity(pool(listOf("d")), { t -> t.filter { it.size > 1 && it[0] == "d" } }, { it.filter(DTag::isTag) })
    }

    @Test
    fun welcomeGroupId() {
        assertParity(pool(listOf("h")), { t -> t.firstNotNullOfOrNull { tag -> if (tag.size >= 2 && tag[0] == "h") tag[1] else null } }, { it.welcomeGroupId() })
    }

    @Test
    fun zapReceiptInvoiceAndRequest() {
        assertParity(pool(listOf("bolt11")), { t -> t.firstOrNull { it.size > 1 && it[0] == "bolt11" }?.get(1) }, { it.bolt11() })
        assertParity(pool(listOf("description")), { t -> t.firstOrNull { it.size > 1 && it[0] == "description" }?.get(1) }, { it.zapRequestDescription() })
    }
}
