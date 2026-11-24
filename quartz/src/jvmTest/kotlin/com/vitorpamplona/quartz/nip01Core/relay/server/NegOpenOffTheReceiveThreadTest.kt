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

import com.vitorpamplona.negentropy.fingerprint.Fingerprint
import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgMessage
import com.vitorpamplona.quartz.nip77Negentropy.NegentropyServerSession
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * NEG-OPEN snapshots built by a store that blocks instead of suspending, as SQLite does: the
 * reader pool hands out a free connection without suspending, so nothing on that path ever
 * yields the thread.
 */
class NegOpenOffTheReceiveThreadTest {
    private class Frames : SessionSink {
        val messages = CopyOnWriteArrayList<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    private fun negOpen(subId: String) = NegentropySession.fromEvents(subId, Filter(kinds = listOf(1)), emptyList()).open()

    private fun entries(n: Int) = List(n) { IdAndTime(1_000L + it, Random.nextBytes(32).joinToString("") { b -> "%02x".format(b) }) }

    /** Its snapshot blocks the calling thread until [release], as a long SQLite scan does. */
    private class BlockingNegBackend : SessionBackend {
        val release = CountDownLatch(1)

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
            release.await(30, TimeUnit.SECONDS)
            return emptyList()
        }
    }

    /**
     * Audit 3, finding 2: the build was launched UNDISPATCHED, so a store that never suspends ran
     * the whole scan on the thread reading the connection, holding every frame behind it.
     */
    @Test
    fun aNegOpenWhoseStoreBlocksDoesNotHoldTheReceiveThread() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val frames = Frames()
            val backend = BlockingNegBackend()
            val session = RelaySession(backend, PassThroughPolicy(), scope, frames, onClose = {})
            val receiveThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                launch(receiveThread) {
                    session.receive(negOpen("n1"))
                    session.receive("""["REQ","r1",{"kinds":[1]}]""")
                }
                val answered = withTimeoutOrNull(5_000) { while (frames.messages.none { it is EoseMessage }) delay(5) }
                assertNotNull(answered, "the REQ behind a NEG-OPEN whose store blocks is answered: $frames")
                backend.release.countDown()
                withTimeout(5_000) { while (frames.messages.none { it is NegMsgMessage }) delay(5) }
            } finally {
                backend.release.countDown()
                scope.cancel()
                receiveThread.close()
            }
        }

    /** Blocks the first reconcile read until [release], after [entered] lets the test know. */
    private class BlockingStorage(
        private val inner: IStorage,
    ) : IStorage by inner {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        private fun pause() {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }

        override fun size(): Int {
            pause()
            return inner.size()
        }

        override fun fingerprint(
            begin: Int,
            end: Int,
        ): Fingerprint {
            pause()
            return inner.fingerprint(begin, end)
        }
    }

    /** The first snapshot is [first]; every later one is [later]. */
    private class TwoSnapshots(
        val first: IStorage,
        val later: IStorage,
    ) : SessionBackend {
        private val calls = AtomicInteger(0)

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = 0

        override suspend fun sealedNegentropyStorage(
            filters: List<Filter>,
            maxEntries: Int,
        ): IStorage = if (calls.getAndIncrement() == 0) first else later
    }

    /**
     * Audit 3, finding 7: the build checked that its session was still current, then answered
     * later, from its own thread. A re-open in between got its reply, then the replaced session's
     * answer after it, on the same id.
     */
    @Test
    fun aReplacedSessionsAnswerDoesNotFollowTheNewOne() =
        runBlocking {
            val stale = BlockingStorage(NegentropyServerSession.sealVector(entries(3)))
            val fresh = NegentropyServerSession.sealVector(entries(1))
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val frames = Frames()
            val session = RelaySession(TwoSnapshots(stale, fresh), PassThroughPolicy(), scope, frames, onClose = {})
            val otherThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val first = launch(otherThread) { session.receive(negOpen("n1")) }
                assertTrue(stale.entered.await(5, TimeUnit.SECONDS), "the first session is answering")

                // The client re-opens n1 while the first answer is being computed.
                session.receive(negOpen("n1"))
                withTimeout(5_000) { while (frames.messages.none { it is NegMsgMessage }) delay(5) }
                val reply = frames.messages.single { it is NegMsgMessage }.toJson()

                stale.release.countDown()
                first.join()
                withTimeout(5_000) {
                    scope.coroutineContext.job.children
                        .toList()
                        .joinAll()
                }

                assertEquals(listOf(reply), frames.messages.filterIsInstance<NegMsgMessage>().map { it.toJson() }, "only the current session answers n1")
            } finally {
                stale.release.countDown()
                scope.cancel()
                otherThread.close()
            }
        }
}
