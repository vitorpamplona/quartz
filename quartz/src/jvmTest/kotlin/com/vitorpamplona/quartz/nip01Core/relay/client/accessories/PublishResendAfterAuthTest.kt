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
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A publish to a relay that refuses it `auth-required:` goes out once more after the AUTH lands,
 * not twice. Against the real NIP-42 exchange of [AuthGatedRelayHarness].
 */
class PublishResendAfterAuthTest {
    @Test
    fun anAuthRefusedEventIsResentOnceAfterTheAuth() = resentOnce(otherAccounts = 0)

    /**
     * Every logged-in account answers the challenge, and each accepted AUTH used to re-sync the
     * relay: two accounts sent the refused event twice more, and the copies raced on the relay
     * (one applied, the other answered `duplicate:`).
     */
    @Test
    fun anAuthRefusedEventIsResentOnceWhenSeveralAccountsAuthenticate() = resentOnce(otherAccounts = 2)

    private fun resentOnce(otherAccounts: Int) =
        runBlocking {
            AuthGatedRelayHarness(
                signer = NostrSignerInternal(KeyPair()),
                signDelayMs = 300,
                otherAccounts = List(otherAccounts) { NostrSignerInternal(KeyPair()) },
            ).use { h ->
                val event: Event = NostrSignerInternal(KeyPair()).sign(Event.build(kind = 1, content = "after auth"))
                val sends = CopyOnWriteArrayList<Long>()
                val oks = CopyOnWriteArrayList<OkMessage>()
                h.client.addConnectionListener(
                    object : RelayConnectionListener {
                        override fun onSent(
                            relay: IRelayClient,
                            cmdStr: String,
                            cmd: Command,
                            success: Boolean,
                        ) {
                            if (cmd is EventCmd && cmd.event.id == event.id) sends += System.nanoTime()
                        }

                        override suspend fun onIncomingMessage(
                            relay: IRelayClient,
                            msgStr: String,
                            msg: Message,
                        ) {
                            if (msg is OkMessage && msg.eventId == event.id) oks += msg
                        }
                    },
                )

                h.client.publish(event, setOf(AuthGatedRelayHarness.URL))
                withTimeout(5_000) { while (oks.none { it.success }) delay(10) }
                // Whatever else the pool had queued would have gone out by now.
                delay(500)

                assertTrue(oks.first().message.startsWith("auth-required"), "the first answer is the refusal: $oks")
                assertEquals(2, sends.size, "the first send and one re-send after the AUTH; answers: ${oks.map { it.message }}")
            }
        }
}
