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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.subscribe
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

/**
 * This relay client as the [CvmRelayPool] ContextVM needs: the production binding the
 * [CvmRelayPool] KDoc refers to.
 *
 * Each subscription is the client's own [subscribe] handle, which already gives every request a
 * random subscription id (so closing one request's never closes another's) and delivers stored
 * events as readily as live ones (kind 25910 is ephemeral, so anything that arrives at all is
 * live by definition).
 *
 * [relays] is the server's relay list, not the user's. A ContextVM server has no address beyond
 * its pubkey, so the only place its traffic can be found is the relays whoever published the
 * server said to use. Sending it to the account's outbox relays instead would both miss the
 * server and tell relays that have no business knowing that this account talks to it.
 *
 * [filterFor] builds the one filter each subscription sends, so a client that tags its filters
 * (to explain each subscription in a debug screen, say) can return its own [Filter] subclass.
 * Whatever it builds must keep the kinds and the `#p` tag on the pubkey: a gift-wrapped response
 * is signed by a one-time key, so `authors` would match nothing.
 */
fun INostrClient.cvmRelayPool(
    relays: Set<NormalizedRelayUrl>,
    filterFor: (pubKey: HexKey, kinds: IntArray) -> Filter = ::cvmAddressedTo,
): CvmRelayPool {
    val client = this
    val relayList = relays.toList()
    return object : CvmRelayPool {
        override fun subscribe(
            pubKey: HexKey,
            kinds: IntArray,
            onEvent: (Event) -> Unit,
        ): CvmSubscription {
            val handle = client.subscribe(relayList, filterFor(pubKey, kinds), onEvent)
            return object : CvmSubscription {
                override fun close() = handle.close()
            }
        }

        override suspend fun publish(event: Event) = client.publish(event, relays)
    }
}

/** Events of [kinds] addressed to [pubKey] (`#p`), the filter every ContextVM peer listens with. */
fun cvmAddressedTo(
    pubKey: HexKey,
    kinds: IntArray,
): Filter = Filter(kinds = kinds.toList(), tags = mapOf("p" to listOf(pubKey)))
