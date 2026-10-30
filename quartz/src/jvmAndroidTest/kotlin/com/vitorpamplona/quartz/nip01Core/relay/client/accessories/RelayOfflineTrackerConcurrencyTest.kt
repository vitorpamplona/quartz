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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.testing.FakeRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each relay reports its connection callbacks on its own thread (OkHttp's dispatcher, one per
 * relay), so a burst of relays failing at once, as on a network change, writes
 * [RelayOfflineTracker.cannotConnectRelays] from many threads at the same time. As a plain
 * `var x = x + y` that lost writes, and a set published without a barrier could change under a
 * thread iterating it: Amethyst 1.19.0 crashed on ARM with `ConcurrentModificationException`
 * inside the next failure's `Set.plus`. That crash needs a weak memory model to show; the lost
 * writes show on any JVM, so they are what this test pins.
 */
class RelayOfflineTrackerConcurrencyTest {
    private class CapturingClient(
        private val delegate: INostrClient = EmptyNostrClient(),
    ) : INostrClient by delegate {
        @Volatile var captured: RelayConnectionListener? = null

        override fun addConnectionListener(listener: RelayConnectionListener) {
            captured = listener
        }
    }

    private suspend fun onEachInParallel(
        relays: List<FakeRelayClient>,
        action: (FakeRelayClient) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val workers = 32
        (0 until workers)
            .map { workerId ->
                async {
                    relays.forEachIndexed { idx, relay ->
                        if (idx % workers == workerId) action(relay)
                    }
                }
            }.awaitAll()
    }

    @Test
    fun concurrentFailuresAndConnectsAreAllRecorded() {
        runBlocking {
            repeat(20) { burst ->
                val client = CapturingClient()
                val tracker = RelayOfflineTracker(client)
                try {
                    val listener = client.captured ?: error("RelayOfflineTracker did not register a listener")

                    val relays =
                        (0 until 1024).map {
                            FakeRelayClient(NormalizedRelayUrl("wss://relay-$burst-$it.example/"), connected = false)
                        }
                    val (first, second) = relays.chunked(512)

                    // Failures only: every one must be recorded.
                    onEachInParallel(first) { listener.onCannotConnect(it, "failed") }

                    val offline = tracker.cannotConnectRelays
                    assertEquals(first.size, offline.size, "burst $burst lost ${first.size - offline.size} failures")
                    assertTrue(first.all { it.url in offline }, "burst $burst recorded a relay that never failed")

                    // Removals racing additions: half of the first batch reconnects while the
                    // second batch fails, each list split over the same workers.
                    val reconnecting = first.filterIndexed { idx, _ -> idx % 2 == 0 }.toSet()
                    val stillDown = first.filterNot { it in reconnecting }
                    // Interleaved, so every worker both removes and adds.
                    val mixed = reconnecting.zip(second).flatMap { (up, down) -> listOf(up, down) }
                    onEachInParallel(mixed + second.drop(reconnecting.size)) { relay ->
                        if (relay in reconnecting) listener.onConnected(relay, 0, false) else listener.onCannotConnect(relay, "failed")
                    }

                    val expected = (stillDown + second).map { it.url }.toSet()
                    assertEquals(expected, tracker.cannotConnectRelays, "burst $burst lost a write while connects and failures raced")

                    // Connects only: every one must clear its relay.
                    onEachInParallel(relays) { listener.onConnected(it, 0, false) }

                    assertTrue(tracker.cannotConnectRelays.isEmpty(), "burst $burst kept ${tracker.cannotConnectRelays.size} relays that connected")
                } finally {
                    tracker.destroy()
                }
            }
        }
    }
}
