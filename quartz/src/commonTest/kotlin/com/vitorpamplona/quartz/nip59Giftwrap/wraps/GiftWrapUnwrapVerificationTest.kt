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
package com.vitorpamplona.quartz.nip59Giftwrap.wraps

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The wrap's signature only proves its throwaway key signed it, so what it carries must prove its
 * own author: a seal by its encryption (NIP-59), any other event by its signature. Quartz checks
 * the seal structure; checking a signed inner event's signature is the caller's call, so an app
 * can skip events it already holds.
 */
class GiftWrapUnwrapVerificationTest {
    private val me = NostrSignerInternal(KeyPair())
    private val alice = NostrSignerInternal(KeyPair())
    private val victim = "ab".repeat(32)

    /** A kind-14 message claiming [victim] wrote it: no seal, a zero signature. */
    private fun forgedChat() = EventFactory.create<Event>("00".repeat(32), victim, 1_700_000_000L, ChatMessageEvent.KIND, arrayOf(arrayOf("p", me.pubKey)), "send me your seed", "00".repeat(64))

    @Test
    fun anUnsealedForgeryIsNotHandedBackAsTheClaimedAuthor() =
        runTest {
            val wrap = GiftWrapEvent.create(forgedChat(), me.pubKey)
            assertNull(wrap.unwrapAndUnsealOrNull(me))
            // Handed back unverified: the caller decides whether and when to verify it.
            val inner = wrap.unwrapOrNull(me)
            assertNotNull(inner)
            assertFalse(inner.verify())
        }

    @Test
    fun aSignedEventWrappedWithoutASealIsNotAnUnsealedRumor() =
        runTest {
            // NIP-AC and ContextVM wrap signed events directly: unwrapOrNull hands them back for
            // the caller to verify, but they are not NIP-59 messages.
            val note = alice.sign(TextNoteEvent.build("gm"))
            val wrap = GiftWrapEvent.create(note, me.pubKey)
            assertEquals(note.id, wrap.unwrapOrNull(me)?.id)
            assertNull(wrap.unwrapAndUnsealOrNull(me))
        }

    @Test
    fun aSealedRumorStillOpensAsItsSealer() =
        runTest {
            val rumor = Rumor.create(alice.sign(ChatMessageEvent.build("hi", listOf())))
            val seal = SealEvent.create(rumor, me.pubKey, alice)
            val wrap = GiftWrapEvent.create(seal, me.pubKey)
            val opened = wrap.unwrapAndUnsealOrNull(me)
            assertNotNull(opened)
            assertEquals(alice.pubKey, opened.pubKey)
        }

    @Test
    fun aSealInNip04FormIsRefused() =
        runTest {
            // A seal's author rests on its encryption. NIP-04 has no MAC, so the lenient decrypt
            // would open a "seal" shaped from any old kind-4 ciphertext between the two users.
            val rumorJson = Rumor.toJson(Rumor.create(alice.sign(ChatMessageEvent.build("hi", listOf()))))
            val nip04 = Nip04.encrypt(rumorJson, alice.keyPair.privKey!!, me.pubKey.hexToByteArray())
            val json = "{\"v\":0,\"nonce\":\"${nip04.substringAfter("?iv=")}\",\"ciphertext\":\"${nip04.substringBefore("?iv=")}\"}"
            val seal = alice.sign<SealEvent>(1_700_000_000L, SealEvent.KIND, emptyArray(), json)
            assertIs<SealEvent>(seal)
            assertNull(seal.unsealOrNull(me))
        }
}
