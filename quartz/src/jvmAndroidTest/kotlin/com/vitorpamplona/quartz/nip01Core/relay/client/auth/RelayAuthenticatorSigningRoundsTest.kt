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
package com.vitorpamplona.quartz.nip01Core.relay.client.auth

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.testing.FakeRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.AuthMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One signing round per connection at a time: AUTH frames that arrive while the signer works are
 * coalesced onto the newest challenge, an AUTH signed for a connection that has since been replaced
 * is not sent on the new one, and an authenticator attached after the connection opened still
 * answers its challenges.
 */
class RelayAuthenticatorSigningRoundsTest {
    private class CapturingClient(
        private val delegate: INostrClient = EmptyNostrClient(),
    ) : INostrClient by delegate {
        @Volatile var captured: RelayConnectionListener? = null

        override fun addConnectionListener(listener: RelayConnectionListener) {
            captured = listener
        }
    }

    private val url = NormalizedRelayUrl("wss://relay.example/")

    private fun challengesSent(relay: FakeRelayClient) = relay.sent.filterIsInstance<AuthCmd>().map { it.event.challenge() }

    @Test
    fun challengesArrivingWhileSigningAreCoalescedOntoTheNewest() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val user = NostrSignerInternal(KeyPair())
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            val signed = mutableListOf<String?>()
            val client = CapturingClient()
            RelayAuthenticator(client, scope) { _, template, _ ->
                val gate = CompletableDeferred<Unit>().also { gates += it }
                gate.await()
                val event = user.sign(template)
                signed += event.challenge()
                listOf(event)
            }
            val listener = client.captured!!
            val relay = FakeRelayClient(url)
            listener.onConnecting(relay)

            listener.onIncomingMessage(relay, "", AuthMessage("chal-1"))
            listener.onIncomingMessage(relay, "", AuthMessage("chal-2"))
            listener.onIncomingMessage(relay, "", AuthMessage("chal-3"))
            assertEquals(1, gates.size, "one signer prompt at a time, not one per AUTH frame")

            gates[0].complete(Unit)
            assertEquals(2, gates.size, "the newest challenge is signed once the first round ends")
            gates[1].complete(Unit)

            assertEquals(listOf("chal-3"), challengesSent(relay), "only the current challenge is answered")
            scope.cancel()
        }

    @Test
    fun anAuthSignedForAReplacedConnectionIsNotSentOnTheNewOne() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val user = NostrSignerInternal(KeyPair())
            val gate = CompletableDeferred<Unit>()
            val client = CapturingClient()
            RelayAuthenticator(client, scope) { _, template, _ ->
                gate.await()
                listOf(user.sign(template))
            }
            val listener = client.captured!!
            val relay = FakeRelayClient(url)
            listener.onConnecting(relay)
            listener.onIncomingMessage(relay, "", AuthMessage("old-connection"))

            // The socket drops and a new one opens while the signer is still working.
            listener.onDisconnected(relay)
            listener.onConnecting(relay)
            gate.complete(Unit)

            assertEquals(emptyList(), challengesSent(relay), "the old challenge is not answered on the new connection")
            scope.cancel()
        }

    @Test
    fun anAuthenticatorAttachedAfterConnectingStillAnswers() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val user = NostrSignerInternal(KeyPair())
            val client = CapturingClient()
            RelayAuthenticator(client, scope) { _, template, _ -> listOf(user.sign(template)) }
            val listener = client.captured!!
            val relay = FakeRelayClient(url)
            // No onConnecting: the relay was already connected when the authenticator was attached.
            listener.onIncomingMessage(relay, "", AuthMessage("chal-1"))

            assertTrue(challengesSent(relay) == listOf("chal-1"), "the challenge is answered: ${challengesSent(relay)}")
            scope.cancel()
        }
}
