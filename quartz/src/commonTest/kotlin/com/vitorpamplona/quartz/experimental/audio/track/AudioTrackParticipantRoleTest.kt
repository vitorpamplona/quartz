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
package com.vitorpamplona.quartz.experimental.audio.track

import com.vitorpamplona.quartz.experimental.audio.track.tags.ParticipantTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTrackParticipantRoleTest {
    private val host = "d91191e30e00444b942c0e82cad470b32af171764c2275bee0bd99377efd4075"
    private val guest = "fa984bd7dbb282f07e16e7ae87b26a2a7b9b90b7246a44771f0cf5ae58018f52"
    private val relay = "wss://relay.damus.io/"
    private val zero = "00".repeat(32)
    private val sig = "00".repeat(64)

    /** A Zapstr podcast episode from nostr.mom (2023-04-25), content and long urls cut: the role is in `p[2]`. */
    private val zapstrEpisode =
        """{"content":"Nostrovia | The Pablo Episode","created_at":1682433199,"id":"d276f7efd57487f5830faa286e73109334155426eafedba5a7e789f17e5eb88a","kind":31337,"pubkey":"$host","sig":"22b7a9cef52d1ca22da9d2a28e7e5a1e07a508c5810b699bd3022989cfa08b284b4ddce907258ed46d54fe0addff52611e24774482af0ed8f3e7d694e089a1ae","tags":[["d","b07v7s2ic0haospgmeg73i"],["media","https://media.zapstr.live:3118/track","http"],["p","$host","Host"],["p","$guest","Guest"],["c","Podcast"],["price","402"],["subject","Nostrovia | The Pablo Episode"]]}"""

    @Test
    fun readsZapstrRolesFromTheThirdSlot() {
        val track = Event.fromJson(zapstrEpisode) as AudioTrackEvent

        assertEquals(listOf("Host", "Guest"), track.participants().map { it.role })
        assertEquals(listOf(null, null), track.participants().map { it.relayHint })
        assertEquals(listOf(host, guest), track.participantKeys())
    }

    @Test
    fun readsTheRoleAfterARelayHint() {
        val tag = ParticipantTag.parse(arrayOf("p", host, relay, "Artist"))

        assertEquals("Artist", tag?.role)
        assertEquals(relay, tag?.relayHint?.url)
        assertEquals("Host", ParticipantTag.parse(arrayOf("p", host, "", "Host"))?.role)
        assertNull(ParticipantTag.parse(arrayOf("p", host, relay))?.role)
        assertNull(ParticipantTag.parse(arrayOf("p", host))?.role)
    }

    /** Zapstr writes several roles in one slot (`"Artist, Producer"`, `"Producer, "` on nostr.mom, 2023-07-11). */
    @Test
    fun splitsCommaSeparatedRoles() {
        assertEquals(listOf("Artist", "Producer"), ParticipantTag.parse(arrayOf("p", host, "Artist, Producer"))?.roles())
        assertEquals(listOf("Producer"), ParticipantTag.parse(arrayOf("p", host, "Producer, "))?.roles())
        assertTrue(ParticipantTag.parse(arrayOf("p", host))!!.roles().isEmpty())
    }

    @Test
    fun assembleRoundTrips() {
        val zapstr = ParticipantTag(host, null, "Host")
        val withRelay = ParticipantTag(host, NormalizedRelayUrl(relay), "Host")

        assertEquals(listOf("p", host, "Host"), zapstr.toTagArray().toList())
        assertEquals(listOf("p", host, relay, "Host"), withRelay.toTagArray().toList())
        assertEquals(zapstr, ParticipantTag.parse(zapstr.toTagArray()))
        assertEquals(withRelay, ParticipantTag.parse(withRelay.toTagArray()))
    }

    @Test
    fun buildWritesParticipants() {
        val template = AudioTrackEvent.build("Podcast", "https://example.com/a.mp3", subject = "Ep 1") { participant(ParticipantTag(guest, null, "Guest")) }
        val track = AudioTrackEvent(zero, host, template.createdAt, template.tags, template.content, sig)

        assertEquals(listOf(ParticipantTag(guest, null, "Guest")), track.participants())
    }
}
