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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RelayPool.connectedRelays] is what the app reads as "how many relays am I connected to", so it
 * must drop a relay the moment the pool lets go of it, even when the socket layer never confirms
 * the close.
 *
 * [com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.FakeWebSocket.disconnect] is a
 * no-op that never calls back, which is exactly what OkHttp did after a relay sent a CLOSE frame
 * the app never answered: `cancel()` then fired neither `onClosed` nor `onFailure`, and the flow
 * carried such relays for minutes. The real transports now report a disconnect synchronously (see
 * [com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket]), but this flow must not depend on
 * that: the pool clears it itself on removal and on disconnect.
 */
class RelayPoolConnectedFlowTest {
    private val url = NormalizedRelayUrl("wss://relay.example.com/")

    private fun openedPool(): RelayPool {
        val sockets = FakeWebsocketBuilder()
        val pool = RelayPool(sockets)
        pool.getOrCreateRelay(url).connect()
        sockets.lastListener.onOpen(pingMillis = 10, compression = false)
        return pool
    }

    @Test
    fun anOpenSocketIsConnected() {
        val pool = openedPool()

        assertEquals(setOf(url), pool.connectedRelays.value)
        assertEquals(1, pool.connectedRelaysCount())
    }

    @Test
    fun removingARelayDropsItEvenWhenTheCloseIsSilent() {
        val pool = openedPool()

        // No subscription wants it anymore: the pool drops it and cancels its socket, and the
        // socket layer stays silent.
        pool.removeRelay(url)

        assertEquals(emptySet(), pool.connectedRelays.value)
        assertEquals(0, pool.connectedRelaysCount())
    }

    @Test
    fun disconnectingThePoolDropsEveryRelayEvenWhenTheCloseIsSilent() {
        val pool = openedPool()

        // The host is putting the client down (app backgrounded, connectivity lost).
        pool.disconnect()

        assertEquals(emptySet(), pool.connectedRelays.value)
        assertEquals(setOf(url), pool.availableRelays.value, "still a member, just not connected")
    }

    // availableRelays used to hold the pool map's live key view: the same object on every update,
    // so the flow never emitted again after the first member, and a value read earlier changed
    // under the reader.
    @Test
    fun everyMembershipChangePublishesANewSnapshot() {
        val pool = RelayPool(FakeWebsocketBuilder())
        val other = NormalizedRelayUrl("wss://other.example.com/")

        pool.getOrCreateRelay(url)
        val first = pool.availableRelays.value
        pool.getOrCreateRelay(other)
        val second = pool.availableRelays.value

        assertEquals(setOf(url), first, "a published value never changes afterwards")
        assertEquals(setOf(url, other), second)
        assertTrue(first !== second, "a new value, so the flow emits")

        pool.removeRelay(url)
        assertEquals(setOf(other), pool.availableRelays.value)
        assertEquals(setOf(url, other), second)
    }

    // The pool swaps clients for one url: a sample drops the idle relay just as a publish wants
    // it again, so a new client dials while the old one's close is still being reported. The
    // old client's end used to take the url out of the set the new, connected client had just
    // put it in, and a NIP-77 sync waiting for the relay to show as connected waited out its
    // whole connect bound (60 s) on a relay that was up.
    @Test
    fun aReplacedClientsLateCloseLeavesItsSuccessorConnected() {
        val sockets = FakeWebsocketBuilder()
        val pool = RelayPool(sockets)
        pool.getOrCreateRelay(url).connect()
        val oldSession = sockets.lastListener
        oldSession.onOpen(pingMillis = 10, compression = false)

        pool.removeRelay(url)
        val successor = pool.getOrCreateRelay(url)
        successor.connect()
        sockets.lastListener.onOpen(pingMillis = 10, compression = false)
        assertEquals(setOf(url), pool.connectedRelays.value)

        // The old socket reports its end only now.
        oldSession.onClosed(1000, "removed from the pool")

        assertTrue(successor.isConnected())
        assertEquals(setOf(url), pool.connectedRelays.value, "the url's current client is connected")
    }

    // The mirror image: a replaced client that only now finishes its handshake is not the url's
    // client, so it does not put the url in the set while its successor is still dialing.
    @Test
    fun aReplacedClientsLateOpenDoesNotMarkTheUrlConnected() {
        val sockets = FakeWebsocketBuilder()
        val pool = RelayPool(sockets)
        pool.getOrCreateRelay(url).connect()
        val oldSession = sockets.lastListener

        pool.removeRelay(url)
        pool.getOrCreateRelay(url).connect()

        oldSession.onOpen(pingMillis = 10, compression = false)

        assertEquals(emptySet(), pool.connectedRelays.value, "the url's current client is still dialing")
    }
}
