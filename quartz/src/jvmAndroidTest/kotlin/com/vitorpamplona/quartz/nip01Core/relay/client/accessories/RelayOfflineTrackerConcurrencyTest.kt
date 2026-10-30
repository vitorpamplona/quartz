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
 * `var x = x + y` that lost writes, and its unsafely published sets crashed a reader with
 * `ConcurrentModificationException` from inside `Set.plus` (seen on 1.19.0, ARM).
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

    @Test
    fun concurrentFailuresAreAllRecordedAndConnectsClearThem() {
        runBlocking {
            repeat(20) { burst ->
                val client = CapturingClient()
                val tracker = RelayOfflineTracker(client)
                val listener = client.captured ?: error("RelayOfflineTracker did not register a listener")

                val relays =
                    (0 until 512).map {
                        FakeRelayClient(NormalizedRelayUrl("wss://relay-$burst-$it.example/"), connected = false)
                    }
                val workers = 32

                withContext(Dispatchers.IO) {
                    (0 until workers)
                        .map { workerId ->
                            async {
                                relays.forEachIndexed { idx, relay ->
                                    if (idx % workers == workerId) listener.onCannotConnect(relay, "failed")
                                }
                            }
                        }.awaitAll()
                }

                val offline = tracker.cannotConnectRelays
                assertEquals(relays.size, offline.size, "burst $burst lost ${relays.size - offline.size} failures")
                assertTrue(relays.all { it.url in offline }, "burst $burst recorded a relay that never failed")

                withContext(Dispatchers.IO) {
                    (0 until workers)
                        .map { workerId ->
                            async {
                                relays.forEachIndexed { idx, relay ->
                                    if (idx % workers == workerId) listener.onConnected(relay, 0, false)
                                }
                            }
                        }.awaitAll()
                }

                assertTrue(tracker.cannotConnectRelays.isEmpty(), "burst $burst kept ${tracker.cannotConnectRelays.size} relays that connected")
                tracker.destroy()
            }
        }
    }
}
