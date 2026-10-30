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
package com.vitorpamplona.quartz.nip01Core.relay.prodbench

import com.sun.management.ThreadMXBean
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.FanoutExecutor
import com.vitorpamplona.quartz.nip01Core.relay.server.LiveFrameSink
import com.vitorpamplona.quartz.nip01Core.relay.server.NostrServer
import com.vitorpamplona.quartz.nip01Core.relay.server.RelaySession
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live fan-out cost as a websocket relay sees it: [SUBS] open `{"kinds":[1],"limit":1}` feeds
 * spread over connections that each belong to one of [LOOPS] single-threaded "event loops"
 * (Netty's model), and [EVENTS] published events that every feed matches.
 *
 * Each connection's sink is a [LiveFrameSink] that writes a frame on its loop. A frame that
 * arrives on another thread is handed to the loop as one task per frame, which is what a
 * Netty `channel.write` from outside the loop does. In [Mode.EXECUTOR] the sinks declare their
 * loop as their [FanoutExecutor], so frames arrive on the loop and are written directly.
 *
 * Reports, per delivery: wall-clock ns, CPU ns summed over every thread, and bytes allocated
 * summed over every thread (`ThreadMXBean`, so transient garbage counts). Also the publisher's
 * OK latency, publishing one event at a time and waiting for its OK: back-to-back (the next
 * publish right after the OK, so a slow fan-out backs up the writer) and paced (the next publish
 * once the previous event reached every feed). And the heap each idle connection and each idle
 * subscription keeps after a full GC.
 *
 * Small by default so `jvmTest` stays quick; scale it with
 * `-DfanoutBenchSubs=12000 -DfanoutBenchEvents=200 -DfanoutBenchSubsPerConn=1`.
 * No speed assertions: the numbers are informational.
 */
class LiveFanoutBenchmark {
    companion object {
        val SUBS = System.getProperty("fanoutBenchSubs")?.toInt() ?: 2_000
        val EVENTS = System.getProperty("fanoutBenchEvents")?.toInt() ?: 40
        val SUBS_PER_CONN = System.getProperty("fanoutBenchSubsPerConn")?.toInt() ?: 2
        const val LOOPS = 4
        const val WARMUP_EVENTS = 20
        const val PACED_EVENTS = 30

        private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        private const val PUBKEY = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
        private val SIG = "0".repeat(128)
    }

    /** One single-threaded loop. Its counters are only written from its own thread. */
    private class Loop(
        name: String,
    ) {
        @Volatile
        var thread: Thread? = null

        val executor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, name).also {
                    thread = it
                    it.isDaemon = true
                }
            }

        /** The loop as a [FanoutExecutor]: one instance per loop, shared by its sinks. */
        val fanout =
            object : FanoutExecutor {
                override fun execute(task: () -> Unit) = executor.execute(task)
            }

        @Volatile
        var frames = 0L
        var bytes = 0L

        fun inLoop() = Thread.currentThread() === thread

        fun write(
            framePrefix: String,
            body: String,
        ) {
            bytes += framePrefix.length + body.length + 1
            frames++
        }
    }

    private enum class Mode(
        val label: String,
    ) {
        CROSS_THREAD("frames handed to the loop per delivery"),
        EXECUTOR("sinks declare their loop as FanoutExecutor"),
    }

    private open class LoopSink(
        val loop: Loop,
        val eoses: AtomicInteger,
    ) : LiveFrameSink {
        override fun message(message: Message) {
            if (message is EoseMessage) eoses.incrementAndGet()
        }

        override fun raw(json: String) {}

        override fun liveEvent(
            framePrefix: String,
            body: String,
        ) {
            if (loop.inLoop()) {
                loop.write(framePrefix, body)
            } else {
                loop.executor.execute { loop.write(framePrefix, body) }
            }
        }
    }

    private fun hexId(n: Int): String = n.toString(16).padStart(64, '0')

    private fun event(n: Int) = Event(hexId(n), PUBKEY, 1_700_000_000L + n, 1, emptyArray(), "fanout benchmark $n", SIG)

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) {
            System.gc()
            Thread.sleep(50)
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun allThreadsAllocated(): Long = threads.getThreadAllocatedBytes(threads.allThreadIds).sum()

    private fun allThreadsCpu(): Long = threads.allThreadIds.sumOf { threads.getThreadCpuTime(it).coerceAtLeast(0) }

    private fun pct(
        sorted: LongArray,
        p: Double,
    ) = sorted[((sorted.size - 1) * p).toInt()] / 1e6

    @Test
    fun fanout() {
        Mode.entries.forEach { run(it) }
    }

    private fun run(mode: Mode) =
        runBlocking {
            val loops = List(LOOPS) { Loop("bench-loop-$it") }
            val server =
                NostrServer(store = EventStore(null), policyBuilder = { EmptyPolicy }, parentContext = Dispatchers.Default + SupervisorJob())
            val eoses = AtomicInteger()

            val pendingOk = AtomicReference<CompletableDeferred<Unit>?>(null)
            val publisher =
                server.connect(
                    object : LiveFrameSink {
                        override fun message(message: Message) {
                            if (message is OkMessage) pendingOk.get()?.complete(Unit)
                        }

                        override fun raw(json: String) {}

                        override fun liveEvent(
                            framePrefix: String,
                            body: String,
                        ) {}
                    },
                )

            val conns = SUBS / SUBS_PER_CONN
            val heapBefore = usedHeapAfterGc()
            val sessions = ArrayList<RelaySession>(conns)
            repeat(conns) { i ->
                val loop = loops[i % LOOPS]
                sessions += server.connect(sinkFor(mode, loop, eoses))
            }
            val heapConns = usedHeapAfterGc()
            sessions.forEachIndexed { i, s ->
                repeat(SUBS_PER_CONN) { k -> s.receive("""["REQ","feed$k",{"kinds":[1],"limit":1}]""") }
            }
            withTimeout(60_000) { while (eoses.get() < conns * SUBS_PER_CONN) delay(1) }
            val heapSubs = usedHeapAfterGc()
            val subs = conns * SUBS_PER_CONN

            var seq = 0

            suspend fun publish(n: Int): Long {
                val ok = CompletableDeferred<Unit>()
                pendingOk.set(ok)
                val json = OptimizedJsonMapper.toJson(EventCmd(event(n)))
                val t0 = System.nanoTime()
                publisher.receive(json)
                withTimeout(60_000) { ok.await() }
                return System.nanoTime() - t0
            }

            fun framesSoFar() = loops.sumOf { it.frames }

            suspend fun awaitFrames(target: Long) {
                withTimeout(120_000) { while (framesSoFar() < target) delay(1) }
            }

            repeat(WARMUP_EVENTS) { publish(seq++) }
            awaitFrames(WARMUP_EVENTS.toLong() * subs)

            val okLatency = LongArray(EVENTS)
            val alloc0 = allThreadsAllocated()
            val cpu0 = allThreadsCpu()
            val t0 = System.nanoTime()
            repeat(EVENTS) { okLatency[it] = publish(seq++) }
            awaitFrames((WARMUP_EVENTS + EVENTS).toLong() * subs)
            val wall = System.nanoTime() - t0
            val cpu = allThreadsCpu() - cpu0
            val alloc = allThreadsAllocated() - alloc0

            val deliveries = EVENTS.toLong() * subs
            assertEquals((WARMUP_EVENTS + EVENTS).toLong() * subs, framesSoFar())
            okLatency.sort()

            // Paced: each event is published once the previous one reached every feed, the way
            // a relay at a few events a second sees it. The OK then shows whether a publish
            // waits for its own fan-out, with no backlog from the event before it.
            val pacedOk = LongArray(PACED_EVENTS)
            var pacedTarget = framesSoFar()
            repeat(PACED_EVENTS) {
                pacedOk[it] = publish(seq++)
                pacedTarget += subs
                awaitFrames(pacedTarget)
            }
            pacedOk.sort()
            println("LiveFanoutBenchmark [${mode.name}: ${mode.label}] $subs subs on $conns connections, $LOOPS loops, $EVENTS events")
            println("  per delivery:  ${"%8.1f".format(wall.toDouble() / deliveries)} ns wall  ${"%8.1f".format(cpu.toDouble() / deliveries)} ns cpu  ${"%7.1f".format(alloc.toDouble() / deliveries)} B allocated")
            println("  OK, paced:        p50 ${"%7.3f".format(pct(pacedOk, 0.5))} ms  p90 ${"%7.3f".format(pct(pacedOk, 0.9))} ms  max ${"%7.3f".format(pct(pacedOk, 1.0))} ms")
            println("  OK, back-to-back: p50 ${"%7.3f".format(pct(okLatency, 0.5))} ms  p90 ${"%7.3f".format(pct(okLatency, 0.9))} ms  max ${"%7.3f".format(pct(okLatency, 1.0))} ms")
            println("  retained heap: ${(heapConns - heapBefore) / conns} B/connection  ${(heapSubs - heapConns) / subs} B/subscription")

            sessions.forEach { it.close() }
            publisher.close()
            server.close()
            loops.forEach {
                it.executor.shutdown()
                it.executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

    private fun sinkFor(
        mode: Mode,
        loop: Loop,
        eoses: AtomicInteger,
    ): LiveFrameSink =
        when (mode) {
            Mode.CROSS_THREAD -> {
                LoopSink(loop, eoses)
            }

            Mode.EXECUTOR -> {
                object : LoopSink(loop, eoses) {
                    override val liveExecutor = loop.fanout
                }
            }
        }
}
