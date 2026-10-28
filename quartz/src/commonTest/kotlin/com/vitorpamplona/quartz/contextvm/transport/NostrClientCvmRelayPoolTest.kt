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
package com.vitorpamplona.quartz.contextvm.transport

import com.vitorpamplona.quartz.contextvm.core.CvmKinds
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The adapter between quartz's relay client and what ContextVM asks of one.
 *
 * Small enough to look obviously right and wrong in three ways that a test is
 * the only thing that catches, because each one fails as silence rather than
 * as an error: a REQ sent to the account's relays instead of the server's, a
 * response dropped because it arrived as a stored event, and a subscription
 * left open after the call that made it returned.
 */
class NostrClientCvmRelayPoolTest {
    private val serverRelay = relay("wss://server.example")
    private val otherRelay = relay("wss://elsewhere.example")

    @Test
    fun subscribesOnlyToTheRelaysItWasGiven() {
        val client = RecordingClient()
        val pool = NostrClientCvmRelayPool(client, setOf(serverRelay))

        pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE)) { }

        assertEquals(
            setOf(serverRelay),
            client.subscribed
                .single()
                .filters.keys,
        )
    }

    @Test
    fun filtersOnKindAndOnTheRecipientPTag() {
        val client = RecordingClient()
        val pool = NostrClientCvmRelayPool(client, setOf(serverRelay, otherRelay))

        pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE, CvmKinds.GIFT_WRAP)) { }

        val filter =
            client.subscribed
                .single()
                .filters
                .getValue(serverRelay)
                .single()
        assertEquals(listOf(CvmKinds.MESSAGE, CvmKinds.GIFT_WRAP), filter.kinds)
        // Addressed-to, not authored-by: a gift-wrapped response is signed by a
        // one-time key, so `authors` would match nothing.
        assertEquals(mapOf("p" to listOf(SERVER)), filter.tags)
        assertNull(filter.authors)
    }

    @Test
    fun sendsTheFilterTheCallerBuildsUnderItsPrefix() {
        val client = RecordingClient()
        val built = mutableListOf<Filter>()
        val pool =
            NostrClientCvmRelayPool(client, setOf(serverRelay), subIdPrefix = "cordn") { pubKey: HexKey, kinds: IntArray ->
                NostrClientCvmRelayPool.addressedTo(pubKey, kinds).also { built += it }
            }

        pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE)) { }

        val req = client.subscribed.single()
        assertSame(built.single(), req.filters.getValue(serverRelay).single())
        assertTrue(req.subId.startsWith("cordn-"), req.subId)
    }

    @Test
    fun deliversAStoredEventAsReadilyAsALiveOne() =
        runTest {
            val client = RecordingClient()
            val pool = NostrClientCvmRelayPool(client, setOf(serverRelay))

            val seen = mutableListOf<Event>()
            pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE)) { seen += it }

            // Kind 25910 is ephemeral, so "stored" is not a state it can be in,
            // but a relay that replays its own send buffer reports isLive=false
            // anyway. Branching on it would drop the one response the call is
            // waiting for, and the call would time out instead.
            client.subscribed
                .single()
                .listener
                .onEvent(response(), isLive = false, serverRelay, null)

            assertEquals(1, seen.size)
        }

    @Test
    fun closingASubscriptionUnsubscribesThatIdAndNoOther() {
        val client = RecordingClient()
        val pool = NostrClientCvmRelayPool(client, setOf(serverRelay))

        val first = pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE)) { }
        pool.subscribe(SERVER, intArrayOf(CvmKinds.MESSAGE)) { }

        val ids = client.subscribed.map { it.subId }
        assertEquals(2, ids.toSet().size, "two subscriptions must not share an id")

        first.close()

        // CvmTransport opens one of these per request and closes it in the same
        // call. Unsubscribing the wrong id would leave a REQ open on the
        // server's relay for the life of the process and silently break the
        // next response that a shared id was still listening for.
        assertEquals(listOf(ids.first()), client.unsubscribed)
    }

    @Test
    fun publishesToTheServerRelays() =
        runTest {
            val client = RecordingClient()
            val pool = NostrClientCvmRelayPool(client, setOf(serverRelay, otherRelay))

            pool.publish(response())

            val (event, relays) = client.published.single()
            assertEquals(setOf(serverRelay, otherRelay), relays)
            assertEquals(CvmKinds.MESSAGE, event.kind)
        }

    private fun relay(url: String) = RelayUrlNormalizer.normalizeOrNull(url)!!

    private fun response() =
        Event(
            id = "aa".repeat(32),
            pubKey = SERVER,
            createdAt = 1,
            kind = CvmKinds.MESSAGE,
            tags = arrayOf(arrayOf("p", SERVER)),
            content = "",
            sig = "bb".repeat(32),
        )

    /** Records what the pool asked of the client, and nothing else. */
    private class RecordingClient : INostrClient by EmptyNostrClient() {
        class Req(
            val subId: String,
            val filters: Map<NormalizedRelayUrl, List<Filter>>,
            val listener: SubscriptionListener,
        )

        val subscribed = mutableListOf<Req>()
        val unsubscribed = mutableListOf<String>()
        val published = mutableListOf<Pair<Event, Set<NormalizedRelayUrl>>>()

        override fun subscribe(
            subId: String,
            filters: Map<NormalizedRelayUrl, List<Filter>>,
            listener: SubscriptionListener?,
        ) {
            subscribed += Req(subId, filters, listener!!)
        }

        override fun unsubscribe(subId: String) {
            unsubscribed += subId
        }

        override fun publish(
            event: Event,
            relayList: Set<NormalizedRelayUrl>,
        ) {
            published += event to relayList
        }
    }

    private companion object {
        val SERVER = "cc".repeat(32)
    }
}
