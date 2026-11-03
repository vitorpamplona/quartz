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
package com.vitorpamplona.quartz.nip01Core.relay.client.reqs

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.testing.InProcessRelays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicSubscriptionTest {
    /** Every REQ the relay accepted, across connections. */
    private val reqs = CopyOnWriteArrayList<ReqCmd>()

    private inner class RecordingPolicy : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> {
            reqs += cmd
            return PolicyResult.Accepted(cmd)
        }
    }

    /** The hub, keeping each socket it builds so a test can drop the connection under the client. */
    private class DroppableBuilder(
        private val delegate: WebsocketBuilder,
    ) : WebsocketBuilder {
        val sockets = CopyOnWriteArrayList<WebSocket>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket = delegate.build(url, out).also { sockets += it }

        fun dropAll() = sockets.forEach { it.disconnect() }
    }

    private val hub = InProcessRelays(defaultPolicy = { RecordingPolicy() })
    private val builder = DroppableBuilder(hub)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = NostrClient(builder, scope)
    private val relay = InProcessRelays.DEFAULT_URL

    @AfterTest
    fun tearDown() {
        client.disconnect()
        scope.cancel()
        hub.close()
    }

    private suspend fun awaitReqs(count: Int) = withTimeout(10_000) { while (reqs.size < count) delay(20) }

    @Test
    fun aReconnectSendsWhatTheLambdaReturnsNow() =
        runBlocking<Unit> {
            var since = 500L
            val sub = client.subscribe(filters = { mapOf(relay to listOf(Filter(kinds = listOf(1), since = since))) })
            awaitReqs(1)
            assertEquals(500L, reqs[0].filters.single().since)

            since = 900L // no refresh: the pool reads it when it writes the REQ again
            builder.dropAll()
            awaitReqs(2)
            assertEquals(900L, reqs[1].filters.single().since)
            sub.close()
        }

    @Test
    fun aRelayTheLambdaNoLongerNamesKeepsItsFiltersUntilARefresh() =
        runBlocking<Unit> {
            var relays = setOf(relay)
            val sub = client.subscribe(filters = { relays.associateWith { listOf(Filter(kinds = listOf(1), since = 500)) } })
            awaitReqs(1)

            relays = emptySet() // no refresh: still open there, with the filters it was given
            builder.dropAll()
            awaitReqs(2)
            assertEquals(500L, reqs[1].filters.single().since)
            sub.close()
        }
}
