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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * destroy() must stop the authenticator, not just unhook its listeners: an AUTH
 * still waiting on the signer (a NIP-55 prompt, a bunker) would otherwise sign
 * and send after the host let the authenticator go. The host's own scope, which
 * it may share with everything else, must survive.
 */
class RelayAuthenticatorDestroyTest {
    private class CapturingClient(
        private val delegate: INostrClient = EmptyNostrClient(),
    ) : INostrClient by delegate {
        @Volatile var captured: RelayConnectionListener? = null

        override fun addConnectionListener(listener: RelayConnectionListener) {
            captured = listener
        }
    }

    @Test
    fun destroyStopsAnAuthStillWaitingOnTheSigner() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val signer = NostrSignerInternal(KeyPair())
            val signerReturns = CompletableDeferred<Unit>()
            val signerCalled = CompletableDeferred<Unit>()

            val client = CapturingClient()
            val authenticator =
                RelayAuthenticator(
                    client = client,
                    scope = scope,
                    signWithAllLoggedInUsers = { _, template, _ ->
                        signerCalled.complete(Unit)
                        signerReturns.await()
                        listOf(signer.sign(template))
                    },
                )
            val listener = client.captured ?: error("RelayAuthenticator did not register a listener")
            val relay = FakeRelayClient(NormalizedRelayUrl("wss://relay.example/"))

            listener.onConnecting(relay)
            listener.onIncomingMessage(relay, "", AuthMessage("chal-1"))
            signerCalled.await()

            authenticator.destroy()
            signerReturns.complete(Unit)
            delay(200)

            assertEquals(emptyList(), relay.sent.filterIsInstance<AuthCmd>(), "no AUTH goes out after destroy()")
            assertTrue(scope.isActive, "the host's scope is not cancelled")
            scope.cancel()
        }
}
