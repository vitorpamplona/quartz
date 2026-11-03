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

import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SubscriptionListener.filtersForSync]: when the pool writes a subscription's REQ on a connection
 * ([PoolRequests.syncState]: a connect, a reconnect, the replay after an AUTH), it sends what the
 * listener names instead of the filters it was given, and only for that REQ.
 */
class PoolRequestsFiltersForSyncTest {
    private val relay = NormalizedRelayUrl("wss://relay.example/")
    private val given = listOf(Filter(kinds = listOf(1), since = 100))

    private fun listener(answer: (List<Filter>) -> List<Filter>?) =
        object : SubscriptionListener {
            override fun filtersForSync(
                relay: NormalizedRelayUrl,
                filters: List<Filter>,
            ) = answer(filters)
        }

    private fun PoolRequests.sync(): List<ReqCmd> {
        val sent = mutableListOf<Command>()
        syncState(relay) { sent += it }
        return sent.filterIsInstance<ReqCmd>()
    }

    @Test
    fun aSyncWritesTheListenersFilters() {
        val pool = PoolRequests()
        var asked: List<Filter>? = null
        pool.addOrUpdate(
            "live",
            mapOf(relay to given),
            listener {
                asked = it
                listOf(Filter(kinds = listOf(1), since = 500))
            },
        )

        val req = pool.sync().single()
        assertEquals(given, asked, "the listener is shown the filters it subscribed with")
        assertEquals(500L, req.filters.single().since)
        assertEquals(given, pool.getSubscriptionFiltersOrNull("live")?.get(relay), "the subscription's own filters are unchanged")
    }

    @Test
    fun noAnswerOrAFailureSendsTheFiltersAsGiven() {
        val pool = PoolRequests()
        pool.addOrUpdate("quiet", mapOf(relay to given), listener { null })
        pool.addOrUpdate("empty", mapOf(relay to given), listener { emptyList() })
        pool.addOrUpdate("broken", mapOf(relay to given), listener { error("boom") })
        pool.addOrUpdate("other", mapOf(relay to given), null)

        val sent = pool.sync().associate { it.subId to it.filters }
        assertEquals(setOf("quiet", "empty", "broken", "other"), sent.keys, "one listener failing breaks nobody's sync")
        sent.values.forEach { assertEquals(given, it) }
    }

    @Test
    fun eachSyncAsksAgain() {
        val pool = PoolRequests()
        var since = 200L
        pool.addOrUpdate("live", mapOf(relay to given), listener { listOf(Filter(kinds = listOf(1), since = since)) })

        assertEquals(
            200L,
            pool
                .sync()
                .single()
                .filters
                .single()
                .since,
        )
        pool.onConnecting(relay) // the next connection
        since = 300L
        assertEquals(
            300L,
            pool
                .sync()
                .single()
                .filters
                .single()
                .since,
        )
    }
}
