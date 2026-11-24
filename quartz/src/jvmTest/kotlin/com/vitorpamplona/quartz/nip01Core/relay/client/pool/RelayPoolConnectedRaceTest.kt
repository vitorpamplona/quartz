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
package com.vitorpamplona.quartz.nip01Core.relay.client.pool

import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.FakeWebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RelayPool.connectedRelays] against reports that race the pool's own membership changes, on
 * real threads. Whatever the interleaving, once everything settles the set must say what the
 * pool's members say: a url is in it exactly when the url's current client is connected.
 */
class RelayPoolConnectedRaceTest {
    private val url = NormalizedRelayUrl("wss://relay.example.com/")

    private fun race(
        a: () -> Unit,
        b: () -> Unit,
    ) {
        val start = CyclicBarrier(2)
        val t1 =
            thread {
                start.await()
                a()
            }
        val t2 =
            thread {
                start.await()
                b()
            }
        t1.join()
        t2.join()
    }

    private fun RelayPool.truth() = buildSet { getRelay(url)?.let { if (it.isConnected()) add(url) } }

    // The client a pool sample replaced reports its close while the pool removes it and its
    // successor connects: the successor's url must stay.
    @Test
    fun aReplacedClientsCloseRacingTheSwapLeavesTheSuccessorConnected() {
        repeat(ROUNDS) { round ->
            val sockets = FakeWebsocketBuilder()
            val pool = RelayPool(sockets)
            pool.getOrCreateRelay(url).connect()
            val old = sockets.lastListener
            old.onOpen(pingMillis = 1, compression = false)

            race(
                { old.onClosed(1000, "relay closed") },
                {
                    pool.removeRelay(url)
                    pool.getOrCreateRelay(url).connect()
                    sockets.lastListener.onOpen(pingMillis = 1, compression = false)
                },
            )

            assertEquals(pool.truth(), pool.connectedRelays.value, "round $round")
        }
    }

    // A client finishing its handshake as the pool removes it: a url with no member is not connected.
    @Test
    fun anOpenRacingTheRemovalLeavesNoUrlBehind() {
        repeat(ROUNDS) { round ->
            val sockets = FakeWebsocketBuilder()
            val pool = RelayPool(sockets)
            pool.getOrCreateRelay(url).connect()
            val session = sockets.lastListener

            race(
                { session.onOpen(pingMillis = 1, compression = false) },
                { pool.removeRelay(url) },
            )

            assertEquals(pool.truth(), pool.connectedRelays.value, "round $round")
        }
    }

    // Membership: a member leaving and coming back at once must stay listed.
    @Test
    fun aRelayRemovedAndAddedAtOnceStaysAvailable() {
        repeat(ROUNDS) { round ->
            val pool = RelayPool(FakeWebsocketBuilder())
            pool.getOrCreateRelay(url)

            race(
                { pool.removeRelay(url) },
                { pool.addRelay(url) },
            )

            val members = buildSet { if (pool.getRelay(url) != null) add(url) }
            assertEquals(members, pool.availableRelays.value, "round $round")
        }
    }

    private companion object {
        const val ROUNDS = 20_000
    }
}
