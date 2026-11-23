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

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.FullAuthPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.testing.InProcessRelays
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * An auth-gated relay answers a cold publish `auth-required:`; once the AUTH lands the pool
 * re-sends the event by itself, and that OK is the relay's real answer. A second publish after AUTH
 * only gets `duplicate:`, which is how a Buzz DM command's channel id got lost.
 */
class NostrClientPublishThroughAuthTest {
    private val signer = NostrSignerInternal(KeyPair())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var hub: InProcessRelays? = null
    private var client: NostrClient? = null

    private fun start(policy: () -> IRelayPolicy): NostrClient {
        val h = InProcessRelays(defaultPolicy = policy)
        hub = h
        val c = NostrClient(h, scope)
        client = c
        RelayAuthenticator(c, scope) { _, template, _ -> listOf(signer.sign(template)) }
        return c
    }

    @After
    fun tearDown() {
        client?.disconnect()
        scope.cancel()
        hub?.close()
    }

    private val url = InProcessRelays.DEFAULT_URL

    @Test
    fun keepsTheOkOfThePoolsPostAuthResend() =
        runBlocking {
            val c = start { FullAuthPolicy(url) }
            val note = signer.sign(TextNoteEvent.build("hello"))
            val result = c.publishAwaitingOkThroughAuth(note, setOf(url), timeoutInSeconds = 5).getValue(url)
            assertTrue(result.accepted, "accepted through AUTH: ${result.message}")
            assertFalse(result.message.startsWith("duplicate:"), "the real OK, not the retry's duplicate: ${result.message}")
        }

    /** What callers did before: publish, warm the AUTH up, publish again. The answer is a duplicate notice. */
    @Test
    fun publishingAgainAfterAuthOnlyGetsADuplicate() =
        runBlocking {
            val c = start { FullAuthPolicy(url) }
            val note = signer.sign(TextNoteEvent.build("hello again"))
            // After the AUTH the pool re-sends the refused event by itself, and the relay answers
            // that copy with the real OK. A retry sent before that OK reaches us races the copy: it
            // is paired by id with whichever OK comes first, so it can get the real OK itself, or a
            // refusal while the copy is still being applied (59740eb84). The point here is the
            // retry AFTER the re-send was answered, so wait for that OK first.
            val resendAnswered = CompletableDeferred<Unit>()
            c.addConnectionListener(
                object : RelayConnectionListener {
                    override suspend fun onIncomingMessage(
                        relay: IRelayClient,
                        msgStr: String,
                        msg: Message,
                    ) {
                        if (msg is OkMessage && msg.eventId == note.id && msg.success) resendAnswered.complete(Unit)
                    }
                },
            )
            val first = c.publishAndCollectResults(note, setOf(url), timeoutInSeconds = 5).getValue(url)
            assertTrue(first.message.startsWith("auth-required:"), first.message)
            c.warmUpAuth(setOf(url), note.kind)
            withTimeout(5_000) { resendAnswered.await() }
            val retry = c.publishAndCollectResults(note, setOf(url), timeoutInSeconds = 5).getValue(url)
            assertTrue(retry.accepted, retry.message)
            assertTrue(retry.message.startsWith("duplicate:"), retry.message)
        }

    private inner class BanningPolicy : FullAuthPolicy(url) {
        override fun accept(cmd: AuthCmd) = PolicyResult.Rejected<AuthCmd>("blocked: you are banned from this community")
    }

    @Test
    fun aBlockedAuthEndsThePublishWithTheRelaysReason() =
        runBlocking {
            val c = start { BanningPolicy() }
            val note = signer.sign(TextNoteEvent.build("from a banned key"))
            val started = TimeSource.Monotonic.markNow()
            val result = c.publishAwaitingOkThroughAuth(note, setOf(url), timeoutInSeconds = 15).getValue(url)
            assertFalse(result.accepted)
            assertEquals("blocked: you are banned from this community", result.message)
            assertTrue(started.elapsedNow().inWholeSeconds < 10, "answered without waiting out the timeout: ${started.elapsedNow()}")
        }
}
