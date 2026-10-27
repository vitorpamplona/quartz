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
package com.vitorpamplona.quartz.experimental.pressstr.magazine

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagazineEventTest {
    // Published by PressStr (pressstr.shakespeare.wtf).
    private val pressstrMagazine =
        """{"id":"0f13d8be178a92f7c6dc585a7242ff6fd6cf4e55e1c0194c66501255dd768d5d","pubkey":"a8d1560d6a647d501699167246f237b36fb123f89168fda11dc743533fec7a08","created_at":1779721997,"kind":34609,"tags":[["d","stl1988-s-personal-mag"],["title","stl1988's Personal Magazine"],["alt","Magazine: stl1988's Personal Magazine"],["image","https://cdn.nostrcheck.me/eb0d459852b2622a74046abf51f2e64b6b73a3552266e6676f2036912f4fcb70.webp"],["summary","A personal mag created with InkPress - https://inkpress.shakespeare.wtf"],["l","en"],["url","https://stl1988.shakespeare.wtf"],["t","bitcoin"],["t","nostr"],["t","ai"],["t","asperger"],["t","autism"],["t","nature"],["t","crazy stuff"],["t","inkpress"],["t","shakespeare"],["t","shakespearediy"],["t","nowhere"],["t","nature"],["client","pressstr.shakespeare.wtf"]],"content":"Forum: https://nowhr.xyz/s#PZBvT8IwEMbTb1LqW9m6TSISIdkcBgwogoKaRTpL9yd2bK6FDUf47HYBfHNP7rnf3eVuZf483i_cRfYdzO1FMv3YTbdDS5jNcriMp_7EjAZ05b7-LgULw0shuXHTbkPSGNv7hjUCqI6Hu2QHSX9IxjbQVM2dkIE8OMTlZJ75OYPkeUbsEmgdj4ttCMuEr0UXRVJmHV0vikIrLC3NQ93EGOuKQHAbs8JJyy7CEEMDGqjncRrnlDNIlau1EKS7o-ZH2W_eQBddYNz2TRPpipeslPAEn9hVrVaLJYp3KGju05ZqIg8GQPDzQYImcSbKSNJ1KjKfsrP7jpV7jfE5l67Ktav_rUEQoN7sZeRxvZ6rltdn9G7n1aiKjWqAq6f-OuSxiNR75hnwvMxlGyloxKC9EaH_xf4ApKBuBFQSiKUG-i0QtZRj2iBt4pE1876jCoIK2su2lz48dCnzGWu2MbwQIsJ-GNii0t-9tnXz4SZaRgWKjp47gg","sig":"a7d14e01eb1ea4f0a3e04b32523800bf21f5d5e992fdbcc9ba643ec3353de10784a85de1148650c878eb1d50fcf6042c3e24f668233cae30d015d42924ee20c3"}"""

    @Test
    fun parsesPressstrMagazine() {
        val event = Event.fromJson(pressstrMagazine)
        assertIs<MagazineEvent>(event)
        assertTrue(event.verify())

        assertEquals("stl1988-s-personal-mag", event.dTag())
        assertEquals("stl1988's Personal Magazine", event.title())
        assertEquals("https://cdn.nostrcheck.me/eb0d459852b2622a74046abf51f2e64b6b73a3552266e6676f2036912f4fcb70.webp", event.image())
        assertEquals("A personal mag created with InkPress - https://inkpress.shakespeare.wtf", event.summary())
        assertEquals("https://stl1988.shakespeare.wtf", event.website())
        assertEquals("en", event.language())
        assertTrue("bitcoin" in event.topics())
        assertTrue("crazy stuff" in event.topics())
        assertTrue(event.content.startsWith("Forum: https://nowhr.xyz/"))
    }

    @Test
    fun isKnownAndSearchable() {
        assertTrue(EventFactory.isKnownKind(MagazineEvent.KIND))

        val event = Event.fromJson(pressstrMagazine)
        assertIs<SearchableEvent>(event)
        assertTrue("Personal Magazine" in event.indexableContent())
        assertTrue("InkPress" in event.indexableContent())
    }

    @Test
    fun buildsSpecShapedMagazine() {
        val template =
            MagazineEvent.build(
                title = "Bitcoin Magazine",
                dTag = "bitcoin-magazine",
                description = "The world's leading Bitcoin magazine.",
                image = "https://blossom.example.com/logo.webp",
                summary = "Bitcoin protocol, culture, and economy.",
                website = "https://bitcoinmagazine.com",
                language = "en",
            )

        val event = NostrSignerSync().sign(template)
        assertIs<MagazineEvent>(EventFactory.create<Event>(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig))

        assertEquals("bitcoin-magazine", event.dTag())
        assertEquals("Bitcoin Magazine", event.title())
        assertEquals("https://bitcoinmagazine.com", event.website())
        assertEquals("Magazine: Bitcoin Magazine", event.alt())
        assertEquals("Bitcoin Magazine", template.tags.title())
        assertNull(MagazineEvent.build(title = "Bare", dTag = "bare").tags.website())
    }
}
