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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cache's promise under real threads: an open never misses a write acknowledged before it
 * started, and never holds an event whose deletion was acknowledged before it started, while
 * writers, deleters and openers of the same and other windows all run at once.
 */
class NegentropySnapshotRaceTest {
    private class StoreIngest(
        private val store: IEventStore,
    ) : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(store.batchInsert(listOf(event)).single())

        override fun hasBacklog() = false

        override fun close() {}
    }

    /** Any store but Quartz's SQLite one, so nothing is local unless the hooks say so. */
    private class Wrapped(
        inner: IEventStore,
    ) : IEventStore by inner

    @Test
    fun noOpenMissesAnAcknowledgedWriteOrKeepsAnAcknowledgedDeletion() = race { store -> LiveEventStore(store, StoreIngest(store)) }

    @Test
    fun noSearchLocalOpenMissesAnAcknowledgedWriteOrKeepsAnAcknowledgedDeletion() =
        race { store ->
            val wrapped = Wrapped(store)
            LiveEventStore(wrapped, StoreIngest(wrapped), LiveEventStore.DEFAULT_MAX_HELD_BEFORE_EOSE, { true }, { true })
        }

    private fun race(live: (EventStore) -> LiveEventStore) =
        runBlocking {
            val store = EventStore(null)
            val live = live(store)
            val acked = ConcurrentHashMap<String, Event>()
            // Deletions submitted (they may or may not be in a snapshot yet), and acknowledged.
            val deleting = ConcurrentHashMap.newKeySet<String>()
            val deleted = ConcurrentHashMap.newKeySet<String>()
            val clock = AtomicInteger(0)
            val windows =
                listOf(
                    listOf(Filter(kinds = listOf(1))),
                    listOf(Filter(kinds = listOf(1), until = 1_000_000L + 400)),
                    listOf(Filter(kinds = listOf(1), since = 1_000_000L + 400)),
                    // A trust view, no text: the SQLite store strips it, Filter.match ignores it.
                    listOf(Filter(kinds = listOf(1), until = 1_000_000L + 600, search = "include:spam")),
                )
            val failures = ConcurrentHashMap.newKeySet<String>()

            coroutineScope {
                repeat(4) {
                    launch(Dispatchers.Default) {
                        val signer = NostrSignerSync()
                        var previous: Event? = null
                        repeat(150) { i ->
                            val note = signer.sign(TextNoteEvent.build("n $i", createdAt = 1_000_000L + clock.incrementAndGet()))
                            live.insert(note)
                            acked[note.id] = note
                            if (i % 3 == 0) live.insert(signer.sign<Event>(1_000_000L + clock.get(), 7, emptyArray(), "+"))
                            val target = previous
                            if (i % 5 == 0 && target != null) {
                                deleting.add(target.id)
                                live.insert(signer.sign(DeletionRequestEvent.build(listOf(target))))
                                deleted.add(target.id)
                            }
                            previous = note
                        }
                    }
                }
                repeat(6) { reader ->
                    launch(Dispatchers.Default) {
                        repeat(150) { i ->
                            val filters = windows[(reader + i) % windows.size]
                            val ackedBefore = HashMap(acked)
                            val deletedBefore = deleted.toHashSet()
                            val storage = live.sealedNegentropyStorage(filters, 100_000)!!
                            val ids = storage.map { it.id.toHexString() }.toHashSet()
                            val deletingAfter = deleting.toHashSet()
                            for ((id, note) in ackedBefore) {
                                if (id !in deletingAfter && filters.any { it.match(note) } && id !in ids) failures.add("missed $id")
                            }
                            for (id in deletedBefore) if (id in ids) failures.add("kept deleted $id")
                        }
                    }
                }
            }
            store.close()
            assertTrue(failures.isEmpty(), "${failures.size} violations, e.g. ${failures.take(5)}")
        }
}
