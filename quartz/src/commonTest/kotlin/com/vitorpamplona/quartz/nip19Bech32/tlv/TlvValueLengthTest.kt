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
package com.vitorpamplona.quartz.nip19Bech32.tlv

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.toNIP19
import com.vitorpamplona.quartz.nip21UriScheme.toNostrUri
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A TLV length is one byte. Writing `size.toByte()` for a longer value wrapped it (300 -> 44),
 * so `NAddress.create` with a 300-byte d-tag returned a 560-char naddr that even quartz decoded
 * to null. Values that cannot fit are now rejected (d-tags, any TlvBuilder value) or dropped
 * (relay hints, which are optional).
 */
class TlvValueLengthTest {
    private val pubKey = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val id = "fae5f3ae5b5bd5e7aa8a97d8a06b61f7d1a7ad5d52a3f0d4e4e1b23d1f2a3b4c"
    private val relay = NormalizedRelayUrl("wss://relay.damus.io/")
    private val longRelay = NormalizedRelayUrl("wss://relay.example.com/" + "a".repeat(240))

    @Test
    fun tlvBuilderRejectsValuesOverOneLengthByte() {
        TlvBuilder().addString(0, "a".repeat(255))
        assertFailsWith<IllegalArgumentException> { TlvBuilder().addString(0, "a".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { TlvBuilder().addHex(0, "ab".repeat(256)) }
    }

    @Test
    fun dTagOf255BytesRoundTrips() {
        val dTag = "d".repeat(255)
        val naddr = NAddress.create(30023, pubKey, dTag, relay)
        assertEquals(NAddress(30023, pubKey, dTag, listOf(relay)), Nip19Parser.uriToRoute(naddr)?.entity)
    }

    @Test
    fun dTagOver255BytesHasNoNaddr() {
        val dTag = "d".repeat(300)
        assertNull(NAddress.createOrNull(30023, pubKey, dTag, relay))
        assertNull(NAddress.createOrNull(30023, pubKey, dTag, listOf(relay)))
        assertNull(NAddress.createNostrUriOrNull(30023, pubKey, dTag, relay))
        assertFailsWith<IllegalArgumentException> { NAddress.create(30023, pubKey, dTag, relay) }
        assertFailsWith<IllegalArgumentException> { NAddress.create(30023, pubKey, dTag, listOf(relay)) }
    }

    @Test
    fun dTagLengthCountsUtf8Bytes() {
        // U+00E9 is 2 bytes in UTF-8: 127 of them fit (254 bytes), 128 do not (256 bytes)
        val fits = "\u00e9".repeat(127)
        val naddr = NAddress.create(30023, pubKey, fits, emptyList())
        assertEquals(fits, (Nip19Parser.uriToRoute(naddr)?.entity as NAddress).dTag)
        assertNull(NAddress.createOrNull(30023, pubKey, "\u00e9".repeat(128), emptyList()))

        // a surrogate pair is 4 bytes
        val emoji = "caf\u00e9 \uD83D\uDE00"
        assertEquals(emoji, (Nip19Parser.uriToRoute(NAddress.create(30023, pubKey, emoji, relay))?.entity as NAddress).dTag)
    }

    @Test
    fun relayHintsTooLongForARecordAreDropped() {
        assertTrue(longRelay.url.length > 255)

        val nevent = Nip19Parser.uriToRoute(NEvent.create(id, pubKey, 1, listOf(longRelay, relay)))?.entity as NEvent
        assertEquals(listOf(relay), nevent.relay)
        assertEquals(pubKey, nevent.author)
        assertEquals(1, nevent.kind)

        val single = Nip19Parser.uriToRoute(NEvent.create(id, pubKey, 1, longRelay))?.entity as NEvent
        assertEquals(emptyList(), single.relay)

        val nprofile = Nip19Parser.uriToRoute(NProfile.create(pubKey, listOf(relay, longRelay)))?.entity as NProfile
        assertEquals(listOf(relay), nprofile.relay)

        val naddr = Nip19Parser.uriToRoute(NAddress.create(30023, pubKey, "x", listOf(longRelay, relay)))?.entity as NAddress
        assertEquals(listOf(relay), naddr.relay)
    }

    @Test
    fun anAddressableEventWithAnOverlongDTagIsCitedByItsNevent() {
        val event = LongFormContentEvent(id, pubKey, 1, arrayOf(arrayOf("d", "d".repeat(300))), "", "")
        assertEquals(NEvent.create(id, pubKey, LongFormContentEvent.KIND, relay), event.toNIP19(relay))
        assertEquals("nostr:" + event.toNIP19(relay), event.toNostrUri(relay))

        val short = LongFormContentEvent(id, pubKey, 1, arrayOf(arrayOf("d", "slug")), "", "")
        assertEquals(NAddress.create(LongFormContentEvent.KIND, pubKey, "slug", relay), short.toNIP19(relay))
        assertEquals("nostr:" + short.toNIP19(relay), short.toNostrUri(relay))
    }
}
