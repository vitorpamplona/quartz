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

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.Log

/**
 * A [CvmRelayPool] over quartz's own relay client: the production binding the
 * [CvmRelayPool] KDoc refers to.
 *
 * [relays] is the server's relay list, not the user's. A ContextVM server has no
 * address beyond its pubkey, so the only place its traffic can be found is the
 * relays whoever published the server said to use. Sending it to the account's
 * outbox relays instead would both miss the server and tell relays that have no
 * business knowing that this account talks to it.
 *
 * [subIdPrefix] names the subscriptions in relay logs, and [filterFor] builds the
 * one filter each subscription sends, so a client that tags its filters (to
 * explain each subscription in a debug screen, say) can return its own [Filter]
 * subclass. Whatever it builds must keep [kinds] and the `#p` tag on [pubKey]:
 * a gift-wrapped response is signed by a one-time key, so `authors` would match
 * nothing.
 */
class NostrClientCvmRelayPool(
    private val client: INostrClient,
    private val relays: Set<NormalizedRelayUrl>,
    private val subIdPrefix: String = "cvm",
    private val filterFor: (pubKey: HexKey, kinds: IntArray) -> Filter = ::addressedTo,
) : CvmRelayPool {
    override fun subscribe(
        pubKey: HexKey,
        kinds: IntArray,
        onEvent: (Event) -> Unit,
    ): CvmSubscription {
        // Random rather than a counter. CvmTransport opens one subscription per
        // request and closes it in the same call, and callers issue requests
        // concurrently, so a shared `nextId++` hands two of them the same id.
        // Then the first close() unsubscribes both, and the other request waits
        // out its whole timeout for a response the relay stopped sending.
        val subId = "$subIdPrefix-${pubKey.take(8)}-${RandomInstance.randomChars(8)}"
        val filter = filterFor(pubKey, kinds)

        client.subscribe(
            subId = subId,
            filters = relays.associateWith { listOf(filter) },
            listener =
                object : SubscriptionListener {
                    override suspend fun onEvent(
                        event: Event,
                        isLive: Boolean,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        // Stored-vs-live is not a distinction kind 25910 has: it
                        // is ephemeral, so anything that arrives at all is live
                        // by definition. Filtering on isLive here would drop
                        // responses on a relay that replays its buffer.
                        onEvent(event)
                    }

                    override fun onClosed(
                        message: String,
                        relay: NormalizedRelayUrl,
                        forFilters: List<Filter>?,
                    ) {
                        Log.d(TAG) { "subscription $subId closed by $relay: $message" }
                    }
                },
        )

        return object : CvmSubscription {
            override fun close() {
                client.unsubscribe(subId)
            }
        }
    }

    override suspend fun publish(event: Event) {
        client.publish(event, relays)
    }

    companion object {
        private const val TAG = "CvmRelayPool"

        /** Events of [kinds] addressed to [pubKey] (`#p`), the filter every ContextVM peer listens with. */
        fun addressedTo(
            pubKey: HexKey,
            kinds: IntArray,
        ): Filter = Filter(kinds = kinds.toList(), tags = mapOf("p" to listOf(pubKey)))
    }
}
