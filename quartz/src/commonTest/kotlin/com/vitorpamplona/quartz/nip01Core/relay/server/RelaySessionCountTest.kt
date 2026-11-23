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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A COUNT runs beside the connection's reader, as a REQ does, so a slow count holds up no
 * later frame; and it is tracked by query id, so a re-COUNT, a CLOSE or a disconnect stops it.
 */
class RelaySessionCountTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    /** Every count call waits for the test to answer it; every EVENT is accepted at once. */
    private class SlowCountBackend : SessionBackend {
        val calls = mutableListOf<CompletableDeferred<Int>>()
        var cancelled = 0

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            val answer = CompletableDeferred<Int>()
            calls.add(answer)
            try {
                return answer.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }

        override suspend fun submit(
            event: Event,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)
    }

    private class Messages : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /** A session read by one coroutine, frame after frame, as a socket's reader loop does. */
    private class Fixture(
        test: TestScope,
    ) {
        val backend = SlowCountBackend()
        val sink = Messages()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + Job())
        val session = RelaySession(backend, EmptyPolicy, scope, sink, onClose = {})
        private val inbox = Channel<String>(Channel.UNLIMITED)
        val reader = scope.launch { for (text in inbox) session.receive(text) }

        fun send(frame: String) {
            inbox.trySend(frame)
        }

        fun counts() = sink.messages.filterIsInstance<CountMessage>()

        fun coroutines() = scope.coroutineContext[Job]!!.children.count { it !== reader }
    }

    @Test
    fun anEventAfterASlowCountIsAnsweredFirst() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","c",{"kinds":[1]}]""")
            f.send(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.backend.calls.size, "the count is running")
            assertEquals(
                listOf(event(1).id),
                f.sink.messages
                    .filterIsInstance<OkMessage>()
                    .map { it.eventId },
            )
            assertTrue(f.counts().isEmpty(), "the count has not answered yet")

            f.backend.calls[0].complete(7)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("c" to 7), f.counts().map { it.queryId to it.result.count })
            assertEquals(0, f.coroutines())
        }

    @Test
    fun aReqAfterASlowCountIsAnsweredFirst() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","c",{"kinds":[1]}]""")
            f.send("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("s"),
                f.sink.messages
                    .filterIsInstance<EoseMessage>()
                    .map { it.subId },
            )
            assertTrue(f.counts().isEmpty())

            f.backend.calls[0].complete(3)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("c" to 3), f.counts().map { it.queryId to it.result.count })
        }

    @Test
    fun aReCountOnTheSameIdReplacesTheRunningOne() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            f.send("""["COUNT","c",{"kinds":[7]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.backend.cancelled, "the first count was cancelled")
            assertEquals(2, f.backend.calls.size)
            f.backend.calls[0].complete(1)
            f.backend.calls[1].complete(2)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to 2), f.counts().map { it.queryId to it.result.count })
            assertEquals(0, f.coroutines())
        }

    @Test
    fun closeOnACountIdStopsItSilently() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","c"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(1, f.backend.cancelled)
            assertEquals(0, f.coroutines())
            // Like a CLOSE on a REQ: no answer, and no "no such subscription".
            assertTrue(f.sink.messages.isEmpty(), "got ${f.sink.messages.map { it.toJson() }}")
        }

    @Test
    fun closeOnAnAnsweredCountIdIsStillUnknown() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            f.backend.calls[0].complete(5)
            testScheduler.advanceUntilIdle()
            f.send("""["CLOSE","c"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to 5), f.counts().map { it.queryId to it.result.count })
            assertEquals(
                listOf("c"),
                f.sink.messages
                    .filterIsInstance<ClosedMessage>()
                    .map { it.subId },
            )
        }

    @Test
    fun disconnectStopsARunningCount() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","a",{"kinds":[1]}]""")
            f.send("""["COUNT","b",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            f.session.close()
            testScheduler.advanceUntilIdle()
            assertEquals(0, f.coroutines())
            f.backend.calls.forEach { it.complete(9) }
            testScheduler.advanceUntilIdle()
            assertTrue(f.counts().isEmpty())
        }

    @Test
    fun countsOnOneConnectionRunOneAtATimeInArrivalOrder() =
        runTest {
            val f = Fixture(this)
            f.send("""["COUNT","a",{"kinds":[1]}]""")
            f.send("""["COUNT","b",{"kinds":[1]}]""")
            f.send(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()

            // The EVENT got past both, but the second count waits for the first.
            assertEquals(
                1,
                f.sink.messages
                    .filterIsInstance<OkMessage>()
                    .size,
            )
            assertEquals(1, f.backend.calls.size)

            f.backend.calls[0].complete(1)
            testScheduler.advanceUntilIdle()
            assertEquals(2, f.backend.calls.size)
            f.backend.calls[1].complete(2)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a" to 1, "b" to 2), f.counts().map { it.queryId to it.result.count })
        }

    @Test
    fun aFastCountStillAnswersBeforeTheNextFrame() =
        runTest {
            val f = Fixture(this)
            val session = RelaySession(InstantCountBackend, EmptyPolicy, f.scope, f.sink, onClose = {})
            session.receive("""["COUNT","c",{"kinds":[1]}]""")

            // A count that does not suspend is answered before receive returns, as it was.
            assertEquals(listOf("c" to 4), f.counts().map { it.queryId to it.result.count })
        }

    private object InstantCountBackend : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) = onEose()

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ) = 4
    }
}
