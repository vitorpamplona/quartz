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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The publisher's OK reports the store's verdict only once the write is durable, and does not
 * wait for live delivery to subscribers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkDurabilityTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    /** Holds every batch until [gate] opens, and logs when its commit returned. */
    private class GatedWrites(
        val inner: IEventStore,
        val log: MutableList<String>,
    ) : IEventStore by inner {
        var gate = CompletableDeferred<Unit>()

        override suspend fun batchInsert(events: List<Event>): List<IEventStore.InsertOutcome> {
            gate.await()
            return inner.batchInsert(events).also { log.add("commit") }
        }
    }

    private class LoggingSink(
        val log: MutableList<String>,
        override val liveExecutor: FanoutExecutor? = null,
    ) : LiveFrameSink {
        override fun message(message: Message) {
            if (message is OkMessage) log.add("ok:${message.success}")
        }

        override fun raw(json: String) {}

        override fun liveEvent(
            framePrefix: String,
            body: String,
        ) {
            log.add("live")
        }
    }

    @Test
    fun okWaitsForTheCommitButNotForLiveDelivery() =
        runTest {
            val log = mutableListOf<String>()
            val store = GatedWrites(EventStore(null), log)
            val server = NostrServer(store = store, policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val subscriber = server.connect(LoggingSink(log))
            val publisher = server.connect(LoggingSink(log))
            subscriber.receive("""["REQ","s",{"kinds":[1]}]""")

            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()
            // The write is held: no OK, no live frame.
            assertEquals(emptyList(), log)

            store.gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("commit", "ok:true", "live"), log)

            server.close()
        }

    @Test
    fun okDoesNotWaitForAnExecutorThatHasNotRun() =
        runTest {
            val log = mutableListOf<String>()
            val store = GatedWrites(EventStore(null), log)
            store.gate.complete(Unit)
            val server = NostrServer(store = store, policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val loop = FanoutExecutorTest.QueueExecutor()
            val subscriber = server.connect(LoggingSink(log, loop))
            val publisher = server.connect(LoggingSink(log))
            subscriber.receive("""["REQ","s",{"kinds":[1]}]""")
            loop.drain()

            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("commit", "ok:true"), log)

            loop.drain()
            assertEquals(listOf("commit", "ok:true", "live"), log)

            server.close()
        }

    @Test
    fun aDuplicateIsAcknowledgedAfterItsCommitAndNotFannedOut() =
        runTest {
            val log = mutableListOf<String>()
            val store = GatedWrites(EventStore(null), log)
            store.gate.complete(Unit)
            val server = NostrServer(store = store, policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val subscriber = server.connect(LoggingSink(log))
            val publisher = server.connect(LoggingSink(log))
            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            subscriber.receive("""["REQ","s",{"kinds":[1]}]""")
            log.clear()

            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(event(1))))
            testScheduler.advanceUntilIdle()
            // NIP-01: a duplicate is `OK true` with the `duplicate:` prefix, and nothing new goes live.
            assertEquals(listOf("commit", "ok:true"), log)

            server.close()
        }
}
