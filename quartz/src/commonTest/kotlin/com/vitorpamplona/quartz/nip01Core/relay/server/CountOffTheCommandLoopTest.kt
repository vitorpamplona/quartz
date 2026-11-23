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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A COUNT runs off the connection's command loop, so the frames behind it are read while it
 * waits on the backend, and each COUNT still gets exactly one answer.
 */
class CountOffTheCommandLoopTest {
    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}

        fun counts() = messages.filterIsInstance<CountMessage>()
    }

    /**
     * One query permit per connection, as a backend that limits a connection's concurrent
     * searches has: a REQ holds it for its live tail, until it is closed; a COUNT waits for it.
     */
    private class OnePermitBackend : SessionBackend {
        val permit = Semaphore(1)
        var countGate: CompletableDeferred<Unit>? = null
        var countFailure: String? = null
        var countError: Throwable? = null
        var countTimesOut = false

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            permit.withPermit {
                onEose()
                awaitCancellation()
            }
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int =
            permit.withPermit {
                countGate?.await()
                countFailure?.let { throw IllegalStateException(it) }
                countError?.let { throw it }
                // A backend deadline that lets its TimeoutCancellationException out.
                if (countTimesOut) withTimeout(1) { awaitCancellation() }
                7
            }
    }

    /** A connection whose frames are read one at a time from [incoming], as a socket's are. */
    private class Connection(
        test: TestScope,
        backend: SessionBackend,
    ) {
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + Job())
        val frames = Frames()
        val session = RelaySession(backend, EmptyPolicy, scope, frames, onClose = {})
        val incoming = Channel<String>(Channel.UNLIMITED)
        val read = mutableListOf<String>()

        init {
            scope.launch {
                for (frame in incoming) {
                    read.add(frame)
                    session.receive(frame)
                }
            }
        }

        fun end() = scope.cancel()
    }

    @Test
    fun aCountWaitingOnTheBackendDoesNotHoldUpTheCloseBehindIt() =
        runTest {
            val connection = Connection(this, OnePermitBackend())

            connection.incoming.send("""["REQ","s",{"kinds":[1]}]""")
            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            connection.incoming.send("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(3, connection.read.size, "the CLOSE was never read: ${connection.read}")
            assertEquals(listOf("c" to 7), connection.frames.counts().map { it.queryId to it.result.count })
            connection.end()
        }

    @Test
    fun manyCountsInFlightEachGetOneAnswer() =
        runTest {
            val backend = OnePermitBackend()
            val gate = CompletableDeferred<Unit>()
            backend.countGate = gate
            val connection = Connection(this, backend)

            val ids = (1..40).map { "c$it" }
            ids.forEach { connection.incoming.send("""["COUNT","$it",{"kinds":[1]}]""") }
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), connection.frames.counts())

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(
                ids.sorted(),
                connection.frames
                    .counts()
                    .map { it.queryId }
                    .sorted(),
            )
            assertTrue(connection.frames.messages.none { it is ClosedMessage })
            connection.end()
        }

    @Test
    fun aFailedCountGetsOneClosed() =
        runTest {
            val backend = OnePermitBackend()
            backend.countFailure = "index down"
            val connection = Connection(this, backend)

            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            val closed = connection.frames.messages.single() as ClosedMessage
            assertEquals("c", closed.subId)
            assertTrue(closed.message.startsWith("error:") && closed.message.contains("index down"), closed.message)
            connection.end()
        }

    @Test
    fun aCountWhoseBackendTimesOutGetsOneClosed() =
        runTest {
            val backend = OnePermitBackend()
            backend.countTimesOut = true
            val connection = Connection(this, backend)

            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            val closed = connection.frames.messages.single() as ClosedMessage
            assertEquals("c", closed.subId)
            assertTrue(closed.message.startsWith("error:"), closed.message)
            connection.end()
        }

    @Test
    fun aDisconnectCancelsARunningCount() =
        runTest {
            val backend = OnePermitBackend()
            backend.countGate = CompletableDeferred()
            val connection = Connection(this, backend)

            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            connection.session.close()
            testScheduler.advanceUntilIdle()

            // The COUNT gave its permit back: it is no longer waiting on the backend.
            assertEquals(1, backend.permit.availablePermits)
            assertEquals(emptyList(), connection.frames.messages)
            connection.end()
        }

    @Test
    fun aCountWhoseBackendThrowsAnErrorGetsOneClosedAndTheConnectionLives() =
        runTest {
            val backend = OnePermitBackend()
            // TODO() in a backend: an Error, not an Exception.
            backend.countError = NotImplementedError("not yet")
            val connection = Connection(this, backend)

            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            val closed = connection.frames.messages.single() as ClosedMessage
            assertEquals("c", closed.subId)
            assertTrue(closed.message.startsWith("error:") && closed.message.contains("not yet"), closed.message)

            // The Error did not take the session's scope down with it.
            backend.countError = null
            connection.incoming.send("""["COUNT","d",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("d" to 7), connection.frames.counts().map { it.queryId to it.result.count })
            connection.end()
        }

    /** Each count waits for the test to answer it. */
    private class AnsweredByTheTest : SessionBackend {
        val answers = mutableListOf<CompletableDeferred<Int>>()

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
            answers.add(answer)
            return answer.await()
        }
    }

    @Test
    fun aRepeatedCountOnOneIdIsAnsweredInTheOrderItWasSent() =
        runTest {
            val backend = AnsweredByTheTest()
            val connection = Connection(this, backend)

            // A client that re-sends a COUNT on the same id (new filters) keeps the last answer.
            connection.incoming.send("""["COUNT","c",{"kinds":[1]}]""")
            connection.incoming.send("""["COUNT","c",{"kinds":[7]}]""")
            testScheduler.advanceUntilIdle()
            backend.answers[1].complete(2)
            testScheduler.advanceUntilIdle()
            backend.answers[0].complete(1)
            testScheduler.advanceUntilIdle()

            // The older count's answer must not land after the newer one's, where it would read
            // as the answer to the filters the client sent last.
            assertEquals(listOf("c" to 1, "c" to 2), connection.frames.counts().map { it.queryId to it.result.count })
            connection.end()
        }
}
