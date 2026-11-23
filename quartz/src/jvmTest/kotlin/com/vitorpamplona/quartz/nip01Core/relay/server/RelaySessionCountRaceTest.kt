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
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * COUNT answers race their own cancellation on real threads: the answer is sent from the count's
 * coroutine while the reader handles a re-COUNT, a CLOSE or the next COUNT.
 */
class RelaySessionCountRaceTest {
    /** One line per frame and per handled command, in the order they happened. */
    private class Log : SessionSink {
        val lines = mutableListOf<String>()

        /** Runs before a COUNT answer is recorded; may block, as a slow socket write does. */
        @Volatile
        var beforeCount: (CountMessage) -> Unit = {}

        override fun message(message: Message) {
            if (message is CountMessage) beforeCount(message)
            record(
                when (message) {
                    is CountMessage -> "COUNT ${message.queryId} ${message.result.count}"
                    is ClosedMessage -> "CLOSED ${message.subId} ${message.message}"
                    else -> message.toJson()
                },
            )
        }

        override fun raw(json: String) {}

        fun record(line: String) = synchronized(lines) { lines.add(line) }

        fun snapshot() = synchronized(lines) { lines.toList() }
    }

    /** Counts to the filter's `since`, the generation the test gave the query. */
    private class GenerationBackend(
        val work: suspend (generation: Long) -> Unit,
    ) : SessionBackend {
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
            val generation = filters.first().since!!
            work(generation)
            return generation.toInt()
        }
    }

    private fun count(
        id: String,
        generation: Int,
    ) = """["COUNT","$id",{"kinds":[1],"since":$generation}]"""

    @Test
    fun anAnswerBeingSentIsNeverOvertakenByItsReplacement() {
        val log = Log()
        // Generation 1 answers from a worker thread (yield hops off the reader, which a
        // withContext on the scope's own dispatcher would not); generation 2 well after the reader is done.
        val backend = GenerationBackend { if (it == 1L) yield() else delay(100) }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = RelaySession(backend, EmptyPolicy, scope, log, onClose = {})

        val answering = CountDownLatch(1)
        log.beforeCount = { message ->
            if (message.result.count == 1) {
                answering.countDown()
                // The worker is inside the send when the reader takes the re-COUNT.
                Thread.sleep(300)
            }
        }
        try {
            runBlocking {
                session.receive(count("c", 1))
                assertTrue(answering.await(5, TimeUnit.SECONDS), "generation 1 never answered")
                session.receive(count("c", 2))
                log.record("handled re-COUNT")
                withTimeout(5000) { while (log.snapshot().none { it == "COUNT c 2" }) delay(5) }
                delay(400)
            }
            // The old answer was already going out, so it may arrive, but only before the reader is
            // done with the re-COUNT, never after it and never after the replacement's answer.
            assertEquals(listOf("COUNT c 1", "handled re-COUNT", "COUNT c 2"), log.snapshot())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun countsReCountsAndClosesOnRealThreadsLeaveNothingBehind() {
        val ids = (0 until 8).map { "q$it" }
        val log = Log()
        val random = Random(42)
        val backend =
            GenerationBackend {
                when (Random.nextInt(4)) {
                    0 -> {}
                    1 -> yield()
                    2 -> delay(Random.nextLong(0, 2))
                    else -> withContext(NonCancellable) { delay(Random.nextLong(0, 2)) }
                }
            }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = RelaySession(backend, LimitsPolicy(RelayLimits(maxSubscriptions = ids.size)), scope, log, onClose = {})

        val generation = ids.associateWith { 0 }.toMutableMap()
        val lastWasCount = ids.associateWith { false }.toMutableMap()
        var next = 1000
        try {
            runBlocking(Dispatchers.Default) {
                repeat(20_000) {
                    val id = ids[random.nextInt(ids.size)]
                    if (random.nextInt(10) == 0) {
                        session.receive("""["CLOSE","$id"]""")
                        // Every generation issued so far is over for this id.
                        log.record("handled CLOSE $id $next")
                        lastWasCount[id] = false
                    } else {
                        val g = next++
                        generation[id] = g
                        session.receive(count(id, g))
                        log.record("handled COUNT $id $g")
                        lastWasCount[id] = true
                    }
                }
                withTimeout(10_000) { while (scope.coroutineContext[Job]!!.children.any()) delay(5) }
            }

            val lines = log.snapshot()
            for (id in ids) {
                // Once a newer COUNT or a CLOSE on the id was handled, no older answer follows it.
                var floor = 0
                var answered = 0
                for (line in lines) {
                    val parts = line.split(' ')
                    when {
                        parts[0] == "handled" && parts[2] == id -> floor = maxOf(floor, parts[3].toInt())
                        parts[0] == "COUNT" && parts[1] == id -> {
                            val g = parts[2].toInt()
                            if (g < floor) fail("$id answered generation $g after a newer COUNT or a CLOSE was handled (floor $floor)")
                            if (g == generation[id]) answered++
                            if (g >= floor) floor = g + 1
                        }
                        // A CLOSE that lands after its count answered is on an unknown id.
                        parts[0] == "CLOSED" && !line.endsWith("error: no such subscription") -> fail("unexpected $line")
                    }
                }
                if (lastWasCount[id] == true) assertEquals(1, answered, "$id's last count was answered once")
            }

            // Nothing leaked a slot: as many fresh counts as the cap are all admitted.
            runBlocking { ids.forEach { session.receive(count("fresh-$it", 1)) } }
            runBlocking { withTimeout(10_000) { while (log.snapshot().count { it.startsWith("COUNT fresh-") } < ids.size) delay(5) } }
            assertTrue(log.snapshot().none { it.startsWith("CLOSED fresh-") }, "a fresh count was refused")
        } finally {
            scope.cancel()
        }
    }
}
