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
package com.vitorpamplona.quartz.nip17Dm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NIP-17: "Clients SHOULD randomize `created_at` in up to two days in the past in both the seal
 * and the gift wrap". Only the rumor carries the real send time.
 */
class NIP17FactoryTimestampTest {
    private val sender = NostrSignerInternal(KeyPair())
    private val recipients = List(4) { NostrSignerInternal(KeyPair()) }
    private val everyone = recipients + sender
    private val sentAt = TimeUtils.now()

    private val template = ChatMessageEvent.build("hi", recipients.map { PTag(it.pubKey) }, createdAt = sentAt)

    /** A seal within a minute of the send time is, for this test, one that was not randomized. */
    private fun assertRandomizedPast(
        createdAts: List<Long>,
        layer: String,
    ) {
        for (t in createdAts) {
            assertTrue(t in (sentAt - TimeUtils.twoDays())..sentAt, "$layer created_at $t outside the two days before $sentAt")
        }
        // Each one is uniform over two days, so all of them landing in the last minute is ~(60/172800)^n.
        assertTrue(createdAts.any { it < sentAt - 60 }, "every $layer is stamped with the send time: $createdAts")
    }

    private suspend fun unwrapSeal(wrap: GiftWrapEvent): Event {
        val recipient = everyone.first { it.pubKey == wrap.recipientPubKey() }
        return wrap.unwrapThrowing(recipient)
    }

    @Test
    fun sealsAndWrapsFromCreateMessageAreRandomized() =
        runTest {
            val result = NIP17Factory().createMessageNIP17(template, sender)
            assertEquals(everyone.size, result.wraps.size)
            assertEquals(sentAt, result.msg.createdAt)

            assertRandomizedPast(result.wraps.map { it.createdAt }, "gift wrap")
            assertRandomizedPast(result.wraps.map { unwrapSeal(it).createdAt }, "seal")
        }

    @Test
    fun sealsFromCreateSealsAreRandomized() =
        runTest {
            val message = sender.sign(template)
            val seals = NIP17Factory().createSeals(message, message.groupMembers(), sender)
            assertEquals(everyone.size, seals.seals.size)

            assertRandomizedPast(seals.seals.map { it.seal.createdAt }, "seal")
        }
}
