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
package com.vitorpamplona.quartz.nip01Core.core

import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.eventHints
import com.vitorpamplona.quartz.nip19Bech32.eventHintsTo
import com.vitorpamplona.quartz.nip19Bech32.eventIds
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHints
import com.vitorpamplona.quartz.nip19Bech32.pubKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The hint providers run on every relay copy of every event and mostly find nothing, so their
 * building blocks must not allocate a list for an empty result.
 */
class FastMapNotNullTest {
    private val id1 = "a".repeat(64)
    private val id2 = "b".repeat(64)
    private val pk = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val relay = RelayUrlNormalizer.normalizeOrNull("wss://relay.damus.io")!!

    private val tags =
        arrayOf(
            arrayOf("e", id1, relay.url),
            arrayOf("alt", "a note"),
            arrayOf("p", pk),
            arrayOf("e", id2),
        )

    @Test
    fun nothingFoundIsTheSharedEmptyList() {
        assertSame(emptyList<String>(), tags.fastMapNotNull { null as String? })
        assertSame(emptyList<String>(), emptyArray<Array<String>>().fastMapNotNull(ETag::parseId))
        assertNull(tags.fastMapNotNullTo(null) { null as String? })
    }

    @Test
    fun keepsTagOrderLikeMapNotNull() {
        assertEquals(tags.mapNotNull(ETag::parseId), tags.fastMapNotNull(ETag::parseId))
        assertEquals(listOf(id1, id2), tags.fastMapNotNull(ETag::parseId))
    }

    @Test
    fun chainedScansFillOneListInOrder() {
        var out: ArrayList<String>? = null
        out = tags.fastMapNotNullTo(out, PTag::parseKey)
        val afterFirst = out
        out = tags.fastMapNotNullTo(out, ETag::parseId)
        assertSame(afterFirst, out, "the second scan appends to the first one's list")
        assertEquals<List<String>?>(listOf(pk, id1, id2), out)
    }

    @Test
    fun contentWithoutEntitiesParsesToTheSharedEmptyList() {
        val cited = Nip19Parser.parseAll("gm, no entities here")
        assertSame<Any>(emptyList<Any>(), cited)
        assertSame<Any>(emptyList<Any>(), Nip19Parser.parseAllStandalone("gm"))
        assertSame<Any>(emptyList<Any>(), Nip19Parser.parseAllEvents("gm"))
        assertSame<Any>(emptyList<Any>(), cited.eventHints())
        assertSame<Any>(emptyList<Any>(), cited.eventIds())
        assertSame<Any>(emptyList<Any>(), cited.pubKeyHints())
        assertSame<Any>(emptyList<Any>(), cited.pubKeys())
    }

    @Test
    fun entityHintsAppendAfterWhatIsThere() {
        val nevent = NEvent(id2, listOf(relay), null, null)
        val start = arrayListOf(EventIdHint(id1, relay))
        val out = listOf(nevent).eventHintsTo(start)
        assertSame(start, out)
        assertEquals<List<EventIdHint>?>(listOf(EventIdHint(id1, relay), EventIdHint(id2, relay)), out)
        assertEquals(listOf(EventIdHint(id2, relay)), listOf(nevent).eventHints())
    }
}
