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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * [subscribeEvents] against a relay that really gates reads behind NIP-42
 * ([AuthGatedRelayHarness]: quartz's own server under `FullAuthPolicy`, a real
 * `RelayAuthenticator`). The responder signs 300 ms late, so the REQ is always refused
 * `auth-required:` first: the path under test runs every time.
 */
class NostrClientSubscribeEventsAuthTest {
    private val relay = AuthGatedRelayHarness.URL
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun AuthGatedRelayHarness.live() = client.subscribeEvents(relay, listOf(Filter(kinds = listOf(1))), authSettleMs = 5_000)

    @Test
    fun aClientThatAuthenticatesKeepsTheFlowAndReceives() =
        runBlocking<Unit> {
            AuthGatedRelayHarness(signer = NostrSignerInternal(KeyPair()), signDelayMs = 300).use { h ->
                h.preload(5)
                val received = Channel<Event>(Channel.UNLIMITED)
                val flow = scope.async { runCatching { h.live().collect { received.send(it) } } }

                withTimeout(10_000) { repeat(5) { received.receive() } }
                assertTrue(flow.isActive, "an AUTH that lands must not end the flow")
                assertTrue(h.signedAuths.get() >= 1)
                flow.cancel()
            }
            scope.cancel()
        }

    @Test
    fun aReconnectThatMustAuthenticateAgainKeepsTheFlow() =
        runBlocking<Unit> {
            AuthGatedRelayHarness(signer = NostrSignerInternal(KeyPair()), signDelayMs = 300).use { h ->
                h.preload(3)
                val received = Channel<Event>(Channel.UNLIMITED)
                val flow = scope.async { runCatching { h.live().collect { received.send(it) } } }
                withTimeout(10_000) { repeat(3) { received.receive() } }
                delay(500) // EOSE seen

                // AUTHs are counted per connection: the new one starts again from zero.
                h.dropConnections()
                h.preload(1, newestAt = TimeUtils.now() + 5)
                withTimeout(15_000) { received.receive() }
                assertTrue(flow.isActive, "the new connection's AUTH must keep the flow: ${if (flow.isCompleted) flow.await().exceptionOrNull() else ""}")
                assertTrue(h.signedAuths.get() >= 2, "authenticated on both connections")
                flow.cancel()
            }
            scope.cancel()
        }

    @Test
    fun aResponderThatNeverSatisfiesTheRelayEndsTheFlowOnItsVerdict() =
        runBlocking<Unit> {
            AuthGatedRelayHarness(signer = null, signDelayMs = 300).use { h ->
                h.preload(5)
                val (outcome, elapsed) = measureTimedValue { withTimeout(10_000) { runCatching { h.live().collect { } }.exceptionOrNull() } }
                assertIs<SubscriptionClosedException>(outcome)
                assertTrue(outcome.reason.startsWith("auth-required:"), outcome.reason)
                assertTrue(elapsed.inWholeMilliseconds < 4_000, "ends on the AUTH's verdict, not the 5 s settle bound; was $elapsed")
            }
            scope.cancel()
        }

    @Test
    fun aClientWithNoResponderEndsTheFlowAtOnce() =
        runBlocking<Unit> {
            AuthGatedRelayHarness(attachAuthenticator = false).use { h ->
                h.preload(5)
                val (outcome, elapsed) = measureTimedValue { withTimeout(10_000) { runCatching { h.live().collect { } }.exceptionOrNull() } }
                assertIs<SubscriptionClosedException>(outcome)
                assertTrue(elapsed.inWholeMilliseconds < 1_000, "nobody can answer: no wait; was $elapsed")
            }
            scope.cancel()
        }
}
