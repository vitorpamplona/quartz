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
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetch
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CachingEventDecoder
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End to end through NostrClient: a rogue relay's forged copy wins the dedup, the app's lazy
 * verify finds it, the honest relay is asked again and the genuine copy comes through, and the
 * rogue relay's next forgery is dropped on arrival.
 */
class ForgedCopyRecoveryTest {
    private val signer = NostrSignerSync()
    private val rogue = NormalizedRelayUrl("wss://rogue.example/")
    private val honest = NormalizedRelayUrl("wss://honest.example/")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Relays that answer every REQ with the events currently set for them, then EOSE. */
    private inner class CannedRelays : WebsocketBuilder {
        val served = ConcurrentHashMap<NormalizedRelayUrl, List<Event>>()

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket =
            object : WebSocket {
                private var open = false

                override fun needsReconnect() = !open

                override fun connect() {
                    open = true
                    out.onOpen(0, false)
                }

                override fun disconnect() {
                    if (!open) return
                    open = false
                    out.onClosed(1000, "bye")
                }

                override fun send(msg: String): Boolean {
                    val subId = REQ.find(msg)?.groupValues?.get(1) ?: return true
                    val events = served[url].orEmpty()
                    scope.launch {
                        for (e in events) out.onMessage("[\"EVENT\",\"$subId\",${e.toJson()}]")
                        out.onMessage("[\"EOSE\",\"$subId\"]")
                    }
                    return true
                }
            }
    }

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, emptyArray(), content)

    private fun Event.withContent(content: String) = Event(id, pubKey, createdAt, kind, tags, content, sig)

    @Test
    fun aForgedCopyIsRecoveredFromAndItsRelayCaughtNextTime() =
        runBlocking<Unit> {
            val relays = CannedRelays()
            val decoder = CachingEventDecoder()
            val forgeries = CopyOnWriteArrayList<CachingEventDecoder.Forgery>()
            decoder.onForgery = { forgeries += it }
            val client = NostrClient(relays, scope, decoder)
            val kind1 = Filter(kinds = listOf(1))

            suspend fun read(relay: NormalizedRelayUrl): List<Event> {
                val got = CopyOnWriteArrayList<Event>()
                withTimeout(5_000) { client.fetch(relay, kind1) { got += it } }
                return got
            }

            try {
                val genuine = note("genuine")
                relays.served[rogue] = listOf(genuine.withContent("forged"))
                relays.served[honest] = listOf(genuine)

                read(rogue)
                val fromHonest = read(honest).single()
                assertEquals("forged", fromHonest.content, "optimistic dedup: the first copy wins")
                assertFalse(fromHonest.verify(), "the app's lazy verify")
                assertEquals(rogue, decoder.reportForged(fromHonest))

                val asked = read(honest).single()
                assertEquals("genuine", asked.content)
                assertTrue(asked.verify())

                val next = note("next")
                relays.served[rogue] = listOf(next.withContent("forged again"))
                relays.served[honest] = listOf(next)
                assertEquals(emptyList(), read(rogue), "dropped on arrival")
                assertEquals(listOf(1, 2), forgeries.map { it.offenses })
                assertTrue(forgeries.all { it.relay == rogue })
                assertEquals("next", read(honest).single().content)
            } finally {
                client.disconnect()
            }
        }

    /** CLIENT.md's sample, compiled. */
    @Suppress("unused")
    private suspend fun clientGuideSample(
        httpClient: OkHttpClient,
        event: Event,
        relaysThatSentIt: List<NormalizedRelayUrl>,
        stopUsing: (NormalizedRelayUrl) -> Unit,
        show: (Event) -> Unit,
    ) {
        val decoder = CachingEventDecoder()
        // Your policy: each forgery found, with the relay's count so far.
        decoder.onForgery = { forgery -> if (forgery.offenses >= 10) stopUsing(forgery.relay) }
        val client = NostrClient(BasicOkHttpWebSocket.Builder { httpClient }, scope, decoder)

        // When the UI needs the event:
        if (!event.verify()) {
            decoder.reportForged(event) // the relay that sent the forged copy, or null
            for (relay in relaysThatSentIt) client.fetch(relay, Filter(ids = listOf(event.id))) { show(it) }
        }
    }

    companion object {
        private val REQ = Regex("^\\[\"REQ\",\"([^\"]+)\"")
    }
}
