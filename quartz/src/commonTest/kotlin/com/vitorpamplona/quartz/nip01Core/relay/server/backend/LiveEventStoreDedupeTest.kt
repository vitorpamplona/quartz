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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An event accepted while a REQ's replay is still running reaches the
 * subscriber twice if the live path delivers it first and the replay's
 * snapshot then also contains it. Each REQ must see every id once.
 */
class LiveEventStoreDedupeTest {
    private val signer = NostrSignerSync()

    private object Ctx : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    /** Holds every replay until [gate] opens, so the test can land a live event first. */
    private class GatedStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        val replayStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            inner.query(filters, onEach)
        }

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            inner.rawQuery(filters, onEach)
        }
    }

    /** Accepts at once: the test has already written the event to the store. */
    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    @Test
    fun eventLandingLiveBeforeTheReplayReadsItIsDeliveredOnce() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val received = mutableListOf<String>()
            val eose = CompletableDeferred<Unit>()

            val req =
                launch {
                    live.query(Ctx, listOf(Filter(kinds = listOf(TextNoteEvent.KIND))), { received.add(it.id) }) { eose.complete(Unit) }
                }
            store.replayStarted.await()

            val note = signer.sign<TextNoteEvent>(TextNoteEvent.build("hi"))
            store.inner.insert(note)
            live.submit(note) {}

            store.gate.complete(Unit)
            eose.await()
            req.cancel()

            assertEquals(listOf(note.id), received)
            store.close()
        }

    @Test
    fun rawReplayDeliversALiveEventOnce() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val received = mutableListOf<String>()
            val eose = CompletableDeferred<Unit>()

            val req =
                launch {
                    live.queryRaw(
                        Ctx,
                        listOf(Filter(kinds = listOf(TextNoteEvent.KIND))),
                        onEachStored = { received.add(it.id) },
                        onEachLive = { event, _ -> received.add(event.id) },
                        onEose = { eose.complete(Unit) },
                    )
                }
            store.replayStarted.await()

            val note = signer.sign<TextNoteEvent>(TextNoteEvent.build("hi"))
            store.inner.insert(note)
            live.submit(note) {}

            store.gate.complete(Unit)
            eose.await()
            req.cancel()

            assertEquals(listOf(note.id), received)
            store.close()
        }
}
