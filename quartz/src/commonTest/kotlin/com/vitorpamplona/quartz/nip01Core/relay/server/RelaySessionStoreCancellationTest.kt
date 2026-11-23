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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store's own CancellationException (a timeout it put on its read) is a failure of the read,
 * not a cancelled request: the client gets `CLOSED error:` instead of waiting forever. Only the
 * request's own cancellation (CLOSE, a replaced id, a disconnect) ends it without an answer.
 */
class RelaySessionStoreCancellationTest {
    /** A read that runs into its own timeout. */
    private suspend fun timesOut() =
        withTimeout(10) {
            delay(1000)
        }

    /** A plain backend: the REQ parks inside [query], which runs [read] first. */
    private class ParkedBackend(
        val read: suspend () -> Unit,
    ) : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            read()
            onEose()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int {
            read()
            return 1
        }
    }

    /** A store whose every read runs [read], behind a [LiveEventStore]. */
    private class ReadingStore(
        inner: IEventStore,
        val read: suspend () -> Unit,
    ) : IEventStore by inner {
        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) = read()

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) = read()
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

    private fun live(read: suspend () -> Unit) = LiveEventStore(ReadingStore(EventStore(null), read), AcceptingIngest)

    /** Screens every outgoing event, so a REQ takes the materialized path. */
    private object Screening : PassThroughPolicy() {
        override val filtersOutgoingEvents = true
    }

    private class Messages : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}

        fun closed() = messages.filterIsInstance<ClosedMessage>().map { it.subId to it.message }

        fun closedPrefixes() = closed().map { (id, reason) -> id to reason.substringBefore(':') }
    }

    private class Harness(
        val session: RelaySession,
        val sink: Messages,
        val scope: CoroutineScope,
    )

    private fun sessionTest(
        backend: SessionBackend,
        policy: IRelayPolicy = EmptyPolicy,
        body: suspend TestScope.(Harness) -> Unit,
    ): TestResult =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val sink = Messages()
            try {
                body(Harness(RelaySession(backend, policy, scope, sink, onClose = {}), sink, scope))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aParkedReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest(ParkedBackend(::timesOut)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), h.sink.closedPrefixes())
            assertTrue(h.sink.messages.none { it is EoseMessage })
        }

    @Test
    fun aLiveReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest(live(::timesOut)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), h.sink.closedPrefixes())
            assertTrue(h.sink.messages.none { it is EoseMessage })
        }

    @Test
    fun aScreenedLiveReqWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest(live(::timesOut), Screening) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error"), h.sink.closedPrefixes())
        }

    @Test
    fun aCountWhoseStoreTimesOutIsClosedWithAnError() =
        sessionTest(ParkedBackend(::timesOut)) { h ->
            h.session.receive("""["COUNT","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("c" to "error"), h.sink.closedPrefixes())
        }

    /** Finishes its read after a CLOSE, then fails on its own. */
    private suspend fun ignoresCancellationThenFails() {
        withContext(NonCancellable) { delay(1000) }
        throw CancellationException("store timeout")
    }

    @Test
    fun aClosedParkedReqIsNotAnsweredWhenItsStoreFailsLater() =
        sessionTest(ParkedBackend(::ignoresCancellationThenFails)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()

            assertTrue(h.sink.messages.isEmpty(), "got ${h.sink.messages.map { it.toJson() }}")
        }

    @Test
    fun aClosedLiveReqIsNotAnsweredWhenItsStoreFailsLater() =
        sessionTest(live(::ignoresCancellationThenFails)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()

            assertTrue(h.sink.messages.isEmpty(), "got ${h.sink.messages.map { it.toJson() }}")
        }

    /** TODO() in a store: an Error, not an Exception. */
    private suspend fun notImplemented() {
        delay(1)
        throw NotImplementedError("boom")
    }

    @Test
    fun aParkedReqWhoseStoreThrowsAnErrorIsClosedAndTheConnectionLives() =
        sessionTest(ParkedBackend(::notImplemented)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error: boom"), h.sink.closed())
            assertTrue(h.scope.isActive, "the store's error did not take the session's scope down")
        }

    @Test
    fun aLiveReqWhoseStoreThrowsAnErrorIsClosedAndTheConnectionLives() =
        sessionTest(live(::notImplemented)) { h ->
            h.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("s" to "error: boom"), h.sink.closed())
            assertTrue(h.scope.isActive, "the store's error did not take the session's scope down")
        }

    private suspend fun fails(): Unit = throw IllegalStateException("down")

    @Test
    fun aParkedReqTheRelayClosedGivesUpItsSlot() =
        sessionTest(ParkedBackend(::fails), LimitsPolicy(RelayLimits(maxSubscriptions = 1))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            h.session.receive("""["REQ","b",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","a"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("a" to "error: down", "b" to "error: down", "a" to "error: no such subscription"),
                h.sink.closed(),
            )
        }

    @Test
    fun aLiveReqTheRelayClosedGivesUpItsSlot() =
        sessionTest(live(::fails), LimitsPolicy(RelayLimits(maxSubscriptions = 1))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            h.session.receive("""["REQ","b",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","a"]""")
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("a" to "error: down", "b" to "error: down", "a" to "error: no such subscription"),
                h.sink.closed(),
            )
        }

    @Test
    fun anInvalidReqIdAtTheCapIsRefusedAsInvalid() =
        sessionTest(ParkedBackend { awaitCancellation() }, LimitsPolicy(RelayLimits(maxSubscriptions = 1, maxSubidLength = 4))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            h.session.receive("""["REQ","too-long",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("too-long" to "invalid: subscription id too long (max 4)"), h.sink.closed())
        }

    @Test
    fun aClosedParkedReqTheStoreStillRunsKeepsItsSlot() {
        val gate = CompletableDeferred<Unit>()
        sessionTest(ParkedBackend { withContext(NonCancellable) { gate.await() } }, LimitsPolicy(RelayLimits(maxSubscriptions = 1))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","a"]""")
            h.session.receive("""["REQ","b",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("b" to "rate-limited: too many concurrent subscriptions (max 1)"), h.sink.closed())

            // Once the store lets go of the closed read, its slot is free again.
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            h.session.receive("""["REQ","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(1, h.sink.closed().size, "got ${h.sink.closed()}")
            assertEquals(
                listOf("c"),
                h.sink.messages
                    .filterIsInstance<EoseMessage>()
                    .map { it.subId },
            )
        }
    }

    @Test
    fun aClosedLiveReqTheStoreStillRunsKeepsItsSlot() {
        val gate = CompletableDeferred<Unit>()
        sessionTest(live { withContext(NonCancellable) { gate.await() } }, LimitsPolicy(RelayLimits(maxSubscriptions = 1))) { h ->
            h.session.receive("""["REQ","a",{"kinds":[1]}]""")
            h.session.receive("""["CLOSE","a"]""")
            h.session.receive("""["REQ","b",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("b" to "rate-limited: too many concurrent subscriptions (max 1)"), h.sink.closed())

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            h.session.receive("""["REQ","c",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(1, h.sink.closed().size, "got ${h.sink.closed()}")
            assertEquals(
                listOf("c"),
                h.sink.messages
                    .filterIsInstance<EoseMessage>()
                    .map { it.subId },
            )
        }
    }
}
