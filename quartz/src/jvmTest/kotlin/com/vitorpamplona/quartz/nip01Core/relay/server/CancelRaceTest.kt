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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On real threads: once the command loop has handled a CLOSE, a re-COUNT, a re-REQ or a refusal
 * on an id, nothing the earlier COUNT or REQ on that id had not already started sending goes
 * out, and what it had started goes out before anything that follows on the id. Each stall is a
 * handshake: the sink holds a frame until the test has handled the frames that must queue
 * behind it, so every test runs its interleaving, however loaded the machine.
 */
class CancelRaceTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"
    private val old = event(1, 1, "old")

    private fun event(
        n: Int,
        kind: Int,
        content: String,
    ) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, kind, emptyArray(), content, sig)

    /** Every frame as one line, in the order the sink took them; [hold] may stall or fail a frame first. */
    private class Log : SessionSink {
        val lines = mutableListOf<String>()

        @Volatile
        var hold: (String) -> Unit = {}

        private fun take(line: String) {
            hold(line)
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

        override fun raw(json: String) =
            take(
                when {
                    "\"old\"" in json -> "EVENT old"
                    "\"new\"" in json -> "EVENT new"
                    else -> json
                },
            )

        fun snapshot() = synchronized(lines) { lines.toList() }

        fun on(id: String) = snapshot().filter { it.split(' ').getOrNull(1) == id || it == "EVENT old" || it == "EVENT new" }

        fun waitFor(
            seconds: Long = 10,
            done: (List<String>) -> Boolean,
        ) = runBlocking {
            try {
                withTimeout(seconds * 1000) { while (!done(snapshot())) delay(5) }
            } catch (e: Exception) {
                val got = snapshot()
                fail("timed out with ${got.size} frames, last ${got.takeLast(5)}")
            }
        }
    }

    /** Holds the first frame [matching] inside the send until [release]; [sending] says it is there. */
    private class Stall(
        log: Log,
        matching: (String) -> Boolean,
    ) {
        val sending = CountDownLatch(1)
        private val released = CountDownLatch(1)

        init {
            log.hold = { line ->
                if (matching(line) && sending.count > 0) {
                    sending.countDown()
                    check(released.await(30, TimeUnit.SECONDS)) { "never released" }
                }
            }
        }

        fun awaitSending() = check(sending.await(10, TimeUnit.SECONDS)) { "the frame never went out" }

        fun release() = released.countDown()
    }

    private fun session(
        backend: SessionBackend,
        log: Log,
    ): Pair<RelaySession, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        return RelaySession(backend, EmptyPolicy, scope, log, onClose = {}) to scope
    }

    /** Waits until no coroutine of the session runs (parked REQs excluded by [parked]). */
    private fun CoroutineScope.awaitIdle(parked: Int = 0) =
        runBlocking {
            withTimeout(10_000) { while (coroutineContext[Job]!!.children.count { it.isActive } > parked) delay(5) }
        }

    // -- COUNT ----------------------------------------------------------------

    /** Counts to the filter's `since`; [gates] holds a count until the test releases it, cancelled or not. */
    private class GatedCounts : SessionBackend {
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        val returned = AtomicInteger()

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
            returned.incrementAndGet()
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
            // Every count returned and every COUNT coroutine ended: nothing more can go out.
            log.waitFor { backend.returned.get() == 150 }
            scope.awaitIdle()
            val stale = log.snapshot().filter { line -> (0 until 100).any { n -> line == "COUNT q$n ${1000 + 2 * n}" } }
            assertEquals(emptyList(), stale, "answers of COUNTs cancelled before their store returned")
            assertTrue(log.snapshot().none { it.startsWith("CLOSED") }, "${log.snapshot().filter { it.startsWith("CLOSED") }}")
            assertEquals(50, log.snapshot().size)
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
            log.waitFor { backend.returned.get() == 200 }
            scope.awaitIdle()
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
        val stall = Stall(log) { it == "COUNT c 1" }
        try {
            runBlocking {
                // Generation 1 answers from a worker thread, which then stalls inside the send.
                backend.gates[1L] = CompletableDeferred()
                session.receive(count("c", 1))
                scope.launch { backend.gates[1L]!!.complete(Unit) }
                stall.awaitSending()
                session.receive(count("c", 2))
            }
            stall.release()
            log.waitFor { "COUNT c 2" in it }
            scope.awaitIdle()
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
        val stall = Stall(log) { it == "COUNT c 1" }
        try {
            runBlocking {
                backend.gates[1L] = CompletableDeferred()
                session.receive(count("c", 1))
                scope.launch { backend.gates[1L]!!.complete(Unit) }
                stall.awaitSending()
                session.receive("""["CLOSE","c"]""")
            }
            stall.release()
            log.waitFor { it.size >= 2 }
            scope.awaitIdle()
            assertEquals(listOf("COUNT c 1", "CLOSED c error: no such subscription"), log.on("c"))
        } finally {
            scope.cancel()
        }
    }

    // -- REQ ------------------------------------------------------------------

    /**
     * A parked REQ on kind 1 sends [old] (or fails, with [failOld]) from a worker thread, then
     * parks; any other REQ sends its EOSE and parks. COUNTs answer 0.
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
        val stall = Stall(log) { it == stalled }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                stall.awaitSending()
                next.forEach { session.receive(it) }
            }
            stall.release()
            log.waitFor { it.size >= expected.size }
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

    @Test
    fun aCountFollowsTheFailedReqsClosedBeingSent() =
        oldFrameGoesFirst(
            "CLOSED s error: store down",
            """["COUNT","s",{"kinds":[2]}]""",
            expected = listOf("CLOSED s error: store down", "COUNT s 0"),
            failOld = true,
        )

    @Test
    fun aCloseFollowsTheFailedReqsClosedBeingSent() =
        oldFrameGoesFirst(
            "CLOSED s error: store down",
            """["CLOSE","s"]""",
            expected = listOf("CLOSED s error: store down", "CLOSED s error: no such subscription"),
            failOld = true,
        )

    @Test
    fun aThousandsOfRepliesQueuedBehindAStalledFrameAllGoOut() {
        val log = Log()
        val (session, scope) = session(Streams(), log)
        val stall = Stall(log) { it == "EVENT old" }
        val closes = 50_000
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                stall.awaitSending()
                // The first CLOSE ends the REQ; every later one is on an unknown id, answered
                // after the stalled frame.
                repeat(closes) { session.receive("""["CLOSE","s"]""") }
                session.receive("""["REQ","s",{"kinds":[2]}]""")
            }
            stall.release()
            log.waitFor(seconds = 30) { "EOSE s" in it }
            val got = log.on("s")
            assertEquals("EVENT old", got.first())
            assertEquals(closes - 1, got.count { it == "CLOSED s error: no such subscription" })
            assertEquals("EOSE s", got.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSinkErrorOnAQueuedReplyDoesNotStallTheId() {
        val log = Log()
        val (session, scope) = session(Streams(), log)
        val stall = Stall(log) { it == "EVENT old" }
        val stalledHold = log.hold
        // The transport fails with an Error on the queued reply, once.
        var failed = false
        log.hold = { line ->
            stalledHold(line)
            if (line == "CLOSED s error: no such subscription" && !failed) {
                failed = true
                throw NotImplementedError("transport bug")
            }
        }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                stall.awaitSending()
                session.receive("""["CLOSE","s"]""")
                session.receive("""["CLOSE","s"]""")
                session.receive("""["REQ","s",{"kinds":[2]}]""")
            }
            stall.release()
            log.waitFor { "EOSE s" in it }
            assertEquals(listOf("EVENT old", "EOSE s"), log.on("s"))
        } finally {
            scope.cancel()
        }
    }

    /**
     * A parked REQ on kind 5 sends [old] live from another thread, and fails once that frame is
     * in the sink: its CLOSED must not overtake the frame.
     */
    private inner class FailsWhileLiveFrameIsOut(
        val inSink: CountDownLatch,
    ) : SessionBackend {
        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            onEose()
            CoroutineScope(Dispatchers.Default).launch { onEach(old) }
            withContext(Dispatchers.IO) { check(inSink.await(10, TimeUnit.SECONDS)) }
            throw IllegalStateException("store down")
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ) = 0
    }

    @Test
    fun aFailedReqsClosedFollowsItsLiveFrameBeingSent() {
        val log = Log()
        val stall = Stall(log) { it == "EVENT old" }
        val (session, scope) = session(FailsWhileLiveFrameIsOut(stall.sending), log)
        try {
            runBlocking { session.receive("""["REQ","s",{"kinds":[5]}]""") }
            stall.awaitSending()
            // The failure happens while the frame is held; give it time to try to overtake.
            runBlocking { withTimeout(10_000) { while (scope.coroutineContext[Job]!!.children.any { it.isActive }) delay(5) } }
            stall.release()
            log.waitFor { it.size >= 3 }
            assertEquals(listOf("EOSE s", "EVENT old", "CLOSED s error: store down"), log.on("s"))
        } finally {
            scope.cancel()
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

    @Test
    fun aReReqFollowsTheOldReqsLiveEventBeingSent() {
        val live = LiveEventStore(EventStore(null), AcceptingIngest)
        val log = Log()
        val (session, scope) = session(live, log)
        val stall = Stall(log) { it == "EVENT old" }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                log.waitFor { "EOSE s" in it }
                // A live event for the old REQ, delivered on another thread.
                scope.launch { live.submit(old) {} }
                stall.awaitSending()
                session.receive("""["REQ","s",{"kinds":[2]}]""")
            }
            stall.release()
            log.waitFor { it.count { line -> line == "EOSE s" } == 2 }
            assertEquals(listOf("EOSE s", "EVENT old", "EOSE s"), log.on("s"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReplacementWaitingOnItsPredecessorHoldsNoLiveEventsAheadOfIt() {
        val store = EventStore(null)
        // Holds at most two live events per REQ before EOSE, then forwards them early.
        val live = LiveEventStore(store, AcceptingIngest, maxHeldBeforeEose = 2)
        val log = Log()
        val (session, scope) = session(live, log)
        val stall = Stall(log) { it == "EVENT old" }
        // Kind 7 (reactions): regular events, so the store keeps all five.
        val fresh = (10 until 15).map { event(it, 7, "new") }
        try {
            runBlocking {
                session.receive("""["REQ","s",{"kinds":[1]}]""")
                log.waitFor { "EOSE s" in it }
                scope.launch { live.submit(old) {} }
                stall.awaitSending()
                // The replacement waits for the old REQ's frame, while five of its events arrive.
                session.receive("""["REQ","s",{"kinds":[7]}]""")
                fresh.forEach {
                    store.insert(it)
                    live.submit(it) {}
                }
            }
            assertTrue(log.snapshot().none { it == "EVENT new" }, "the replacement sent events ahead of its predecessor's frame: ${log.snapshot()}")
            stall.release()
            log.waitFor { it.count { line -> line == "EOSE s" } == 2 }
            assertEquals(listOf("EOSE s", "EVENT old") + List(5) { "EVENT new" } + "EOSE s", log.on("s"))
        } finally {
            scope.cancel()
        }
    }
}
