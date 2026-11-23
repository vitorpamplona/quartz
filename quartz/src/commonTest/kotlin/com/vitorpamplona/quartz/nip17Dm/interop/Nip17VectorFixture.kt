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
package com.vitorpamplona.quartz.nip17Dm.interop

import com.vitorpamplona.crypto.aes.AESGCM
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.messages.changeSubject
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

/**
 * The fixture the NIP-17 vectors share with their generator (`quartz/tools/nip17-vector-gen`): the
 * same keys and clock. [ourCases] sends what Quartz's NIP-17 path writes (NIP17Factory: text, a
 * reply with a subject, a group room, a reaction, a file, a deletion) from [alice]; each case keeps
 * the rumor and the wrap to one recipient, which the generator opens with nostr-tools.
 */
object Nip17VectorFixture {
    val SK =
        linkedMapOf(
            "alice" to "44".repeat(32),
            "bob" to "55".repeat(32),
            "carol" to "66".repeat(32),
            "mallory" to "77".repeat(32),
        )
    val PK: Map<String, String> = SK.mapValues { KeyPair(it.value.hexToByteArray()).pubKey.toHexKey() }
    val alice = PK.getValue("alice")
    val bob = PK.getValue("bob")
    val carol = PK.getValue("carol")

    /** 2026-01-01T00:00:00Z. */
    const val T0 = 1767225600L

    fun signer(who: String) = NostrSignerInternal(KeyPair(SK.getValue(who).hexToByteArray()))

    val FILE_KEY = ByteArray(32) { 0x11 }
    val FILE_NONCE = ByteArray(12) { 0x22 }
    val FILE_HASH = "ab".repeat(32)

    class Case(
        val name: String,
        val recipient: String,
        val rumor: Event,
        val wrap: GiftWrapEvent,
    )

    private fun Case(
        name: String,
        recipient: String,
        result: NIP17Factory.Result,
    ) = Case(name, recipient, result.msg, result.wraps.first { it.recipientPubKey() == PK.getValue(recipient) })

    suspend fun ourCases(): List<Case> {
        val alice = signer("alice")
        val factory = NIP17Factory()
        val text = factory.createMessageNIP17(ChatMessageEvent.build("hello bob, from quartz", listOf(PTag(bob)), createdAt = T0), alice)
        val reply =
            factory.createMessageNIP17(
                ChatMessageEvent.reply("quartz replies", EventHintBundle(text.msg as ChatMessageEvent), createdAt = T0 + 1) { changeSubject("Pizza night") },
                alice,
            )
        val group = factory.createMessageNIP17(ChatMessageEvent.build("group hello from quartz", listOf(PTag(bob), PTag(carol)), createdAt = T0 + 2), alice)
        // createReactionWithinGroup builds this same template at the current time; built here at T0 + 3
        // so the rumor id is stable, and wrapped through the same two phases.
        val reactionMsg = alice.sign(ReactionEvent.build("🍕", EventHintBundle<Event>(group.msg), createdAt = T0 + 3))
        val seals = factory.createSeals(reactionMsg, setOf(bob, carol, alice.pubKey), alice)
        val reaction = NIP17Factory.Result(reactionMsg, seals.seals.map { factory.wrapSeal(it, seals.expirationDelta) })
        val file =
            factory.createEncryptedFileNIP17(
                ChatMessageEncryptedFileHeaderEvent.build(
                    to = listOf(PTag(bob)),
                    url = "https://blossom.example/$FILE_HASH",
                    cipher = AESGCM(FILE_KEY, FILE_NONCE),
                    mimeType = "image/png",
                    hash = FILE_HASH,
                    size = 6,
                    createdAt = T0 + 4,
                ),
                alice,
            )
        val deletion = factory.createDeletionNIP17(DeletionRequestEvent.build(listOf(text.msg), createdAt = T0 + 5), listOf(bob), alice)
        return listOf(
            Case("text", "bob", text),
            Case("text_sender_copy", "alice", text),
            Case("reply_with_subject", "bob", reply),
            Case("group_to_bob", "bob", group),
            Case("group_to_carol", "carol", group),
            Case("reaction_in_group", "carol", reaction),
            Case("file", "bob", file),
            Case("deletion", "bob", deletion),
        )
    }
}
