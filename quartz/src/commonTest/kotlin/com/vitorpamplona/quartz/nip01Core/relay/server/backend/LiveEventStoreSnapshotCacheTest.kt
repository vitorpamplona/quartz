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
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Audit 3, finding 8: the NEG-OPEN snapshot cache keys on who asks, so an observer-relative store
 * answers each caller its own set. Quartz's SQLite store answers everyone the same set, so keying
 * it by caller only made authenticated mirror peers evict each other's snapshot from the one slot.
 */
class LiveEventStoreSnapshotCacheTest {
    private val signer = NostrSignerSync()

    private class As(
        user: HexKey,
    ) : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = setOf(user)
    }

    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    /** Any other store, whose answers may depend on who asks. */
    private class Wrapped(
        inner: IEventStore,
    ) : IEventStore by inner

    private val alice = As("a".repeat(64))
    private val bob = As("b".repeat(64))
    private val filters = listOf(Filter(kinds = listOf(1)))

    @Test
    fun peersOnTheSqliteStoreShareOneCachedSnapshot() =
        runTest {
            val store = EventStore(null)
            store.insert(signer.sign(TextNoteEvent.build("hi", createdAt = 1_000)))
            val live = LiveEventStore(store, AcceptingIngest)

            val first = live.sealedNegentropyStorage(alice, filters, 100)
            live.sealedNegentropyStorage(bob, filters, 100)
            assertSame(first, live.sealedNegentropyStorage(alice, filters, 100), "bob's NEG-OPEN does not evict alice's snapshot")
            store.close()
        }

    @Test
    fun anyOtherStoreStillGetsASnapshotPerCaller() =
        runTest {
            val store = EventStore(null)
            store.insert(signer.sign(TextNoteEvent.build("hi", createdAt = 1_000)))
            val live = LiveEventStore(Wrapped(store), AcceptingIngest)

            val forAlice = live.sealedNegentropyStorage(alice, filters, 100)
            assertNotSame(forAlice, live.sealedNegentropyStorage(bob, filters, 100), "bob is not handed alice's snapshot")
            store.close()
        }
}
