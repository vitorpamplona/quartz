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

class CachingEventDecoderKnownEventsUtf8Test : CachingEventDecoderKnownEventsTest() {
    override fun CachingEventDecoder.decodeAt(
        text: String,
        relay: NormalizedRelayUrl?,
    ) = utf8(text, relay)
}

/** The byte path skips a scrutinized id's spam unparsed, as the text path's sig scan does. */
class CachingEventDecoderUtf8SpamTest {
    private val signer = NostrSignerSync()
    private val rogue = NormalizedRelayUrl("wss://rogue.example/")

    private fun frame(e: Event) = "[\"EVENT\",\"sub\",${e.toJson()}]"

    private fun sig(n: Int) = n.toString(16).padStart(128, '0')

    @Test
    fun aKnownBadSignatureAndCopiesPastTheCapAreDroppedUnparsed() {
        val genuine: Event = signer.sign(1_700_000_000L, 1, arrayOf(arrayOf("t", "x")), "genuine")
        val badSig = Event(genuine.id, genuine.pubKey, genuine.createdAt, genuine.kind, genuine.tags, genuine.content, sig(1))
        val d = CachingEventDecoder()
        val handedOut = (d.utf8(frame(badSig), rogue) as EventMessage).event
        assertFalse(handedOut.verify())
        d.reportForged(handedOut)

        val parsed = d.parsedCount
        val skipped = d.skippedCount
        repeat(50) { assertEquals(null, d.utf8(frame(badSig), rogue), "the known-bad signature again") }
        assertEquals(parsed, d.parsedCount, "dropped without a parse")
        assertEquals(skipped + 50, d.skippedCount)

        // New bad signatures are parsed and checked, until the cap; then every copy is dropped unparsed.
        for (n in 2..CachingEventDecoder.MAX_FORGED_COPIES + 1) assertEquals(null, d.utf8(frame(badSig.withSig(sig(n))), rogue))
        val afterCap = d.parsedCount
        repeat(20) { n -> assertEquals(null, d.utf8(frame(badSig.withSig(sig(1000 + n))), rogue)) }
        assertEquals(afterCap, d.parsedCount, "past the cap, copies are not parsed")
        assertTrue(d.skippedCount > skipped + 50)
    }

    @Test
    fun aDecoySigKeyDoesNotGetTheGenuineCopyDropped() {
        val paths =
            listOf<Pair<String, (CachingEventDecoder, String) -> Message?>>(
                "text" to { d, f -> d.decodeFrom(f, rogue) },
                "bytes" to { d, f -> d.utf8(f, rogue) },
            )
        for ((name, decode) in paths) {
            val genuine: Event = signer.sign(1_700_000_000L, 1, arrayOf(arrayOf("t", "x")), "genuine $name")
            val d = CachingEventDecoder()
            val handedOut = (decode(d, frame(genuine.withSig(sig(1)))) as EventMessage).event
            assertFalse(handedOut.verify())
            d.reportForged(handedOut)

            // The genuine copy, behind a key ending in \"sig that holds the known-bad signature.
            val decoy = "[\"EVENT\",\"sub\",{\"x\\\"sig\":\"${sig(1)}\"," + genuine.toJson().removePrefix("{") + "]"
            val recovered = (decode(d, decoy) as EventMessage?)?.event
            assertEquals(genuine.sig, recovered?.sig, "$name: the genuine copy is parsed and checked, not dropped by the decoy")
            assertTrue(recovered!!.verify())
        }
    }

    private fun Event.withSig(sig: String) = Event(id, pubKey, createdAt, kind, tags, content, sig)
}
