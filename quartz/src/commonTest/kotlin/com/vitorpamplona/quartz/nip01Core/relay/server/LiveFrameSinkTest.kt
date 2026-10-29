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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
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

@OptIn(ExperimentalCoroutinesApi::class)
class LiveFrameSinkTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun hexId(n: Int): String = n.toString().padStart(64, '0')

    private fun testEvent(
        id: String,
        createdAt: Long = 1000L,
    ) = Event(id, pubkey, createdAt, 1, emptyArray(), "hello", sig)

    private class LiveFrameCollector : LiveFrameSink {
        val raws = mutableListOf<String>()
        val prefixes = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun message(message: Message) {}

        override fun raw(json: String) {
            raws.add(json)
        }

        override fun liveEvent(
            framePrefix: String,
            body: String,
        ) {
            prefixes.add(framePrefix)
            bodies.add(body)
        }

        fun liveFrames() = prefixes.indices.map { prefixes[it] + bodies[it] + "]" }
    }

    @Test
    fun liveEventsArriveInPartsWithOneSharedBody() =
        runTest {
            val store = EventStore(null)
            store.insert(testEvent(hexId(1)))
            val server = NostrServer(store = store, policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val a = LiveFrameCollector()
            val b = LiveFrameCollector()
            val sessionA = server.connect(a)
            val sessionB = server.connect(b)
            val publisher = server.connect {}

            sessionA.receive("""["REQ","a1",{"kinds":[1]}]""")
            sessionA.receive("""["REQ","a2",{"kinds":[1]}]""")
            sessionB.receive("""["REQ","b1",{"kinds":[1]}]""")

            // The stored replay still goes through raw, one whole frame per row.
            assertEquals(3, a.raws.size + b.raws.size)
            assertTrue(a.prefixes.isEmpty() && b.prefixes.isEmpty())

            val live = testEvent(hexId(2), createdAt = 2000L)
            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(live)))

            // Fan-out order across subscriptions is unspecified (the index is a hash set).
            assertEquals(setOf("[\"EVENT\",\"a1\",", "[\"EVENT\",\"a2\","), a.prefixes.toSet())
            assertEquals(listOf("[\"EVENT\",\"b1\","), b.prefixes)
            // One body instance for every subscription, on every connection.
            val body = a.bodies.first()
            (a.bodies + b.bodies).forEach { assertSame(body, it) }
            // The parts reassemble into the frame the plain path would have sent.
            (a.liveFrames() + b.liveFrames()).forEach {
                assertEquals(live.id, (OptimizedJsonMapper.fromJsonToMessage(it) as EventMessage).event.id)
            }
            assertEquals(3, a.raws.size + b.raws.size, "live events no longer go through raw")

            server.close()
        }

    @Test
    fun plainSinkStillGetsWholeLiveFrames() =
        runTest {
            val server = NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = UnconfinedTestDispatcher(testScheduler))
            val frames = mutableListOf<String>()
            val session = server.connect { frames.add(it) }
            val publisher = server.connect {}

            session.receive("""["REQ","s",{"kinds":[1]}]""")
            val live = testEvent(hexId(3))
            publisher.receive(OptimizedJsonMapper.toJson(EventCmd(live)))

            val event = frames.single { it.startsWith("[\"EVENT\"") }
            val parsed = OptimizedJsonMapper.fromJsonToMessage(event) as EventMessage
            assertEquals("s", parsed.subId)
            assertEquals(live.id, parsed.event.id)

            server.close()
        }
}
