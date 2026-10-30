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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.RelaySession
import com.vitorpamplona.quartz.nip01Core.relay.server.SessionSink
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQs on a [LiveSubscriptionBackend] keep no coroutine once their replay is done, and CLOSE,
 * a replaced sub id and a disconnect unregister them whether the replay finished or not.
 */
class LiveSubscriptionTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    private object Ctx : RequestContext {
        override val connectionId: Long = 1
        override val policy: IRelayPolicy = EmptyPolicy
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    /** Holds every replay until [gate] opens; throws instead when [fail] is set. */
    private class GatedStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        var replayStarted = CompletableDeferred<Unit>()
        var gate = CompletableDeferred<Unit>()
        var fail: String? = null

        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            fail?.let { throw IllegalStateException(it) }
            inner.query(filters, onEach)
        }

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            replayStarted.complete(Unit)
            gate.await()
            fail?.let { throw IllegalStateException(it) }
            inner.rawQuery(filters, onEach)
        }
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

    private class Frames : SessionSink {
        val frames = mutableListOf<String>()
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {
            frames.add(json)
        }
    }

    private class Fixture(
        test: TestScope,
    ) {
        val store = GatedStore(EventStore(null))
        val live = LiveEventStore(store, AcceptingIngest)
        val sink = Frames()

        /**
         * Owns the REQ coroutines, so the test can count them. On the test's scheduler (so
         * `advanceUntilIdle` runs them, which it does not for `backgroundScope`) but not a
         * child of the test, which would otherwise wait for it.
         */
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + Job())
        val session = RelaySession(live, EmptyPolicy, scope, sink, onClose = {})

        fun coroutines() = scope.coroutineContext[Job]!!.children.count()

        /** Commits [event] to the store and fans it out, as an accepted publish would. */
        suspend fun publish(event: Event) {
            store.inner.insert(event)
            live.submit(event) {}
        }
    }

    @Test
    fun closeDuringReplayLeavesNothingRegistered() =
        runTest {
            val f = Fixture(this)
            f.session.receive("""["REQ","s",{"kinds":[1]}]""")
            f.store.replayStarted.await()
            assertEquals(1, f.live.liveSubscriptionCount)

            f.session.receive("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()
            assertEquals(0, f.live.liveSubscriptionCount)
            assertEquals(0, f.coroutines())

            f.store.gate.complete(Unit)
            f.publish(event(1))
            testScheduler.advanceUntilIdle()
            assertTrue(f.sink.frames.isEmpty())
            assertTrue(f.sink.messages.none { it is EoseMessage })
        }

    @Test
    fun disconnectDuringReplayLeavesNothingRegistered() =
        runTest {
            val f = Fixture(this)
            f.session.receive("""["REQ","a",{"kinds":[1]}]""")
            f.session.receive("""["REQ","b",{"authors":["$pubkey"]}]""")
            f.store.replayStarted.await()
            assertEquals(2, f.live.liveSubscriptionCount)

            f.session.close()
            testScheduler.advanceUntilIdle()
            assertEquals(0, f.live.liveSubscriptionCount)
            assertEquals(0, f.coroutines())

            f.store.gate.complete(Unit)
            f.publish(event(1))
            testScheduler.advanceUntilIdle()
            assertTrue(f.sink.frames.isEmpty())
        }

    @Test
    fun aReplacedSubIdDropsTheOldRegistrationMidReplay() =
        runTest {
            val f = Fixture(this)
            f.session.receive("""["REQ","s",{"kinds":[1]}]""")
            f.store.replayStarted.await()

            f.session.receive("""["REQ","s",{"kinds":[1],"since":2000}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(1, f.live.liveSubscriptionCount)

            f.store.gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(1, f.sink.messages.count { it is EoseMessage })

            // Only the replacement's filter (since 2000) is live.
            f.publish(event(1))
            f.publish(event(1500))
            testScheduler.advanceUntilIdle()
            assertEquals(1, f.sink.frames.size)
            assertTrue(
                f.sink.frames
                    .single()
                    .contains(event(1500).id),
            )
        }

    @Test
    fun afterEoseALiveSubscriptionKeepsNoCoroutine() =
        runTest {
            val f = Fixture(this)
            f.store.gate.complete(Unit)
            f.publish(event(1))
            f.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            assertEquals(1, f.sink.messages.count { it is EoseMessage })
            assertEquals(1, f.sink.frames.size)
            assertEquals(0, f.coroutines())
            assertEquals(1, f.live.liveSubscriptionCount)

            f.publish(event(2))
            assertEquals(2, f.sink.frames.size)

            f.session.receive("""["CLOSE","s"]""")
            assertEquals(0, f.live.liveSubscriptionCount)
            f.publish(event(3))
            assertEquals(2, f.sink.frames.size)
            // A second CLOSE finds nothing.
            f.session.receive("""["CLOSE","s"]""")
            assertTrue(f.sink.messages.last() is ClosedMessage)
        }

    @Test
    fun aFailingReplayEndsWithClosedAndUnregisters() =
        runTest {
            val f = Fixture(this)
            f.store.fail = "disk on fire"
            f.store.gate.complete(Unit)
            f.session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            val closed = f.sink.messages.single() as ClosedMessage
            assertTrue(closed.message.contains("disk on fire"))
            assertEquals(0, f.live.liveSubscriptionCount)
            assertEquals(0, f.coroutines())
        }

    @Test
    fun anEventAcceptedMidReplayReachesTheHandleOnce() =
        runTest {
            val store = GatedStore(EventStore(null))
            val live = LiveEventStore(store, AcceptingIngest)
            val received = mutableListOf<String>()
            val eose = CompletableDeferred<Unit>()

            val sub = live.openSubscription(Ctx, listOf(Filter(kinds = listOf(1)))) { event, _ -> received.add(event.id) }
            val replay = backgroundScope.launch { sub.replayRaw({ received.add(it.id) }) { eose.complete(Unit) } }
            store.replayStarted.await()

            val note = event(1)
            store.inner.insert(note)
            live.submit(note) {}
            store.gate.complete(Unit)
            eose.await()
            replay.join()

            val next = event(2)
            store.inner.insert(next)
            live.submit(next) {}
            assertEquals(listOf(note.id, next.id), received)

            sub.cancel()
            sub.cancel()
            assertEquals(0, live.liveSubscriptionCount)
        }

    /** Screens out events whose content says "hidden", stored or live. */
    private object HidingPolicy : PassThroughPolicy() {
        override val filtersOutgoingEvents = true

        override fun canSendToSession(event: Event) = !event.content.contains("hidden")
    }

    @Test
    fun aScreeningPolicyVetoesStoredAndLiveEvents() =
        runTest {
            val store = GatedStore(EventStore(null))
            store.gate.complete(Unit)
            val live = LiveEventStore(store, AcceptingIngest)
            val sink = Frames()
            val session = RelaySession(live, HidingPolicy, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), sink, onClose = {})

            fun visible(n: Int) = event(n)

            fun hidden(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hidden $n", sig)

            store.inner.insert(visible(1))
            store.inner.insert(hidden(2))
            session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()

            live.submit(hidden(3).also { store.inner.insert(it) }) {}
            live.submit(visible(4).also { store.inner.insert(it) }) {}

            val sent = sink.messages.filterIsInstance<EventMessage>()
            assertEquals(listOf(visible(1).id, visible(4).id), sent.map { it.event.id })
            assertTrue(sent.all { it.subId == "s" })
            assertTrue(sink.frames.isEmpty())
            assertEquals(1, sink.messages.count { it is EoseMessage })
        }
}
