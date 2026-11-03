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

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.DynamicSubscription
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.ResumePolicy
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.resume
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import com.sun.management.ThreadMXBean as SunThreadMXBean

/**
 * [CaughtUpResume] on its own: what it adds to each event a subscription is handed, and to each
 * REQ the pool writes on a connection. `LiveSubscriptionCostBenchmark` measures the same paths
 * end to end, where this cost is too small to separate from the machine's noise.
 *
 * Opt-in: `LIVE_SUB_BENCH=1 ./gradlew :quartz:jvmTest --tests "*ResumePolicyCostBenchmark" --rerun`
 */
class ResumePolicyCostBenchmark {
    private val enabled = System.getenv("LIVE_SUB_BENCH") != null
    private val threads = ManagementFactory.getThreadMXBean() as SunThreadMXBean

    private fun timeNanos(
        operations: Int,
        block: () -> Unit,
    ): Pair<Double, Double> {
        System.gc()
        val cpu0 = threads.currentThreadCpuTime
        val alloc0 = threads.currentThreadAllocatedBytes
        block()
        return (threads.currentThreadCpuTime - cpu0).toDouble() / operations to
            (threads.currentThreadAllocatedBytes - alloc0).toDouble() / operations
    }

    private fun report(
        name: String,
        samples: List<Pair<Double, Double>>,
        unit: String,
    ) {
        val ns = samples.map { it.first }.sorted()[samples.size / 2]
        val bytes = samples.map { it.second }.sorted()[samples.size / 2]
        println("BENCH   %-44s %8.1f ns/%s  %6.0f B/%s".format(name, ns, unit, bytes, unit))
    }

    @Test
    fun perEventAndPerReq() {
        if (!enabled) return
        val relay = "wss://bench-relay.local".normalizeRelayUrl()
        val now = TimeUtils.now()
        val n = 2_000_000
        // Each newer than the last, all in the past: every one moves the point.
        val newer = (1..200_000).map { SyntheticEvents.fakeEvent(idSeed = it, createdAt = now - 300_000 + it) }
        val older = (1..1000).map { SyntheticEvents.fakeEvent(idSeed = it, createdAt = now - 100_000) }
        val follows = (1..300).map { SyntheticEvents.hexId(it) }
        val filters =
            listOf(
                Filter(kinds = listOf(1, 6, 7), authors = follows, since = now - 86_400),
                Filter(kinds = listOf(0), authors = follows),
                Filter(kinds = listOf(7, 9735), tags = mapOf("p" to listOf(SyntheticEvents.hexId(9999)))),
            )

        fun liveSameEvents(): Pair<Double, Double> {
            val resume = CaughtUpResume()
            older.forEach { resume.onEvent(relay, it, true) }
            return timeNanos(n) { for (i in 0 until n) resume.onEvent(relay, older[i % 1000], true) }
        }

        fun liveNewer(): Pair<Double, Double> {
            val resume = CaughtUpResume()
            return timeNanos(newer.size) { for (e in newer) resume.onEvent(relay, e, true) }
        }

        fun reqResume(): Pair<Double, Double> {
            val resume = CaughtUpResume()
            resume.onEvent(relay, newer.last(), false)
            resume.onEose(relay, filters)
            val reqs = 20_000
            var sink = 0
            return timeNanos(reqs) { for (i in 0 until reqs) sink += resume.resume(relay, filters)?.size ?: 0 }.also { check(sink == reqs * filters.size) }
        }

        val cases =
            listOf(
                Triple("onEvent, live, not newer (most events)", ::liveSameEvents, "event"),
                Triple("onEvent, live, newer (moves the point)", ::liveNewer, "event"),
                Triple("resume() of 3 filters, 300 authors (per REQ)", ::reqResume, "REQ"),
            )
        repeat(3) { cases.forEach { it.second() } }
        val samples = cases.map { mutableListOf<Pair<Double, Double>>() }
        repeat(11) { cases.forEachIndexed { i, c -> samples[i] += c.second() } }
        println("BENCH CaughtUpResume alone, median of 11")
        cases.forEachIndexed { i, c -> report(c.first, samples[i], c.third) }
    }

    /** Opens as soon as it is dialled; swallows what is sent. */
    private class OpeningBuilder : WebsocketBuilder {
        val listeners = HashMap<NormalizedRelayUrl, WebSocketListener>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            listeners[url] = out
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() = out.onOpen(0, false)

                override fun disconnect() {}

                override fun send(msg: String) = true
            }
        }
    }

    /** A client with 100 caught-up subscriptions on 60 relays, and a function that runs one reconnect wave. */
    private fun waveRig(resume: () -> ResumePolicy?): Pair<() -> Unit, () -> Unit> {
        val builder = OpeningBuilder()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(builder, scope)
        client.connect()
        val relays = (1..60).map { "wss://bench-relay-$it.local".normalizeRelayUrl() }
        val now = TimeUtils.now()
        val follows = (1..300).map { SyntheticEvents.hexId(it) }
        val filters =
            listOf(
                Filter(kinds = listOf(1, 6, 7), authors = follows, since = now - 86_400),
                Filter(kinds = listOf(0), authors = follows),
                Filter(kinds = listOf(7, 9735), tags = mapOf("p" to listOf(SyntheticEvents.hexId(9999)))),
            )
        val subs = (1..100).map { DynamicSubscription(client, { relays.associateWith { filters } }, resume()) }
        val relayClients = relays.map { client.getOrCreateRelay(it) }
        relayClients.forEach { it.connect() }
        runBlocking {
            relayClients.forEach { relayClient ->
                subs.forEachIndexed { i, sub ->
                    client.onIncomingMessage(relayClient, "", EventMessage(sub.subId, SyntheticEvents.fakeEvent(idSeed = i + 1, createdAt = now - 60)))
                    client.onIncomingMessage(relayClient, "", EoseMessage(sub.subId))
                }
            }
        }
        val wave = {
            relayClients.forEach { relayClient ->
                builder.listeners.getValue(relayClient.url).onClosed(1000, "bench")
                relayClient.connect()
            }
        }
        val close = {
            client.disconnect()
            scope.cancel()
        }
        return wave to close
    }

    /** The same reconnect wave on the same code, with each subscription's resume off and on. */
    @Test
    fun reconnectWaveResumeOffAndOn() {
        if (!enabled) return
        val (off, closeOff) = waveRig { null }
        val (on, closeOn) = waveRig { CaughtUpResume() }
        try {
            repeat(5) {
                off()
                on()
            }
            val samples = listOf(mutableListOf<Pair<Double, Double>>(), mutableListOf())
            repeat(25) {
                samples[0] += timeNanos(1) { off() }
                samples[1] += timeNanos(1) { on() }
            }
            println("BENCH reconnect wave, 100 subscriptions x 60 relays, same code, median of 25 alternating")
            listOf("resume off (REQs re-sent as given)", "resume on (CaughtUpResume)").forEachIndexed { i, name ->
                val cpu = samples[i].map { it.first }.sorted()[12] / 1e6
                val kb = samples[i].map { it.second }.sorted()[12] / 1024
                println("BENCH   %-44s %8.1f ms CPU  %,10.0f KB allocated per wave".format(name, cpu, kb))
            }
        } finally {
            closeOff()
            closeOn()
        }
    }
}
