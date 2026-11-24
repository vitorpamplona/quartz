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
package com.vitorpamplona.quartz.nip01Core.relay.server

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip01Core.store.StoreQueryContext
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression tests for the relay-server findings of the 2.25.0 library audit. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditServerFixesTest {
    private val relayUrl = NormalizedRelayUrl("wss://relay.example.com/")

    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /** A backend whose COUNTs never return, as a slow store under a COUNT flood is. */
    private class StuckCountBackend : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            onEose()
            awaitCancellation()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = awaitCancellation()
    }

    @Test
    fun runningCountsCountAgainstTheSubscriptionCap() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val frames = Frames()
            val session = RelaySession(StuckCountBackend(), LimitsPolicy(RelayLimits(maxSubscriptions = 2)), scope, frames, onClose = {})

            session.receive("""["COUNT","c1",{"kinds":[1]}]""")
            session.receive("""["COUNT","c2",{"kinds":[1]}]""")
            session.receive("""["COUNT","c3",{"kinds":[1]}]""")
            session.receive("""["REQ","r1",{"kinds":[1]}]""")

            val closed = frames.messages.filterIsInstance<ClosedMessage>().map { it.subId }
            assertEquals(listOf("c3", "r1"), closed, "past the cap a COUNT is refused, and so is a REQ: ${frames.messages}")
            scope.cancel()
        }

    /** Records the [StoreQueryContext] each negentropy snapshot was built under, and how many were built. */
    private class NegRecordingStore(
        private val inner: IEventStore,
    ) : IEventStore by inner {
        val contexts = mutableListOf<StoreQueryContext?>()

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
            onProgress: ((collected: Int) -> Unit)?,
        ): List<IdAndTime> {
            contexts.add(coroutineContext[StoreQueryContext])
            return inner.snapshotIdsForNegentropy(filters, maxEntries, onProgress)
        }

        override suspend fun sealedNegentropyStorage(
            filters: List<Filter>,
            maxEntries: Int,
        ): IStorage? {
            contexts.add(coroutineContext[StoreQueryContext])
            return inner.sealedNegentropyStorage(filters, maxEntries)
        }
    }

    private fun negOpen(subId: String) = NegentropySession.fromEvents(subId, Filter(kinds = listOf(1)), emptyList()).open()

    @Test
    fun negOpenSnapshotsAreTakenUnderTheCallersIdentityAndCachedPerObserver() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val store = NegRecordingStore(EventStore(null))
            val server = NostrServer(store = store, policyBuilder = { PassThroughPolicy() }, parentContext = dispatcher)
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)

            val first = server.connect { }
            first.authenticateByTransport(alice)
            first.receive(negOpen("n1"))
            val second = server.connect { }
            second.authenticateByTransport(bob)
            second.receive(negOpen("n1"))

            assertEquals(listOf(alice, bob), store.contexts.map { it?.observer }, "each NEG-OPEN is answered as its caller, from its own snapshot")
            server.close()
        }

    /** A backend whose negentropy snapshot waits on [gate], as a slow scan does. */
    private class SlowNegBackend : SessionBackend {
        val gate = CompletableDeferred<Unit>()

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            onEose()
            awaitCancellation()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = 0

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
        ): List<IdAndTime> {
            gate.await()
            return emptyList()
        }
    }

    @Test
    fun aSlowNegOpenDoesNotHoldTheFramesBehindIt() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val frames = Frames()
            val backend = SlowNegBackend()
            val session = RelaySession(backend, PassThroughPolicy(), scope, frames, onClose = {})

            val loop =
                scope.launch {
                    session.receive(negOpen("n1"))
                    session.receive("""["REQ","r1",{"kinds":[1]}]""")
                }
            assertTrue(frames.messages.any { it is EoseMessage }, "the REQ behind a NEG-OPEN still scanning is answered: ${frames.messages}")
            backend.gate.complete(Unit)
            loop.join()
            assertTrue(frames.messages.any { it.label() == "NEG-MSG" }, "and the NEG-OPEN is answered once its scan ends: ${frames.messages}")
            scope.cancel()
        }
}
