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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NRelay
import com.vitorpamplona.quartz.nip19Bech32.tlv.Tlv
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The entity encoders and decoders, which now size and read their TLV in place, against the
 * generic [TlvBuilder] / [Tlv] path plus the 5-bit Bech32 encoder they replaced.
 */
class Nip19EntityCodecTest {
    private val rnd = Random(21)

    private fun hex32() = rnd.nextBytes(32).toHexKey()

    private val relays =
        listOf("wss://relay.damus.io/", "wss://nos.lol/", "wss://relay.primal.net/", "wss://nostr.wine/").map { NormalizedRelayUrl(it) }

    private fun reference(
        hrp: String,
        tlv: TlvBuilder.() -> Unit,
    ) = Bech32.encode(hrp, Bech32.eight2five(TlvBuilder().apply(tlv).build()), Bech32.Encoding.Bech32)

    @Test
    fun encodersMatchTheTlvBuilderPath() {
        repeat(200) { i ->
            val id = hex32()
            val author = hex32()
            val hints = relays.take(i % 5)
            val kind = listOf(0, 1, 30023, 65535)[i % 4]
            val dTag = "slug-$i-" + "x".repeat(i % 40)

            assertEquals(Bech32.encode("npub", Bech32.eight2five(Bech32.decodeBytes(NPub.create(author)).second), Bech32.Encoding.Bech32), NPub.create(author))
            assertEquals(
                reference("nevent") {
                    addHex(0, id)
                    hints.forEach { addString(1, it.url) }
                    addHex(2, author)
                    addInt(3, kind)
                },
                NEvent.create(id, author, kind, hints),
            )
            assertEquals(reference("nevent") { addHex(0, id) }, NEvent.create(id, null, null, null as NormalizedRelayUrl?))
            assertEquals(
                reference("nevent") {
                    addHex(0, id)
                    addString(1, relays[0].url)
                    addHex(2, author)
                },
                NEvent.create(id, author, null, relays[0]),
            )
            assertEquals(
                reference("nprofile") {
                    addHex(0, author)
                    hints.forEach { addString(1, it.url) }
                },
                NProfile.create(author, hints),
            )
            assertEquals(reference("nprofile") { addHex(0, author) }, NProfile.create(author, null as NormalizedRelayUrl?))
            assertEquals(
                reference("naddr") {
                    addString(0, dTag)
                    hints.forEach { addString(1, it.url) }
                    addHex(2, author)
                    addInt(3, kind)
                },
                NAddress.create(kind, author, dTag, hints),
            )
            assertEquals(
                reference("naddr") {
                    addString(0, "")
                    addHex(2, author)
                    addInt(3, kind)
                },
                NAddress.create(kind, author, "", null as NormalizedRelayUrl?),
            )

            assertEquals("nostr:" + NEvent.create(id, author, kind, hints), NEvent.createNostrUri(id, author, kind, hints))
            assertEquals("nostr:" + NEvent.create(id, author, kind, relays[1]), NEvent.createNostrUri(id, author, kind, relays[1]))
            assertEquals("nostr:" + NProfile.create(author, hints), NProfile.createNostrUri(author, hints))
            assertEquals("nostr:" + NProfile.create(author, relays[1]), NProfile.createNostrUri(author, relays[1]))
            assertEquals("nostr:" + NAddress.create(kind, author, dTag, hints), NAddress.createNostrUriOrNull(kind, author, dTag, hints))
            assertEquals("nostr:" + NPub.create(author), NPub.createNostrUri(author))
        }
    }

    @Test
    fun decodersMatchTheTlvPath() {
        repeat(200) { i ->
            val id = hex32()
            val author = hex32()
            val hints = relays.take(i % 5)
            val kind = listOf(0, 1, 30023, 65535)[i % 4]
            val dTag = "slug-$i"

            assertEquals(NEvent(id, hints, author, kind), Nip19Parser.uriToRoute(NEvent.create(id, author, kind, hints))?.entity)
            assertEquals(NEvent(id, emptyList(), null, null), Nip19Parser.uriToRoute(NEvent.create(id, null, null, emptyList()))?.entity)
            assertEquals(NProfile(author, hints), Nip19Parser.uriToRoute(NProfile.create(author, hints))?.entity)
            assertEquals(NAddress(kind, author, dTag, hints), Nip19Parser.uriToRoute(NAddress.create(kind, author, dTag, hints))?.entity)
            assertEquals(NAddress(kind, author, dTag, hints), NAddress.parse("nostr:" + NAddress.create(kind, author, dTag, hints)))
            assertEquals(NPub(author), Nip19Parser.uriToRoute(NPub.create(author))?.entity)
            assertEquals(NNote(id), Nip19Parser.uriToRoute(NNote.create(id))?.entity)
        }
    }

    @Test
    fun onlyTheFirstOfASingleValuedRecordCounts() {
        val id = hex32()
        val author = hex32()
        // a second id, a malformed second author and a second kind are ignored, as Tlv.firstAs* did
        val bytes =
            TlvBuilder()
                .apply {
                    addHex(0, id)
                    addHex(0, hex32())
                    addHex(2, author)
                    addHex(2, "abcd")
                    addInt(3, 7)
                    addInt(3, 9)
                }.build()
        assertEquals(NEvent(id, emptyList(), author, 7), NEvent.parse(bytes))

        // a malformed FIRST author or kind is dropped, not replaced by a later one
        val badAuthor =
            TlvBuilder()
                .apply {
                    addHex(0, id)
                    addHex(2, "abcd")
                    addHex(2, author)
                }.build()
        assertEquals(NEvent(id, emptyList(), null, null), NEvent.parse(badAuthor))
    }

    @Test
    fun nrelayKeepsEveryRelay() {
        val bytes =
            TlvBuilder()
                .apply {
                    addString(0, "wss://a.com")
                    addString(0, "wss://b.com")
                }.build()
        assertEquals(NRelay(listOf("wss://a.com", "wss://b.com")), NRelay.parse(bytes))
        assertEquals(NRelay(Tlv.parse(bytes).asStringList(0)!!), NRelay.parse(bytes))
        assertEquals(null, NRelay.parse(TlvBuilder().apply { addString(1, "wss://a.com") }.build()))
    }
}
