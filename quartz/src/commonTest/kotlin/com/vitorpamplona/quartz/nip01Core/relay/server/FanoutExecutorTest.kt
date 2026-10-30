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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Sinks that declare a [FanoutExecutor] get their live frames from one task per event on it. */
@OptIn(ExperimentalCoroutinesApi::class)
class FanoutExecutorTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    private fun publishJson(n: Int) = OptimizedJsonMapper.toJson(EventCmd(event(n)))

    /** A test-driven executor: tasks wait until [drain] runs them. */
    class QueueExecutor : FanoutExecutor {
        val tasks = ArrayDeque<() -> Unit>()
        var running = false
            private set

        override fun execute(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun drain(): Int {
            var n = 0
            running = true
            while (tasks.isNotEmpty()) {
                tasks.removeFirst()()
                n++
            }
            running = false
            return n
        }
    }

    private class Frames(
        override val liveExecutor: QueueExecutor?,
    ) : LiveFrameSink {
        val prefixes = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var offExecutor = 0

        override fun message(message: Message) {}

        override fun raw(json: String) {}

        override fun liveEvent(
            framePrefix: String,
            body: String,
        ) {
            if (liveExecutor != null && !liveExecutor.running) offExecutor++
            prefixes.add(framePrefix)
            bodies.add(body)
        }
    }

    @Test
    fun liveFramesRunOnTheDeclaredExecutorOncePerEvent() =
        runTest {
            val server = NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val loopX = QueueExecutor()
            val loopY = QueueExecutor()
            val a = Frames(loopX)
            val b = Frames(loopX)
            val c = Frames(loopY)
            val plain = Frames(null)
            val sessions = listOf(a, b, c, plain).map { server.connect(it) }
            val publisher = server.connect {}

            sessions[0].receive("""["REQ","a1",{"kinds":[1]}]""")
            sessions[0].receive("""["REQ","a2",{"kinds":[1]}]""")
            sessions[1].receive("""["REQ","b1",{"kinds":[1]}]""")
            sessions[2].receive("""["REQ","c1",{"kinds":[1]}]""")
            sessions[3].receive("""["REQ","p1",{"kinds":[1]}]""")
            // EOSE settles each subscription's dedupe set on its own executor.
            loopX.drain()
            loopY.drain()

            publisher.receive(publishJson(1))

            // A sink without an executor still gets its frame on the accepting thread.
            assertEquals(listOf("[\"EVENT\",\"p1\","), plain.prefixes)
            // The others wait for their executor: one task per event per executor, not per subscription.
            assertTrue(a.prefixes.isEmpty() && b.prefixes.isEmpty() && c.prefixes.isEmpty())
            assertEquals(1, loopX.tasks.size)
            assertEquals(1, loopY.tasks.size)

            assertEquals(1, loopX.drain())
            assertEquals(setOf("[\"EVENT\",\"a1\",", "[\"EVENT\",\"a2\","), a.prefixes.toSet())
            assertEquals(listOf("[\"EVENT\",\"b1\","), b.prefixes)
            assertTrue(c.prefixes.isEmpty())
            assertEquals(1, loopY.drain())
            assertEquals(listOf("[\"EVENT\",\"c1\","), c.prefixes)
            listOf(a, b, c).forEach { assertEquals(0, it.offExecutor) }

            // One body instance for every subscription, across executors and the inline path.
            val body = plain.bodies.single()
            (a.bodies + b.bodies + c.bodies).forEach { assertSame(body, it) }

            // An event no subscription on an executor can match posts nothing there.
            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(Event("f".repeat(64), pubkey, 5000L, 7, emptyArray(), "", sig))))
            assertTrue(loopX.tasks.isEmpty() && loopY.tasks.isEmpty())

            server.close()
        }

    @Test
    fun aClosedSubscriptionGetsNothingFromATaskAlreadyQueued() =
        runTest {
            val server = NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val loop = QueueExecutor()
            val sink = Frames(loop)
            val session = server.connect(sink)
            val publisher = server.connect {}

            session.receive("""["REQ","s",{"kinds":[1]}]""")
            loop.drain()
            publisher.receive(publishJson(1))
            session.receive("""["CLOSE","s"]""")
            loop.drain()
            assertTrue(sink.prefixes.isEmpty())

            server.close()
        }

    @Test
    fun anExecutorPartitionComesBackAfterItsLastSubscriptionLeaves() =
        runTest {
            val server = NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val loop = QueueExecutor()
            val publisher = server.connect {}

            repeat(3) { round ->
                val sink = Frames(loop)
                val session = server.connect(sink)
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                loop.drain()
                publisher.receive(publishJson(round))
                loop.drain()
                assertEquals(listOf("[\"EVENT\",\"s\","), sink.prefixes, "round $round")
                session.close()
            }

            // With every subscription gone, nothing is queued for the executor.
            publisher.receive(publishJson(99))
            assertTrue(loop.tasks.isEmpty())

            server.close()
        }
}
