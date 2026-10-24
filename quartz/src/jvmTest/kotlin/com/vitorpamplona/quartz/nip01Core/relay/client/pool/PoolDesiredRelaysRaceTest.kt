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

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desired relay set is recomputed from the registry after every change and
 * then emitted. Two concurrent changes must not let the older snapshot land
 * last: the pool would then disconnect a relay a live subscription still needs,
 * until some unrelated change happened to recompute the set.
 */
class PoolDesiredRelaysRaceTest {
    private val relays = (0 until 8).map { NormalizedRelayUrl("wss://relay$it.example.com/") }
    private val filters = listOf(Filter(kinds = listOf(1)))

    /**
     * Runs [rounds] rounds in which every thread changes its own entry once and
     * puts it back; when all threads are done with a round, [check] must hold.
     * Every round end is a fresh chance for a stale snapshot to be emitted last.
     */
    private fun hammer(
        threads: Int,
        rounds: Int,
        op: (thread: Int) -> Unit,
        check: () -> Unit,
    ) {
        var failure: Throwable? = null
        val barrier =
            CyclicBarrier(threads) {
                try {
                    check()
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                }
            }
        (0 until threads)
            .map { t ->
                thread {
                    repeat(rounds) {
                        op(t)
                        barrier.await()
                    }
                }
            }.forEach { it.join() }
        failure?.let { throw it }
    }

    @Test
    fun requestsEndWithTheRelaysTheirSubscriptionsWant() {
        val pool = PoolRequests()
        hammer(
            threads = 8,
            rounds = 20_000,
            op = { t ->
                pool.remove("sub$t")
                pool.addOrUpdate("sub$t", mapOf(relays[t] to filters), null)
            },
            check = { assertEquals(relays.toSet(), pool.desiredRelays.value) },
        )
    }

    @Test
    fun countsEndWithTheRelaysTheirQueriesWant() {
        val pool = PoolCounts()
        hammer(
            threads = 8,
            rounds = 20_000,
            op = { t ->
                pool.remove("q$t")
                pool.addOrUpdate("q$t", mapOf(relays[t] to filters))
            },
            check = { assertEquals(relays.toSet(), pool.relays.value) },
        )
    }
}
