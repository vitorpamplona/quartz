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
import java.util.concurrent.atomic.AtomicInteger
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

        /** Runs after a COUNT answer is recorded, still inside the send. */
        @Volatile
        var afterCount: (CountMessage) -> Unit = {}

        val rateLimited = AtomicInteger()

        override fun message(message: Message) {
            if (message is CountMessage) beforeCount(message)
            record(
                when (message) {
                    is CountMessage -> "COUNT ${message.queryId} ${message.result.count}"
                    is ClosedMessage -> "CLOSED ${message.subId} ${message.message}"
                    else -> message.toJson()
                },
            )
            if (message is ClosedMessage && message.message.startsWith("rate-limited:")) rateLimited.incrementAndGet()
            if (message is CountMessage) afterCount(message)
        }

        override fun raw(json: String) {}

        fun record(line: String) = synchronized(lines) { lines.add(line) }

        fun snapshot() = synchronized(lines) { lines.toList() }

        fun waitFor(
            what: String,
            done: (List<String>) -> Boolean,
        ) = runBlocking {
            try {
                withTimeout(10_000) { while (!done(snapshot())) delay(5) }
            } catch (e: Exception) {
                fail("never saw $what in ${snapshot()}")
            }
        }
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
            }
            log.waitFor("the replacement's answer") { it.contains("COUNT c 2") }
            Thread.sleep(400)
            // The old answer was already going out, so it arrives, but before its replacement's
            // answer, and only once.
            assertEquals(listOf("COUNT c 1", "COUNT c 2"), log.snapshot())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCloseWhileTheAnswerIsGoingOutIsOnAnUnknownId() {
        val log = Log()
        val backend = GenerationBackend { yield() }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = RelaySession(backend, EmptyPolicy, scope, log, onClose = {})

        // The answer is recorded, and the send lingers, as a socket write finishing does.
        val answered = CountDownLatch(1)
        log.afterCount = {
            answered.countDown()
            Thread.sleep(300)
        }
        try {
            runBlocking {
                session.receive(count("c", 1))
                assertTrue(answered.await(5, TimeUnit.SECONDS), "never answered")
                session.receive("""["CLOSE","c"]""")
            }
            log.waitFor("a reply to the CLOSE") { it.size >= 2 }
            Thread.sleep(400)
            assertEquals(listOf("COUNT c 1", "CLOSED c error: no such subscription"), log.snapshot())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun countsReCountsAndClosesOnRealThreadsStayOrderedAndCapped() {
        val seed = System.nanoTime()
        try {
            storm(seed)
        } catch (e: AssertionError) {
            throw AssertionError("seed $seed: ${e.message}", e)
        }
    }

    private fun storm(seed: Long) {
        val ids = (0 until 8).map { "q$it" }
        val cap = 4
        val log = Log()
        val random = Random(seed)
        val running = AtomicInteger()
        val mostRunning = AtomicInteger()
        val backend =
            GenerationBackend { generation ->
                mostRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                try {
                    val r = Random(seed + generation)
                    when (r.nextInt(4)) {
                        0 -> {}
                        1 -> yield()
                        2 -> delay(r.nextLong(0, 2))
                        // A store that finishes its read whatever the client says.
                        else -> withContext(NonCancellable) { delay(r.nextLong(0, 5)) }
                    }
                } finally {
                    running.decrementAndGet()
                }
            }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val session = RelaySession(backend, LimitsPolicy(RelayLimits(maxSubscriptions = cap)), scope, log, onClose = {})

        // The generation each id still owes an answer for, if any.
        val owed = mutableMapOf<String, Int?>()
        var next = 1000
        try {
            runBlocking(Dispatchers.Default) {
                repeat(20_000) {
                    val id = ids[random.nextInt(ids.size)]
                    if (random.nextInt(10) == 0) {
                        session.receive("""["CLOSE","$id"]""")
                        owed[id] = null
                    } else {
                        val g = next++
                        val refusedBefore = log.rateLimited.get()
                        session.receive(count(id, g))
                        // A refusal is sent while the reader handles the COUNT; the count it would
                        // have replaced keeps running and is still owed.
                        if (log.rateLimited.get() == refusedBefore) owed[id] = g
                    }
                }
                withTimeout(10_000) { while (scope.coroutineContext[Job]!!.children.any()) delay(5) }
            }

            assertTrue(mostRunning.get() <= cap + 1, "${mostRunning.get()} store reads at once with a cap of $cap")

            val lines = log.snapshot()
            for (id in ids) {
                // An id's answers arrive in the order its COUNTs were sent, each at most once.
                var last = 0
                var owedAnswers = 0
                for (line in lines) {
                    val parts = line.split(' ')
                    when {
                        parts[0] == "COUNT" && parts[1] == id -> {
                            val g = parts[2].toInt()
                            if (g <= last) fail("$id answered generation $g after generation $last")
                            last = g
                            if (g == owed[id]) owedAnswers++
                        }
                        parts[0] == "CLOSED" && parts[1] == id ->
                            if (!line.endsWith("error: no such subscription") && !parts[2].startsWith("rate-limited:")) fail("unexpected $line")
                    }
                }
                if (owed[id] != null) assertEquals(1, owedAnswers, "$id's last admitted count was answered once")
            }

            // Nothing leaked a slot: as many fresh counts as the cap are all admitted.
            runBlocking { ids.take(cap).forEach { session.receive(count("fresh-$it", 1)) } }
            log.waitFor("$cap fresh answers") { lines2 -> lines2.count { it.startsWith("COUNT fresh-") } == cap }
            assertTrue(log.snapshot().none { it.startsWith("CLOSED fresh-") }, "a fresh count was refused")
        } finally {
            scope.cancel()
        }
    }
}
