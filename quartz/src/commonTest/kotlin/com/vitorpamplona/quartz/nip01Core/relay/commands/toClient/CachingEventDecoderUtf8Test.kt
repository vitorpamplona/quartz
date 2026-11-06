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
package com.vitorpamplona.quartz.nip01Core.relay.commands.toClient

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * The decoder's suites again, with every frame handed over as bytes, as OkHttpBytesWebSocket
 * does: the byte path (Kite's reader asking for the id as it parses) must keep every rule.
 */

private fun CachingEventDecoder.utf8(
    text: String,
    relay: NormalizedRelayUrl?,
): Message? = text.encodeToByteArray().let { decodeFromUtf8(it, 0, it.size, relay) }

private fun CachingEventDecoder.utf8(text: String): Message = text.encodeToByteArray().let { decodeUtf8(it, 0, it.size) }

class CachingEventDecoderUtf8Test : CachingEventDecoderTest() {
    override fun CachingEventDecoder.decodeAt(
        text: String,
        relay: NormalizedRelayUrl?,
    ) = utf8(text, relay)

    override fun CachingEventDecoder.decodeAt(text: String) = utf8(text)
}

class CachingEventDecoderForgeryUtf8Test : CachingEventDecoderForgeryTest() {
    override fun CachingEventDecoder.decodeAt(
        text: String,
        relay: NormalizedRelayUrl?,
    ) = utf8(text, relay)

    override fun CachingEventDecoder.decodeAt(text: String) = utf8(text)
}

class CachingEventDecoderTrimUtf8Test : CachingEventDecoderTrimTest() {
    override fun CachingEventDecoder.decodeAt(
        text: String,
        relay: NormalizedRelayUrl?,
    ) = utf8(text, relay)

    override fun CachingEventDecoder.decodeAt(text: String) = utf8(text)
}

/** [CachingEventDecoder.knownEvents]: the app's events, asked after the decoder's own cache. */
class CachingEventDecoderKnownEventsTest {
    private val signer = NostrSignerSync()
    private val relay = NormalizedRelayUrl("wss://relay.example/")

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, arrayOf(arrayOf("t", "x")), content)

    /** Strfry's order (content first), so the id is read after a deferred field. */
    private fun frame(
        e: Event,
        subId: String = "sub",
    ): String {
        val tags = e.tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { "\"$it\"" } }
        return """["EVENT","$subId",{"content":"${e.content}","created_at":${e.createdAt},"id":"${e.id}","kind":${e.kind},"pubkey":"${e.pubKey}","sig":"${e.sig}","tags":$tags}]"""
    }

    private class AppCache : KnownEventLookup {
        val events = HashMap<String, Event>()
        val asked = ArrayList<String>()

        override fun find(id: String): Event? {
            asked.add(id)
            return events[id]
        }
    }

    private fun both(test: (decode: CachingEventDecoder.(String) -> Message?) -> Unit) {
        test { decodeFrom(it, relay) }
        test { utf8(it, relay) }
    }

    @Test
    fun anEventTheAppHoldsIsHandedOutNotParsed() =
        both { decode ->
            val app = AppCache()
            val held = note("held by the app")
            app.events[held.id] = held
            val d = CachingEventDecoder().also { it.knownEvents = app }

            val msg = d.decode(frame(held, "s1")) as EventMessage
            assertSame(held, msg.event)
            assertEquals("s1", msg.subId)
            assertEquals(0, d.parsedCount)
            assertEquals(1, d.reusedCount)
            assertEquals(1, d.reusedKnownCount)
        }

    @Test
    fun theDecodersOwnCacheIsAskedFirst() =
        both { decode ->
            val app = AppCache()
            val d = CachingEventDecoder().also { it.knownEvents = app }
            val e = note("first seen here")
            val first = d.decode(frame(e)) as EventMessage
            assertEquals(listOf(e.id), app.asked, "a new id is asked of the app once")
            val again = d.decode(frame(e, "s2")) as EventMessage
            assertSame(first.event, again.event)
            assertEquals(1, app.asked.size, "a cached id never reaches the app")
            assertEquals(0, d.reusedKnownCount)
        }

    @Test
    fun anUnknownEventIsParsedAndCached() =
        both { decode ->
            val d = CachingEventDecoder().also { it.knownEvents = AppCache() }
            val e = note("new")
            val msg = d.decode(frame(e)) as EventMessage
            assertEquals(e.content, msg.event.content)
            assertEquals(e.tags.map { it.toList() }, msg.event.tags.map { it.toList() })
            assertTrue(msg.event.verify())
            assertEquals(1, d.parsedCount)
        }

    @Test
    fun aScrutinizedIdIsNeverAnsweredByTheApp() =
        both { decode ->
            val app = AppCache()
            val genuine = note("genuine")
            val forged = Event(genuine.id, genuine.pubKey, genuine.createdAt, genuine.kind, genuine.tags, "forged", genuine.sig)
            val d = CachingEventDecoder().also { it.knownEvents = app }
            // The forged copy wins the dedup, the app keeps it, then finds it forged.
            val handedOut = (d.decode(frame(forged)) as EventMessage).event
            app.events[genuine.id] = handedOut
            assertFalse(handedOut.verify())
            d.reportForged(handedOut)

            // The genuine copy comes in: parsed and verified, not the app's forged one.
            val recovered = (d.decode(frame(genuine, "s2")) as EventMessage).event
            assertEquals("genuine", recovered.content)
            assertTrue(recovered.verify())
            assertEquals(0, d.reusedKnownCount, "the forged copy the app holds is never handed out")
        }
}
