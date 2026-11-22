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
package com.vitorpamplona.quartz.buzz.pmPinnedMessages

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * NIP-PM (`docs/nips/NIP-PM.md` in block/buzz at 52cbc6113). The wire shapes below are the ones
 * the spec's examples and `crates/buzz-test-client/tests/e2e_channel_pins.rs` sign: a command is
 * `h` + `e` + `client-id` with empty content, the list is `d`, `h`, then one `e` per pin.
 */
class PinnedMessagesEventTest {
    private val signer = NostrSignerInternal(KeyPair())
    private val channel = "6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b"
    private val m1 = "a".repeat(64)
    private val m2 = "b".repeat(64)

    private fun reparse(e: Event): Event = EventFactory.create(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig)

    @Test
    fun pinBuildsTheSpecShapeAndParsesBack() =
        runTest {
            val signed = signer.sign(PinMessageEvent.build(channel, m1))
            val pin = assertIs<PinMessageEvent>(reparse(signed))
            assertEquals(40010, pin.kind)
            assertEquals("", pin.content)
            assertEquals(channel, pin.channel())
            assertEquals(m1, pin.target())
            assertEquals(listOf(m1), pin.linkedEventIds())
            assertTrue(pin.clientId()!!.isNotEmpty())
            assertTrue(pin.isWellFormed())
            assertEquals(1, pin.tags.count { it[0] == "h" })
            assertEquals(1, pin.tags.count { it[0] == "e" })
        }

    @Test
    fun twoCommandsInOneSecondAreTwoEvents() =
        runTest {
            val a = signer.sign(UnpinMessageEvent.build(channel, m1, createdAt = 1_000))
            val b = signer.sign(UnpinMessageEvent.build(channel, m1, createdAt = 1_000))
            assertNotEquals(a.id, b.id, "a fresh client-id per command keeps them apart")
        }

    @Test
    fun unpinParsesBack() =
        runTest {
            val unpin = assertIs<UnpinMessageEvent>(reparse(signer.sign(UnpinMessageEvent.build(channel, m2, clientId = "fixed"))))
            assertEquals(40011, unpin.kind)
            assertEquals(m2, unpin.target())
            assertEquals("fixed", unpin.clientId())
            assertTrue(unpin.isWellFormed())
        }

    @Test
    fun malformedCommandsAreNotWellFormed() {
        val twoE = PinMessageEvent("00".repeat(32), "11".repeat(32), 1, arrayOf(arrayOf("h", channel), arrayOf("e", m1), arrayOf("e", m2)), "", "22".repeat(64))
        assertFalse(twoE.isWellFormed())
        val upper = PinMessageEvent("00".repeat(32), "11".repeat(32), 1, arrayOf(arrayOf("h", channel), arrayOf("e", "A".repeat(64))), "", "22".repeat(64))
        assertFalse(upper.isWellFormed())
        val content = PinMessageEvent("00".repeat(32), "11".repeat(32), 1, arrayOf(arrayOf("h", channel), arrayOf("e", m1)), "hi", "22".repeat(64))
        assertFalse(content.isWellFormed())
        val noH = PinMessageEvent("00".repeat(32), "11".repeat(32), 1, arrayOf(arrayOf("e", m1)), "", "22".repeat(64))
        assertFalse(noH.isWellFormed())
    }

    /** A list as `buzz-db/src/store/pins.rs` writes it: `d`, `h`, then the pins newest first. */
    @Test
    fun relayListParsesInDisplayOrder() {
        val tags = arrayOf(arrayOf("d", channel), arrayOf("h", channel), arrayOf("e", m2), arrayOf("e", m1))
        val list = assertIs<ChannelPinsEvent>(EventFactory.create("00".repeat(32), "11".repeat(32), 100, 30623, tags, "", "22".repeat(64)))
        assertEquals(channel, list.channel())
        assertEquals(channel, list.dTag())
        assertEquals(listOf(m2, m1), list.pinnedMessageIds())
        assertEquals(listOf(m2, m1), list.linkedEventIds())
    }

    @Test
    fun emptyListMeansNoPins() =
        runTest {
            val list = assertIs<ChannelPinsEvent>(reparse(signer.sign(ChannelPinsEvent.build(channel, emptyList()))))
            assertTrue(list.pinnedMessageIds().isEmpty())
            assertEquals(channel, list.channel())
        }

    @Test
    fun builtListKeepsOrder() =
        runTest {
            val list = assertIs<ChannelPinsEvent>(reparse(signer.sign(ChannelPinsEvent.build(channel, listOf(m1, m2)))))
            assertEquals(listOf(m1, m2), list.pinnedMessageIds())
            assertEquals(listOf("d", "h", "e", "e"), list.tags.map { it[0] })
        }

    @Test
    fun pinnableKindsMatchUpstream() {
        // buzz-core/src/kind.rs PINNABLE_KINDS at 52cbc6113.
        assertEquals(setOf(9, 40002, 40008, 45001, 45003), ChannelPinsEvent.PINNABLE_KINDS)
        assertFalse(ChannelPinsEvent.isPinnable(40010), "a pin command is never a pin target")
    }
}
