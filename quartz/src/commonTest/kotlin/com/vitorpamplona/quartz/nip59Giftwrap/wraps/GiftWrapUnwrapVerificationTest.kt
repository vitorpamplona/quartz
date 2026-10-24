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
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wrap's key is throwaway, so the inner event's signature is the only proof of its author:
 * an inner event that doesn't verify must not come out of the wrap.
 */
class GiftWrapUnwrapVerificationTest {
    private val alice = NostrSignerSync(KeyPair())
    private val recipient = NostrSignerInternal(KeyPair())

    @Test
    fun aSignedInnerEventUnwraps() =
        runTest {
            val note = alice.sign(TextNoteEvent.build("hi"))
            val wrap = GiftWrapEvent.create(note, recipient.pubKey)

            assertEquals(note.id, wrap.unwrapOrNull(recipient)?.id)
            assertEquals(note.id, wrap.innerEventId)
        }

    @Test
    fun anInnerEventWithABadSignatureDoesNotUnwrap() =
        runTest {
            val note = alice.sign(TextNoteEvent.build("hi"))
            val forged = EventFactory.create<Event>(note.id, note.pubKey, note.createdAt, 14, note.tags, "forged", "0".repeat(128))
            val wrap = GiftWrapEvent.create(forged, recipient.pubKey)

            assertNull(wrap.unwrapOrNull(recipient))
            assertNull(wrap.unwrapAndUnsealOrNull(recipient))
            assertNull(wrap.innerEventId, "a forged inner id must not be recorded")
        }

    @Test
    fun anInnerEventClaimingAnotherAuthorDoesNotUnwrap() =
        runTest {
            val note = alice.sign(TextNoteEvent.build("hi"))
            val impostor = NostrSignerSync(KeyPair())
            val claimed = impostor.sign<Event>(note.createdAt, note.kind, note.tags, note.content)
            val forged = Event(claimed.id, alice.pubKey, claimed.createdAt, claimed.kind, claimed.tags, claimed.content, claimed.sig)
            val wrap = GiftWrapEvent.create(forged, recipient.pubKey)

            assertNull(wrap.unwrapOrNull(recipient))
        }
}
