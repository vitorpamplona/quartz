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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.ControlFixtures
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CORD-03 §2 Channel order: a `position` rank key first, Armada's legacy `custom["armada.order"]`
 * next, then name as UTF-8 bytes, then id.
 */
class ConcordChannelOrderTest {
    private val owner = "0f".repeat(32)

    private fun channel(
        id: String,
        content: String,
    ) = ControlEdition(ControlEntityKind.CHANNEL, id.hexToByteArray(), 1, null, null, content, owner, "r-$id", 0)

    private fun order(vararg channels: Pair<String, String>): List<String> =
        ControlFixtures
            .fold(channels.map { channel(it.first, it.second) }, owner)
            .orderedChannels
            .map { it.definition.name }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun positionedChannelsComeFirstInKeyOrder() {
        assertEquals(
            listOf("zeta", "alpha", "beta", "gamma"),
            order(
                "a1".repeat(32) to """{"name":"gamma","private":false}""",
                "a2".repeat(32) to """{"name":"alpha","private":false,"position":"V"}""",
                "a3".repeat(32) to """{"name":"zeta","private":false,"position":"M"}""",
                "a4".repeat(32) to """{"name":"beta","private":false}""",
            ),
        )
    }

    @Test
    fun armadaLegacyOrderSortsAfterSpecPositionsAndBeforeTheRest() {
        assertEquals(
            listOf("spec", "zeta", "alpha", "beta", "general"),
            order(
                "b1".repeat(32) to """{"name":"general","private":false}""",
                "b2".repeat(32) to """{"name":"alpha","private":false,"custom":{"armada.order":{"position":1}}}""",
                "b3".repeat(32) to """{"name":"zeta","private":false,"custom":{"armada.order":{"position":0}}}""",
                "b4".repeat(32) to """{"name":"beta","private":false}""",
                "b5".repeat(32) to """{"name":"spec","private":false,"position":"z","custom":{"armada.order":{"position":9}}}""",
            ),
        )
    }

    @Test
    fun malformedPositionsReadAsAbsent() {
        assertNull(ConcordChannelOrder.positionOf(obj("""{"position":"M0"}""")), "a rank key never ends in 0")
        assertNull(ConcordChannelOrder.positionOf(obj("""{"position":"a-b"}""")), "outside the alphabet")
        assertNull(ConcordChannelOrder.positionOf(obj("""{"position":3}""")), "not a string")
        assertNull(ConcordChannelOrder.positionOf(obj("""{"position":""}""")))
        assertEquals("M", ConcordChannelOrder.positionOf(obj("""{"position":"M"}""")))

        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":{"position":-1}}}""")))
        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":{"position":1.5}}}""")))
        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":{"position":"1"}}}""")))
        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":{"position":9007199254740992}}}""")))
        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":3}}""")))
        assertNull(ConcordChannelOrder.armadaOrderOf(obj("""{"custom":[]}""")))
        assertEquals(2L, ConcordChannelOrder.armadaOrderOf(obj("""{"custom":{"armada.order":{"position":2.0}}}""")))
    }

    @Test
    fun namesCompareAsUtf8BytesThenIds() {
        assertEquals(
            listOf("Zed", "alpha", "same", "same", "été"),
            order(
                "c1".repeat(32) to """{"name":"alpha","private":false}""",
                "c2".repeat(32) to """{"name":"été","private":false}""",
                "c4".repeat(32) to """{"name":"same","private":false}""",
                "c3".repeat(32) to """{"name":"same","private":false}""",
                "c5".repeat(32) to """{"name":"Zed","private":false}""",
            ),
        )
        val state =
            ControlFixtures.fold(
                listOf(channel("c4".repeat(32), """{"name":"same","private":false}"""), channel("c3".repeat(32), """{"name":"same","private":false}""")),
                owner,
            )
        assertEquals(listOf("c3".repeat(32), "c4".repeat(32)), state.orderedChannels.map { it.channelIdHex })
    }

    @Test
    fun theFoldCarriesBothOrders() {
        val folded =
            ControlFixtures
                .fold(listOf(channel("d1".repeat(32), """{"name":"x","private":false,"position":"k","custom":{"armada.order":{"position":4}}}""")), owner)
                .channels
                .getValue("d1".repeat(32))
        assertEquals("k", folded.position)
        assertEquals(4L, folded.armadaOrder)
    }
}
