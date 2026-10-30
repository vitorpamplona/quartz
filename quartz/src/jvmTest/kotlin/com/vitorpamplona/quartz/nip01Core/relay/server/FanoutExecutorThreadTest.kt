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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FanoutExecutor] over real threads: frames land on the declared thread, in order per subscription. */
class FanoutExecutorThreadTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "0".repeat(128)

    private class Loop(
        name: String,
    ) : FanoutExecutor {
        @Volatile
        var thread: Thread? = null
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).also { thread = it } }

        override fun execute(task: () -> Unit) = executor.execute(task)
    }

    private class Sink(
        override val liveExecutor: Loop,
        val eoses: AtomicInteger,
    ) : LiveFrameSink {
        /** Live event ids per subscription, in arrival order: written on the loop only, read once [frames] says done. */
        val received = ConcurrentHashMap<String, MutableList<String>>()
        val frames = AtomicInteger()
        val wrongThread = AtomicInteger()

        override fun message(message: Message) {
            if (message is EoseMessage) eoses.incrementAndGet()
        }

        override fun raw(json: String) {}

        override fun liveEvent(
            framePrefix: String,
            body: String,
        ) {
            if (Thread.currentThread() !== liveExecutor.thread) wrongThread.incrementAndGet()
            val id = OptimizedJsonMapper.fromJson(body).id
            received.getOrPut(framePrefix) { mutableListOf() }.add(id)
            frames.incrementAndGet()
        }
    }

    @Test
    fun framesArriveOnTheDeclaredThreadInPublishOrder() =
        runBlocking {
            val loops = List(3) { Loop("fanout-test-$it") }
            val server = NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = Dispatchers.Default + SupervisorJob())
            val eoses = AtomicInteger()
            val sinks = List(30) { Sink(loops[it % loops.size], eoses) }
            sinks.forEach { sink ->
                val session = server.connect(sink)
                session.receive("""["REQ","a",{"kinds":[1]}]""")
                session.receive("""["REQ","b",{"kinds":[1],"since":1000}]""")
            }
            withTimeout(10_000) { while (eoses.get() < sinks.size * 2) delay(1) }

            val oks = AtomicInteger()
            val publisher =
                server.connect(
                    object : SessionSink {
                        override fun message(message: Message) {
                            if (message is OkMessage && message.success) oks.incrementAndGet()
                        }

                        override fun raw(json: String) {}
                    },
                )
            val events = List(200) { Event(it.toString(16).padStart(64, '0'), pubkey, 1000L + it, 1, emptyArray(), "e$it", sig) }
            // One publisher, one event at a time: the store accepts them in this order.
            events.forEach {
                val before = oks.get()
                publisher.receive(OptimizedJsonMapper.toJson(EventCmd(it)))
                withTimeout(10_000) { while (oks.get() == before) delay(1) }
            }

            val expected = events.map { it.id }
            withTimeout(10_000) {
                while (sinks.any { it.frames.get() < 2 * events.size }) delay(1)
            }
            sinks.forEach { sink ->
                assertEquals(0, sink.wrongThread.get())
                assertEquals(setOf("[\"EVENT\",\"a\",", "[\"EVENT\",\"b\","), sink.received.keys)
                sink.received.values.forEach { assertEquals(expected, it) }
            }
            assertTrue(oks.get() == events.size)

            server.close()
            loops.forEach {
                it.executor.shutdown()
                it.executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
}
