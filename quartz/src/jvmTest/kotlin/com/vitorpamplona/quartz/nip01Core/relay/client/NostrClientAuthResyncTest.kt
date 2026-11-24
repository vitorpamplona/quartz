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
package com.vitorpamplona.quartz.nip01Core.relay.client

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.pool.PoolEventOutbox
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An accepted AUTH re-sends only what the relay refused or held back for want of it, and a second
 * publish of an event keeps the relays the first one still waits on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NostrClientAuthResyncTest {
    private val url = NormalizedRelayUrl("wss://audit.example.com/")
    private val other = NormalizedRelayUrl("wss://other.example.com/")
    private val signer = NostrSignerSync()

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, emptyArray(), content)

    /** Each socket it builds, with what was sent on it. [failOnConnect] makes every dial fail at once. */
    private class RecordingBuilder(
        val failOnConnect: Boolean = false,
    ) : WebsocketBuilder {
        val sent = mutableListOf<MutableList<String>>()
        var builds = 0
        lateinit var lastListener: WebSocketListener

        @Volatile var transportReady = true

        override fun canConnect(url: NormalizedRelayUrl) = transportReady

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            builds++
            lastListener = out
            val mine = mutableListOf<String>().also { sent += it }
            return object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() {
                    if (failOnConnect) out.onFailure(IOException("Connection refused"), null, null)
                }

                override fun disconnect() {}

                override fun send(msg: String): Boolean {
                    mine += msg
                    return true
                }
            }
        }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun deliver(
        builder: RecordingBuilder,
        msg: String,
    ) = runBlocking { builder.lastListener.onMessage(msg) }

    private val idRegex = Regex("\"id\":\"([0-9a-f]{64})\"")

    @Test
    fun anAcceptedAuthResendsOnlyWhatTheRelayRefusedForAuth() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            val user = NostrSignerInternal(KeyPair())
            val authenticator = RelayAuthenticator(client, this) { _, template, _ -> listOf(user.sign(template)) }
            try {
                settle()
                client.subscribe("live", mapOf(url to listOf(Filter(kinds = listOf(1)))))
                client.subscribe("gated", mapOf(url to listOf(Filter(kinds = listOf(4)))))
                val unanswered = note("in flight")
                val refused = note("needs auth")
                client.publish(unanswered, setOf(url))
                client.publish(refused, setOf(url))
                runCurrent()
                builder.lastListener.onOpen(50, false)
                runCurrent()
                val wire = builder.sent.last()

                deliver(builder, """["AUTH","chal-1"]""")
                runCurrent()
                val auth = wire.last { it.startsWith("[\"AUTH\"") }
                val authId = idRegex.find(auth)!!.groupValues[1]

                deliver(builder, """["EOSE","live"]""")
                deliver(builder, """["CLOSED","gated","auth-required: sign in"]""")
                deliver(builder, """["OK","${refused.id}",false,"auth-required: sign in"]""")
                val before = wire.size
                deliver(builder, """["OK","$authId",true,""]""")
                runCurrent()
                val resent = wire.drop(before)

                assertTrue(resent.any { it.startsWith("[\"REQ\",\"gated\"") }, "the refused REQ is re-sent: $resent")
                assertTrue(resent.any { it.startsWith("[\"EVENT\"") && refused.id in it }, "the auth-refused EVENT is re-sent: $resent")
                assertFalse(resent.any { it.startsWith("[\"REQ\",\"live\"") }, "a LIVE sub is not re-sent: $resent")
                assertFalse(resent.any { unanswered.id in it }, "an EVENT still in flight is not re-sent: $resent")
            } finally {
                authenticator.destroy()
                client.close()
            }
        }

    /**
     * Opens [subId] on a fresh connection, answers the relay's challenge and returns the wire with
     * the AUTH's event id. The REQ (and any EVENT published before) went out before the AUTH.
     */
    private fun TestScope.openAndAuthenticate(
        builder: RecordingBuilder,
        client: NostrClient,
        subId: String,
    ): Pair<MutableList<String>, String> {
        settle()
        client.subscribe(subId, mapOf(url to listOf(Filter(kinds = listOf(4)))))
        runCurrent()
        builder.lastListener.onOpen(50, false)
        runCurrent()
        val wire = builder.sent.last()
        deliver(builder, """["AUTH","chal-1"]""")
        runCurrent()
        val auth = wire.last { it.startsWith("[\"AUTH\"") }
        return wire to idRegex.find(auth)!!.groupValues[1]
    }

    @Test
    fun anAuthRequiredClosedThatArrivesAfterTheAuthsOkIsRetriedOnce() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            val user = NostrSignerInternal(KeyPair())
            val authenticator = RelayAuthenticator(client, this) { _, template, _ -> listOf(user.sign(template)) }
            try {
                val (wire, authId) = openAndAuthenticate(builder, client, "gated")
                val before = wire.size
                // Out of order, as khatru answers each frame on its own goroutine: the AUTH's OK
                // first, then the CLOSED of the REQ sent before the AUTH.
                deliver(builder, """["OK","$authId",true,""]""")
                runCurrent()
                deliver(builder, """["CLOSED","gated","auth-required: sign in"]""")
                runCurrent()
                val resent = wire.drop(before)
                assertEquals(1, resent.count { it.startsWith("[\"REQ\",\"gated\"") }, "the late-refused REQ is re-sent once: $resent")
                assertFalse(resent.any { it.startsWith("[\"AUTH\"") }, "no second AUTH for a challenge already answered: $resent")

                // Refused again on the same challenge: this one is the relay's verdict, not a race.
                val again = wire.size
                deliver(builder, """["CLOSED","gated","auth-required: sign in"]""")
                advanceTimeBy(10_000)
                runCurrent()
                assertFalse(wire.drop(again).any { it.startsWith("[\"REQ\",\"gated\"") }, "retried at most once per challenge: ${wire.drop(again)}")
            } finally {
                authenticator.destroy()
                client.close()
            }
        }

    @Test
    fun anAuthRequiredOkThatArrivesAfterTheAuthsOkIsRetried() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            val user = NostrSignerInternal(KeyPair())
            val authenticator = RelayAuthenticator(client, this) { _, template, _ -> listOf(user.sign(template)) }
            val event = note("sent before the AUTH")
            try {
                client.publish(event, setOf(url))
                val (wire, authId) = openAndAuthenticate(builder, client, "gated")
                assertTrue(wire.any { it.startsWith("[\"EVENT\"") && event.id in it }, "the EVENT went out before the AUTH: $wire")
                deliver(builder, """["OK","$authId",true,""]""")
                deliver(builder, """["EOSE","gated"]""")
                runCurrent()
                val before = wire.size
                deliver(builder, """["OK","${event.id}",false,"auth-required: sign in"]""")
                runCurrent()
                val resent = wire.drop(before)
                assertEquals(1, resent.count { it.startsWith("[\"EVENT\"") && event.id in it }, "the late-refused EVENT is re-sent: $resent")
            } finally {
                authenticator.destroy()
                client.close()
            }
        }

    @Test
    fun theSameChallengeRepeatedWhileItIsSignedIsSignedOnce() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            val user = NostrSignerInternal(KeyPair())
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val authenticator =
                RelayAuthenticator(client, this) { _, template, _ ->
                    calls++
                    gate.await()
                    listOf(user.sign(template))
                }
            try {
                settle()
                client.subscribe("gated", mapOf(url to listOf(Filter(kinds = listOf(4)))))
                runCurrent()
                builder.lastListener.onOpen(50, false)
                runCurrent()
                deliver(builder, """["AUTH","chal-1"]""")
                runCurrent()
                deliver(builder, """["AUTH","chal-1"]""")
                runCurrent()
                gate.complete(Unit)
                runCurrent()
                assertEquals(1, calls, "one signing for one challenge")
                assertEquals(1, builder.sent.last().count { it.startsWith("[\"AUTH\"") })
            } finally {
                authenticator.destroy()
                client.close()
            }
        }

    @Test
    fun aNewChallengeWhileTheFirstIsSignedIsSignedNext() =
        runTest {
            val builder = RecordingBuilder()
            val client = NostrClient(builder, this)
            val user = NostrSignerInternal(KeyPair())
            val signed = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val authenticator =
                RelayAuthenticator(client, this) { _, template, _ ->
                    gate.await()
                    listOf(user.sign(template)).also { signed += it.single().challenge()!! }
                }
            try {
                settle()
                client.subscribe("gated", mapOf(url to listOf(Filter(kinds = listOf(4)))))
                runCurrent()
                builder.lastListener.onOpen(50, false)
                runCurrent()
                deliver(builder, """["AUTH","chal-1"]""")
                runCurrent()
                deliver(builder, """["AUTH","chal-2"]""")
                runCurrent()
                gate.complete(Unit)
                runCurrent()
                assertEquals(listOf("chal-1", "chal-2"), signed, "a different challenge queued meanwhile is still signed")
            } finally {
                authenticator.destroy()
                client.close()
            }
        }

    @Test
    fun publishingAgainToSomeRelaysKeepsTheOthersPending() {
        val outbox = PoolEventOutbox()
        val event = note("hello")
        outbox.markAsSending(event, setOf(url, other))
        val toSend = outbox.markAsSending(event, setOf(other))
        assertEquals(setOf(other), toSend, "only the relays asked for now are sent to")
        assertEquals(setOf(url, other), outbox.pendingRelaysFor(event.id), "the first publish's relays stay pending")
    }
}
