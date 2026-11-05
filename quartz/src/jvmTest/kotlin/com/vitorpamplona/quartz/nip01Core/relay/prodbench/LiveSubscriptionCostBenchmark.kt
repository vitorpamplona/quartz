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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.subscribeEventBatches
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.subscribeEvents
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionHandle
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.subscribe
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import com.sun.management.ThreadMXBean as SunThreadMXBean

/**
 * What a live subscription costs the client, offline: the callback subscription per incoming
 * event, and a whole reconnect wave at an app's scale. It uses only APIs that exist before and
 * after resumable subscriptions (`subscribe(relay, filter) { }`, `subscribe(filters = { … }) { }`,
 * `NostrClient.onIncomingMessage`), so the same file runs on both and the two are compared.
 * `perIncomingEventThroughFlows` adds the `subscribeEvents` and `subscribeEventBatches` flows, which
 * exist only after, with the callback subscription beside them as the reference.
 *
 * Everything runs on the calling thread: a socket that opens as soon as it is dialled makes
 * `connect()` run the whole sync (the pool's per-connection REQ replay, serialization included),
 * and messages are handed straight to `NostrClient.onIncomingMessage`. So each measurement reads
 * that thread's CPU time and allocated bytes, not only the wall clock.
 *
 * Opt-in: `LIVE_SUB_BENCH=1 ./gradlew :quartz:jvmTest --tests "*LiveSubscriptionCostBenchmark" --rerun`
 */
class LiveSubscriptionCostBenchmark {
    private val enabled = System.getenv("LIVE_SUB_BENCH") != null
    private val threads = ManagementFactory.getThreadMXBean() as SunThreadMXBean

    /** A socket that opens the moment it is dialled and swallows what is sent, counting it. */
    private class OpeningSocket(
        private val out: WebSocketListener,
        private val sent: AtomicLong,
        private val sentBytes: AtomicLong,
        private val lastSent: AtomicReference<String?>,
    ) : WebSocket {
        override fun needsReconnect() = false

        override fun connect() = out.onOpen(0, false)

        override fun disconnect() {}

        override fun send(msg: String): Boolean {
            sent.incrementAndGet()
            sentBytes.addAndGet(msg.length.toLong())
            lastSent.set(msg)
            return true
        }
    }

    private class OpeningBuilder : WebsocketBuilder {
        val sent = AtomicLong()
        val sentBytes = AtomicLong()
        val lastSent = AtomicReference<String?>(null)
        val listeners = HashMap<NormalizedRelayUrl, WebSocketListener>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            listeners[url] = out
            return OpeningSocket(out, sent, sentBytes, lastSent)
        }
    }

    /** The subscription id behind a handle, whichever class it is. */
    private fun subIdOf(handle: SubscriptionHandle): String = handle.javaClass.getMethod("getSubId").invoke(handle) as String

    private class Sample(
        val cpuNanos: Long,
        val allocatedBytes: Long,
    )

    private inline fun measure(block: () -> Unit): Sample {
        System.gc() // a collection owed to the previous sample isn't charged to this one
        val cpu0 = threads.currentThreadCpuTime
        val alloc0 = threads.currentThreadAllocatedBytes
        block()
        return Sample(threads.currentThreadCpuTime - cpu0, threads.currentThreadAllocatedBytes - alloc0)
    }

    private fun List<Long>.median() = sorted()[size / 2]

    // ------------------------------------------------------------------
    // Per incoming event
    // ------------------------------------------------------------------

    private enum class Delivery { LIVE_NEWER, LIVE_OLDER, STORED }

    private val eventCount = 200_000

    /** ns and bytes per event, through NostrClient's dispatch and the subscription's own callback. */
    private fun perEvent(
        delivery: Delivery,
        callbackSubscription: Boolean,
    ): Sample {
        val builder = OpeningBuilder()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(builder, scope)
        try {
            client.connect()
            val relay = "ws://bench-relay.local".normalizeRelayUrl()
            val filter = Filter(kinds = listOf(1))
            val received = AtomicLong()
            val subId =
                if (callbackSubscription) {
                    subIdOf(client.subscribe(relay, filter) { received.incrementAndGet() })
                } else {
                    "raw".also {
                        client.subscribe(
                            it,
                            mapOf(relay to listOf(filter)),
                            object : SubscriptionListener {
                                override suspend fun onEvent(
                                    event: Event,
                                    isLive: Boolean,
                                    relay: NormalizedRelayUrl,
                                    forFilters: List<Filter>?,
                                ) {
                                    received.incrementAndGet()
                                }
                            },
                        )
                    }
                }
            val relayClient = client.getOrCreateRelay(relay)
            relayClient.connect()

            val now = TimeUtils.now()
            val messages =
                (1..eventCount).map { i ->
                    val createdAt =
                        when (delivery) {
                            Delivery.LIVE_NEWER -> now - eventCount + i // each one newer than the last
                            Delivery.LIVE_OLDER, Delivery.STORED -> now - 100_000 // none moves anything
                        }
                    EventMessage(subId, SyntheticEvents.fakeEvent(idSeed = i, createdAt = createdAt))
                }

            return runBlocking {
                if (delivery != Delivery.STORED) client.onIncomingMessage(relayClient, "", EoseMessage(subId))
                val sample = measure { messages.forEach { client.onIncomingMessage(relayClient, "", it) } }
                check(received.get() == eventCount.toLong()) { "delivered ${received.get()} of $eventCount" }
                Sample(sample.cpuNanos / eventCount, sample.allocatedBytes / eventCount)
            }
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun perIncomingEvent() {
        if (!enabled) return
        val cases =
            listOf(
                "raw listener, live" to { perEvent(Delivery.LIVE_NEWER, callbackSubscription = false) },
                "subscribe { }, live, each newer" to { perEvent(Delivery.LIVE_NEWER, callbackSubscription = true) },
                "subscribe { }, live, not newer" to { perEvent(Delivery.LIVE_OLDER, callbackSubscription = true) },
                "subscribe { }, stored (before EOSE)" to { perEvent(Delivery.STORED, callbackSubscription = true) },
            )
        repeat(3) { cases.forEach { it.second() } } // warm up
        // Round-robin, so drift in the machine's speed lands on every case alike.
        val samples = cases.map { mutableListOf<Sample>() }
        repeat(15) { cases.forEachIndexed { i, case -> samples[i] += case.second() } }
        println("BENCH per incoming event (${System.getenv("BENCH_LABEL") ?: "?"}), $eventCount events, median of 15")
        cases.forEachIndexed { i, (name, _) ->
            val samples = samples[i]
            println(
                "BENCH   %-38s %6d ns/event  %5d B/event".format(
                    name,
                    samples.map { it.cpuNanos }.median(),
                    samples.map { it.allocatedBytes }.median(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Per incoming event, through the Flow accessories
    // ------------------------------------------------------------------

    private enum class FlowKind { EVENTS, BATCHES }

    /**
     * ns and bytes per live event, from the client's dispatch to the collector's block, both on this
     * thread: the flow is collected in a coroutine of the same `runBlocking`, so a hand-over that
     * waits for the collector runs the collector here, and its cost is counted too.
     */
    private fun perFlowEvent(kind: FlowKind): Sample {
        val builder = OpeningBuilder()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(builder, scope)
        try {
            client.connect()
            val relay = "ws://bench-relay.local".normalizeRelayUrl()
            val filters = listOf(Filter(kinds = listOf(1)))
            val received = AtomicLong()
            return runBlocking {
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        when (kind) {
                            FlowKind.EVENTS -> client.subscribeEvents(relay = relay, filters = filters).collect { received.incrementAndGet() }
                            FlowKind.BATCHES -> client.subscribeEventBatches(relay = relay, filters = filters).collect { received.addAndGet(it.size.toLong()) }
                        }
                    }
                yield() // a callbackFlow's producer starts on its first dispatch
                val relayClient = client.getOrCreateRelay(relay)
                relayClient.connect()
                val req = builder.lastSent.get() ?: error("no REQ was written")
                val subId = req.substringAfter("[\"REQ\",\"").substringBefore('"')

                val now = TimeUtils.now()
                val messages =
                    (1..eventCount).map { i ->
                        EventMessage(subId, SyntheticEvents.fakeEvent(idSeed = i, createdAt = now - eventCount + i))
                    }
                client.onIncomingMessage(relayClient, "", EoseMessage(subId))
                yield()
                val sample =
                    measure {
                        messages.forEach { client.onIncomingMessage(relayClient, "", it) }
                        while (received.get() < eventCount) yield()
                    }
                check(received.get() == eventCount.toLong()) { "delivered ${received.get()} of $eventCount" }
                collector.cancel()
                Sample(sample.cpuNanos / eventCount, sample.allocatedBytes / eventCount)
            }
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun perIncomingEventThroughFlows() {
        if (!enabled) return
        val cases =
            listOf(
                "subscribe { }, live, each newer" to { perEvent(Delivery.LIVE_NEWER, callbackSubscription = true) },
                "subscribeEvents, live, each newer" to { perFlowEvent(FlowKind.EVENTS) },
                "subscribeEventBatches, live, each newer" to { perFlowEvent(FlowKind.BATCHES) },
            )
        repeat(3) { cases.forEach { it.second() } } // warm up
        val samples = cases.map { mutableListOf<Sample>() }
        repeat(15) { cases.forEachIndexed { i, case -> samples[i] += case.second() } }
        println("BENCH per incoming event, through flows (${System.getenv("BENCH_LABEL") ?: "?"}), $eventCount events, median of 15")
        cases.forEachIndexed { i, (name, _) ->
            val samples = samples[i]
            println(
                "BENCH   %-40s %6d ns/event  %5d B/event".format(
                    name,
                    samples.map { it.cpuNanos }.median(),
                    samples.map { it.allocatedBytes }.median(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Reconnect wave
    // ------------------------------------------------------------------

    private val relayCount = 60
    private val subscriptionCount = 100

    /** Every subscription on every relay is caught up, then every connection drops and comes back. */
    @Test
    fun reconnectWave() {
        if (!enabled) return
        val builder = OpeningBuilder()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(builder, scope)
        try {
            client.connect()
            val relays = (1..relayCount).map { "wss://bench-relay-$it.local".normalizeRelayUrl() }
            val now = TimeUtils.now()
            val follows = (1..300).map { SyntheticEvents.hexId(it) }
            // Three filters per relay, the shape of an app's home feed, profiles and notifications.
            val filters =
                listOf(
                    Filter(kinds = listOf(1, 6, 7), authors = follows, since = now - 86_400),
                    Filter(kinds = listOf(0), authors = follows),
                    Filter(kinds = listOf(7, 9735), tags = mapOf("p" to listOf(SyntheticEvents.hexId(9999)))),
                )
            val subIds =
                (1..subscriptionCount).map { subIdOf(client.subscribe(filters = { relays.associateWith { filters } }) { }) }
            val relayClients = relays.map { client.getOrCreateRelay(it) }
            relayClients.forEach { it.connect() }

            // Each subscription is handed one stored event on each relay, then reaches EOSE.
            runBlocking {
                relayClients.forEach { relayClient ->
                    subIds.forEachIndexed { i, subId ->
                        client.onIncomingMessage(relayClient, "", EventMessage(subId, SyntheticEvents.fakeEvent(idSeed = i + 1, createdAt = now - 60)))
                        client.onIncomingMessage(relayClient, "", EoseMessage(subId))
                    }
                }
            }

            fun wave(): Triple<Sample, Long, Long> {
                val sent0 = builder.sent.get()
                val bytes0 = builder.sentBytes.get()
                val sample =
                    measure {
                        relayClients.forEach { relayClient ->
                            builder.listeners.getValue(relayClient.url).onClosed(1000, "bench")
                            relayClient.connect()
                        }
                    }
                return Triple(sample, builder.sent.get() - sent0, builder.sentBytes.get() - bytes0)
            }

            repeat(5) { wave() } // warm up
            val waves = (1..25).map { wave() }
            val reqs = waves.first().second
            check(waves.all { it.second == reqs }) { "REQs per wave varied: ${waves.map { it.second }}" }
            println("BENCH reconnect wave (${System.getenv("BENCH_LABEL") ?: "?"}): $subscriptionCount subscriptions x $relayCount relays x ${filters.size} filters, median of 25")
            println(
                "BENCH   %,d REQs  %,d bytes sent  %.1f ms CPU  %,d KB allocated per wave".format(
                    reqs,
                    waves.first().third,
                    waves.map { it.first.cpuNanos }.median() / 1e6,
                    waves.map { it.first.allocatedBytes }.median() / 1024,
                ),
            )
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }
}
