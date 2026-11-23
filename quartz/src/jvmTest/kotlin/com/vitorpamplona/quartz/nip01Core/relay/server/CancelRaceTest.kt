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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On real threads: once the command loop has handled a CLOSE, a re-COUNT, a re-REQ or a refusal
 * on an id, nothing the earlier COUNT or REQ on that id had not already started sending goes
 * out, and what it had started goes out before anything that follows on the id.
 */
class CancelRaceTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"
    private val old = Event("1".padStart(64, '0'), pubkey, 1001, 1, emptyArray(), "old", sig)

    /** Every frame as one line, in the order the sink took them; a hook may stall a frame first. */
    private class Log : SessionSink {
        val lines = mutableListOf<String>()

        @Volatile
        var stall: (String) -> Unit = {}

        private fun take(line: String) {
            stall(line)
            synchronized(lines) { lines.add(line) }
        }

        override fun message(message: Message) =
            take(
                when (message) {
                    is CountMessage -> "COUNT ${message.queryId} ${message.result.count}"
                    is ClosedMessage -> "CLOSED ${message.subId} ${message.message}"
                    is EoseMessage -> "EOSE ${message.subId}"
                    else -> message.toJson()
                },
            )

        override fun raw(json: String) = take(if ("\"old\"" in json) "EVENT old" else json)

        fun snapshot() = synchronized(lines) { lines.toList() }

        fun on(id: String) = snapshot().filter { it.split(' ').getOrNull(1) == id || it == "EVENT old" }

        fun waitFor(done: (List<String>) -> Boolean) =
            runBlocking {
                try {
                    withTimeout(10_000) { while (!done(snapshot())) delay(5) }
                } catch (e: Exception) {
                    fail("timed out with ${snapshot()}")
                }
            }
    }

    /** Stalls the first frame [matching] for 300 ms, after telling the test it is being sent. */
    private fun Log.stallOnce(matching: (String) -> Boolean): CountDownLatch {
        val sending = CountDownLatch(1)
        stall = { line ->
            if (matching(line) && sending.count > 0) {
                sending.countDown()
                Thread.sleep(300)
            }
        }
        return sending
    }

    private fun session(
        backend: SessionBackend,
        log: Log,
    ): Pair<RelaySession, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        return RelaySession(backend, EmptyPolicy, scope, log, onClose = {}) to scope
    }

    // -- COUNT ----------------------------------------------------------------

    /** Counts to the filter's `since`; [gates] holds a count until the test releases it, cancelled or not. */
    private class GatedCounts : SessionBackend {
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

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
            gates[generation]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return generation.toInt()
        }
    }

    private fun count(
        id: String,
        generation: Int,
    ) = """["COUNT","$id",{"kinds":[1],"since":$generation}]"""

    @Test
    fun aCountItsStoreFinishesAfterTheCloseOrReCountIsNeverAnswered() {
        val backend = GatedCounts()
        val log = Log()
        val (session, scope) = session(backend, log)
        try {
            runBlocking {
                repeat(100) { n ->
                    val id = "q$n"
                    val first = 1000 + 2 * n
                    val gate = CompletableDeferred<Unit>().also { backend.gates[first.toLong()] = it }
                    session.receive(count(id, first))
                    // Half the time a CLOSE, half a re-COUNT; the store returns only afterwards,
                    // from another thread, ignoring its cancellation.
                    if (n % 2 == 0) session.receive("""["CLOSE","$id"]""") else session.receive(count(id, first + 1))
                    scope.launch { gate.complete(Unit) }
                }
            }
            log.waitFor { lines -> (0 until 100).filter { it % 2 == 1 }.all { n -> "COUNT q$n ${1001 + 2 * n}" in lines } }
            Thread.sleep(200)
            val stale = log.snapshot().filter { line -> (0 until 100).any { n -> line == "COUNT q$n ${1000 + 2 * n}" } }
            assertEquals(emptyList(), stale, "answers of COUNTs cancelled before their store returned")
            assertTrue(log.snapshot().none { it.startsWith("CLOSED") }, "${log.snapshot().filter { it.startsWith("CLOSED") }}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCloseRacingTheAnswerGetsEitherNothingOrTheAnswerThenUnknown() {
        val seed = System.nanoTime()
        val random = Random(seed)
        val backend = GatedCounts()
        val log = Log()
        val (session, scope) = session(backend, log)
        try {
            runBlocking {
                repeat(200) { n ->
                    val id = "r$n"
                    val gate = CompletableDeferred<Unit>().also { backend.gates[n.toLong()] = it }
                    session.receive(count(id, n))
                    val delayNanos = random.nextLong(0, 200_000)
                    scope.launch {
                        val until = System.nanoTime() + delayNanos
                        while (System.nanoTime() < until) yield()
                        gate.complete(Unit)
                    }
                    val until = System.nanoTime() + random.nextLong(0, 200_000)
                    while (System.nanoTime() < until) yield()
                    session.receive("""["CLOSE","$id"]""")
                }
            }
            Thread.sleep(500)
            repeat(200) { n ->
                val id = "r$n"
                val got = log.on(id)
                val ok = got.isEmpty() || got == listOf("COUNT $id $n", "CLOSED $id error: no such subscription")
                assertTrue(ok, "seed $seed: $id got $got")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anAnswerBeingSentGoesBeforeItsReplacements() {
        val backend = GatedCounts()
        val log = Log()
        val (session, scope) = session(backend, log)
        val sending = log.stallOnce { it == "COUNT c 1" }
        try {
            runBlocking {
                // Generation 1 answers from a worker thread, which then stalls inside the send.
                backend.gates[1L] = CompletableDeferred()
                session.receive(count("c", 1))
                scope.launch { backend.gates[1L]!!.complete(Unit) }
                assertTrue(sending.await(5, TimeUnit.SECONDS))
                session.receive(count("c", 2))
            }
            log.waitFor { "COUNT c 2" in it }
            Thread.sleep(400)
            assertEquals(listOf("COUNT c 1", "COUNT c 2"), log.on("c"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCloseWhileTheAnswerIsBeingSentIsRepliedAfterIt() {
        val backend = GatedCounts()
        val log = Log()
        val (session, scope) = session(backend, log)
        val sending = log.stallOnce { it == "COUNT c 1" }
        try {
            runBlocking {
                backend.gates[1L] = CompletableDeferred()
                session.receive(count("c", 1))
                scope.launch { backend.gates[1L]!!.complete(Unit) }
                assertTrue(sending.await(5, TimeUnit.SECONDS))
                session.receive("""["CLOSE","c"]""")
            }
            log.waitFor { it.size >= 2 }
            Thread.sleep(400)
            assertEquals(listOf("COUNT c 1", "CLOSED c error: no such subscription"), log.on("c"))
        } finally {
            scope.cancel()
        }
    }

    // -- REQ ------------------------------------------------------------------

    /**
     * A parked REQ on kind 1 sends [old] (or fails, with [failOld]) from a worker thread, then
     * parks; any other REQ sends its EOSE and parks.
     */
    private inner class Streams(
        val failOld: Boolean = false,
    ) : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            if (filters.first().kinds == listOf(1)) {
                yield()
                if (failOld) throw IllegalStateException("store down")
                onEach(old)
            } else {
                onEose()
            }
            awaitCancellation()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ) = 0
    }

    /** The old REQ on "s" is sending [stalled] when the command loop handles [next]: [stalled] must come first. */
    private fun oldFrameGoesFirst(
        stalled: String,
        vararg next: String,
        expected: List<String>,
        failOld: Boolean = false,
    ) {
        val log = Log()
        val (session, scope) = session(Streams(failOld), log)
        val sending = log.stallOnce { it == stalled }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                assertTrue(sending.await(5, TimeUnit.SECONDS), "the old REQ never sent")
                next.forEach { session.receive(it) }
            }
            log.waitFor { it.size >= expected.size }
            Thread.sleep(400)
            assertEquals(expected, log.on("s"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReReqFollowsTheOldReqsFrameBeingSent() = oldFrameGoesFirst("EVENT old", """["REQ","s",{"kinds":[2]}]""", expected = listOf("EVENT old", "EOSE s"))

    @Test
    fun aRefusalOnTheIdFollowsTheOldReqsFrameBeingSent() = oldFrameGoesFirst("EVENT old", """["REQ","s",{"limit":"x"}]""", expected = listOf("EVENT old", "CLOSED s invalid: could not parse message"))

    @Test
    fun aReqAfterACloseFollowsTheOldReqsFrameBeingSent() = oldFrameGoesFirst("EVENT old", """["CLOSE","s"]""", """["REQ","s",{"kinds":[2]}]""", expected = listOf("EVENT old", "EOSE s"))

    @Test
    fun aReReqFollowsTheFailedReqsClosedBeingSent() =
        oldFrameGoesFirst(
            "CLOSED s error: store down",
            """["REQ","s",{"kinds":[2]}]""",
            expected = listOf("CLOSED s error: store down", "EOSE s"),
            failOld = true,
        )

    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    @Test
    fun aReReqFollowsTheOldReqsLiveEventBeingSent() {
        val live = LiveEventStore(EventStore(null), AcceptingIngest)
        val log = Log()
        val (session, scope) = session(live, log)
        val sending = log.stallOnce { it == "EVENT old" }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                log.waitFor { "EOSE s" in it }
                // A live event for the old REQ, delivered on another thread.
                scope.launch { live.submit(old) {} }
                assertTrue(sending.await(5, TimeUnit.SECONDS), "the live event never went out")
                session.receive("""["REQ","s",{"kinds":[2]}]""")
            }
            log.waitFor { it.count { line -> line == "EOSE s" } == 2 }
            Thread.sleep(400)
            assertEquals(listOf("EOSE s", "EVENT old", "EOSE s"), log.on("s"))
        } finally {
            scope.cancel()
        }
    }
}
