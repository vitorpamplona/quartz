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

import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoObjectEvent
import com.vitorpamplona.quartz.experimental.edits.TextNoteModificationEvent
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip34Git.repository.tags.CommitReferenceTag
import com.vitorpamplona.quartz.nip60Cashu.wallet.CashuWalletEvent
import com.vitorpamplona.quartz.nip64Chess.Color
import com.vitorpamplona.quartz.nip64Chess.jester.JesterEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterProtocol
import com.vitorpamplona.quartz.nip87Ecash.recommendation.MintRecommendationEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Event-class cleanup, batch 3: the builders whose inline `arrayOf("…", …)` became a codec's
 * `assemble` still write the same tags, in the same order, and the class reads them back.
 */
class TagCodecBuilderTest {
    private val signer = NostrSignerInternal(KeyPair())
    private val start = "a1".repeat(32)
    private val head = "b2".repeat(32)
    private val opponent = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    private fun TagArray.named(vararg names: String) = filter { it.isNotEmpty() && it[0] in names }.map { it.toList() }

    @Test
    fun jesterMoveWritesStartHeadAndOpponentInOrder() {
        val template = JesterEvent.buildMove(start, head, "e4", "fen", listOf("e4"), opponent)
        assertEquals(listOf(listOf("e", start), listOf("e", head), listOf("p", opponent)), template.tags.named("e", "p"))

        val move = JesterEvent("00".repeat(32), "11".repeat(32), template.createdAt, template.tags, template.content, "00".repeat(64))
        assertEquals(start, move.startEventId())
        assertEquals(head, move.headEventId())
        assertEquals(opponent, move.opponentPubkey())
    }

    @Test
    fun jesterStartWritesThePositionHash() {
        val template = JesterEvent.buildPrivateStart(opponent, Color.WHITE)
        assertEquals(listOf(listOf("e", JesterProtocol.START_POSITION_HASH), listOf("p", opponent)), template.tags.named("e", "p"))
    }

    @Test
    fun snoObjectWritesItsName() {
        val template = SnoObjectEvent.build("cube-1", "{}", name = "Cube")
        assertEquals(listOf(listOf("name", "Cube")), template.tags.named("name"))
    }

    @Test
    fun groupMetadataWritesSupportedKinds() {
        val template = GroupMetadataEvent.build("g1", supportedKinds = listOf(9, 11))
        assertEquals(listOf(listOf("supported_kinds", "9", "11")), template.tags.named("supported_kinds"))
    }

    @Test
    fun mintRecommendationWritesItsKind() {
        val template = MintRecommendationEvent.build("mint-1", 38172)
        assertEquals(listOf(listOf("k", "38172")), template.tags.named("k"))
    }

    @Test
    fun gitCommitReferenceIsAPlainR() {
        assertEquals(listOf("r", "c0ffee"), CommitReferenceTag.assemble("c0ffee").toList())
    }

    @Test
    fun blossomAuthorizationWritesVerbHashAndServer() =
        runTest {
            val hash = "ab".repeat(32)
            val auth = BlossomAuthorizationEvent.createDeleteAuth(hash, "Delete", signer, servers = listOf("https://Blossom.Example.com/"))
            assertEquals(listOf(listOf("t", "delete"), listOf("x", hash), listOf("server", "blossom.example.com")), auth.tags.named("t", "x", "server"))
            assertEquals("delete", auth.type())
            assertEquals(listOf(hash), auth.hashes())
            assertEquals(listOf("blossom.example.com"), auth.servers())
        }

    @Test
    fun cashuWalletWritesPrivkeyAndMintsPrivately() =
        runTest {
            val wallet: CashuWalletEvent = signer.sign(CashuWalletEvent.build(listOf("https://mint.example.com"), privkey = "cd".repeat(32), signer = signer))
            assertEquals(listOf(listOf("privkey", "cd".repeat(32)), listOf("mint", "https://mint.example.com")), wallet.cachedPrivateTags(signer).named("privkey", "mint"))
            assertEquals(listOf("https://mint.example.com"), wallet.mints(signer))
            assertEquals("cd".repeat(32), wallet.privkey(signer))
        }

    @Test
    fun noteModificationWritesItsSummary() =
        runTest {
            val edit = TextNoteModificationEvent.create("fixed", start, null, "typo", signer)
            assertEquals(listOf(listOf("summary", "typo")), edit.tags.named("summary"))
            assertTrue(edit.summary() == "typo")
        }
}
