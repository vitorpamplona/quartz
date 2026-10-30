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
package com.vitorpamplona.quartz.nip01Core.relay.server.policies

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.AuthMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip42RelayAuth.tags.ChallengeTag
import com.vitorpamplona.quartz.nip42RelayAuth.tags.RelayTag
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * NIP-42 for a relay that answers at more than one address (a clearnet url and a hidden
 * service): a client signs the address it dialled, and any of them must authenticate, while
 * every other check of [FullAuthPolicy] still applies on each.
 */
class FullAuthPolicyAddressesTest {
    private val clearnet = "wss://relay.example.com/".normalizeRelayUrl()
    private val onion = "ws://${"n".repeat(56)}.onion/".normalizeRelayUrl()
    private val someoneElse = "wss://other.example.com/".normalizeRelayUrl()
    private val signer = NostrSignerSync(KeyPair())

    private class Context(
        override val policy: IRelayPolicy,
    ) : RequestContext {
        override val connectionId: Long = 1
        override val authenticatedUsers: Set<HexKey> = emptySet()
    }

    private fun connected(policy: FullAuthPolicy): MutableList<Message> {
        val sent = mutableListOf<Message>()
        policy.onConnect(Context(policy)) { sent.add(it) }
        return sent
    }

    private fun auth(
        challenge: String,
        vararg relays: NormalizedRelayUrl,
        createdAt: Long = TimeUtils.now(),
    ): AuthCmd =
        AuthCmd(
            signer.sign(
                createdAt,
                RelayAuthEvent.KIND,
                (relays.map { RelayTag.assemble(it) } + listOf(ChallengeTag.assemble(challenge))).toTypedArray(),
                "",
            ),
        )

    @Test
    fun anyServedAddressAuthenticates() {
        val policy = FullAuthPolicy(setOf(clearnet, onion))
        connected(policy)
        assertIs<PolicyResult.Accepted<AuthCmd>>(policy.accept(auth(policy.challenge, onion)))
        assertIs<PolicyResult.Accepted<AuthCmd>>(policy.accept(auth(policy.challenge, clearnet)))
        assertEquals(clearnet, policy.relay, "the first address stays the primary")
        assertEquals(setOf(clearnet, onion), policy.relays)
    }

    @Test
    fun anAuthNamingSeveralRelaysAuthenticatesIfAnyOfThemIsUs() {
        // RelayAuthEvent.create(relays = …) writes one `relay` tag per relay.
        val policy = FullAuthPolicy(clearnet)
        connected(policy)
        assertIs<PolicyResult.Accepted<AuthCmd>>(policy.accept(auth(policy.challenge, someoneElse, clearnet)))
    }

    @Test
    fun theDefaultPortSpelledOutIsTheSameAddress() {
        val policy = FullAuthPolicy(setOf(clearnet, onion))
        connected(policy)
        val explicit = "ws://${"n".repeat(56)}.onion:80/".normalizeRelayUrl()
        assertIs<PolicyResult.Accepted<AuthCmd>>(policy.accept(auth(policy.challenge, explicit)))
        val otherPort = "ws://${"n".repeat(56)}.onion:8080/".normalizeRelayUrl()
        assertIs<PolicyResult.Rejected<AuthCmd>>(policy.accept(auth(policy.challenge, otherPort)))
    }

    @Test
    fun everyOtherCheckStillAppliesOnEachAddress() {
        val policy = FullAuthPolicy(setOf(clearnet, onion))
        connected(policy)
        assertEquals("invalid: relay url does not match", (policy.accept(auth(policy.challenge, someoneElse)) as PolicyResult.Rejected).reason)
        assertEquals("invalid: challenge does not match", (policy.accept(auth("not-the-challenge", onion)) as PolicyResult.Rejected).reason)
        assertIs<PolicyResult.Rejected<AuthCmd>>(policy.accept(auth(policy.challenge, onion, createdAt = TimeUtils.now() - TimeUtils.ONE_HOUR)))
    }

    @Test
    fun anEmptySetIsRefused() {
        assertFailsWith<IllegalArgumentException> { FullAuthPolicy(emptySet()) }
    }

    @Test
    fun optionalAuthTakesTheSameSet() {
        val policy = OptionalAuthPolicy(setOf(clearnet, onion))
        connected(policy)
        assertIs<PolicyResult.Accepted<AuthCmd>>(policy.accept(auth(policy.challenge, onion)))
    }

    @Test
    fun authorizeCanAnswerTheClientOnItsConnection() =
        runTest {
            val policy =
                object : FullAuthPolicy(clearnet) {
                    var seen: RequestContext? = null

                    override suspend fun authorize(event: RelayAuthEvent) {
                        seen = connectionScope
                        sendToClient(NoticeMessage("welcome ${event.pubKey.take(8)}"))
                    }
                }
            val sent = connected(policy)
            val event = auth(policy.challenge, clearnet).event
            policy.onAuthenticated(event)
            assertIs<AuthMessage>(sent[0])
            assertEquals("welcome ${signer.pubKey.take(8)}", (sent[1] as NoticeMessage).message)
            assertSame(policy, policy.seen?.policy)
        }
}
