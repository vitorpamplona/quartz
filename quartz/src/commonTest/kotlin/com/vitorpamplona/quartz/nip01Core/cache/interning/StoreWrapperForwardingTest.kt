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
package com.vitorpamplona.quartz.nip01Core.cache.interning

import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.ObservableEventStore
import com.vitorpamplona.quartz.nip01Core.store.RejectionReason
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The recommended stack, ObservableEventStore(InterningEventStore(EventStore())), must behave like
 * the SQLite store it wraps: the wrappers forward what the store overrides instead of falling back
 * to the interface defaults.
 */
class StoreWrapperForwardingTest {
    private val signer = NostrSignerSync()

    @Test
    fun batchInsertThroughTheWrappersClassifiesADuplicate() =
        runTest {
            val stack = ObservableEventStore(InterningEventStore(EventStore(null), EventInterner()))
            val note = signer.sign(TextNoteEvent.build("dup"))

            val outcomes = stack.batchInsert(listOf(note, note))

            assertEquals(IEventStore.InsertOutcome.Accepted, outcomes[0])
            val second = assertIs<IEventStore.InsertOutcome.Rejected>(outcomes[1])
            assertTrue(second.reason.startsWith(RejectionReason.PREFIX_DUPLICATE), second.reason)
        }

    @Test
    fun theFtsBacklogIsVisibleThroughTheWrappers() {
        val inner =
            object : IEventStore by EventStore(null) {
                override val needsFtsCatchUp: Boolean get() = true
            }
        assertTrue(InterningEventStore(inner, EventInterner()).needsFtsCatchUp)
        assertTrue(ObservableEventStore(InterningEventStore(inner, EventInterner())).needsFtsCatchUp)
    }
}
