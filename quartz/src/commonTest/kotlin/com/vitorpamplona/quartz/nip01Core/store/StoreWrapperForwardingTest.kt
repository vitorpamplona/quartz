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
package com.vitorpamplona.quartz.nip01Core.store

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.cache.interning.EventInterner
import com.vitorpamplona.quartz.nip01Core.cache.interning.InterningEventStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wrappers must reach the inner store for every member, including the ones
 * [IEventStore] gives a default body: inheriting the default instead of
 * forwarding silently turns off the inner store's behavior.
 */
class StoreWrapperForwardingTest {
    private class SpyStore(
        val base: IEventStore,
    ) : IEventStore by base {
        val calls = mutableListOf<String>()

        override val needsFtsCatchUp: Boolean get() = true

        override suspend fun ftsCatchUp(batchSize: Int): Boolean {
            calls.add("ftsCatchUp")
            return false
        }

        override suspend fun batchInsert(events: List<Event>): List<IEventStore.InsertOutcome> {
            calls.add("batchInsert")
            return base.batchInsert(events)
        }

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            calls.add("rawQuery")
        }

        override suspend fun authorsMissingOutbox(): List<HexKey> {
            calls.add("authorsMissingOutbox")
            return emptyList()
        }

        override suspend fun liveNegentropySnapshot(maxEntries: Int): IStorage? {
            calls.add("liveNegentropySnapshot")
            return null
        }
    }

    private val signer = NostrSignerSync()

    private suspend fun exercise(wrap: (IEventStore) -> IEventStore) {
        val spy = SpyStore(EventStore(null))
        val store = wrap(spy)
        try {
            assertTrue(store.needsFtsCatchUp, "needsFtsCatchUp")
            store.ftsCatchUp()
            store.batchInsert(listOf(signer.sign(TextNoteEvent.build("hi"))))
            store.rawQuery(listOf(Filter())) {}
            store.authorsMissingOutbox()
            store.liveNegentropySnapshot(10)
            assertEquals(
                listOf("ftsCatchUp", "batchInsert", "rawQuery", "authorsMissingOutbox", "liveNegentropySnapshot"),
                spy.calls,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun observableStoreForwardsEverything() = runTest { exercise { ObservableEventStore(it) } }

    @Test
    fun interningStoreForwardsEverything() = runTest { exercise { InterningEventStore(it, EventInterner()) } }
}
