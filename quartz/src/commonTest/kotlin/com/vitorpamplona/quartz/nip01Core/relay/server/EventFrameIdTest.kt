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
package com.vitorpamplona.quartz.nip01Core.relay.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventFrameIdTest {
    private val id = "a".repeat(63) + "1"
    private val decoy = "b".repeat(64)

    @Test
    fun readsTheIdOfAnEventFrame() {
        assertEquals(id, eventIdOfFrame("""["EVENT",{"id":"$id","kind":1,"tags":[],"content":"hi"}]"""))
    }

    @Test
    fun toleratesWhitespaceAndKeyOrder() {
        assertEquals(id, eventIdOfFrame(""" [ "EVENT" , { "kind" : 3, "tags" : [["p","x"]], "id" : "$id" } ] """))
    }

    @Test
    fun ignoresAnIdInsideContent() {
        val frame = """["EVENT",{"content":"{\"id\":\"$decoy\"}","id":"$id"}]"""
        assertEquals(id, eventIdOfFrame(frame))
    }

    @Test
    fun ignoresAnIdInsideTags() {
        val frame = """["EVENT",{"tags":[["id","$decoy"],["e","$decoy"]],"id":"$id"}]"""
        assertEquals(id, eventIdOfFrame(frame))
    }

    @Test
    fun survivesEscapedBackslashesBeforeTheId() {
        val frame = """["EVENT",{"content":"ends in a backslash \\","id":"$id"}]"""
        assertEquals(id, eventIdOfFrame(frame))
    }

    @Test
    fun readsTheIdOfAnAuthFrame() {
        // NIP-42 owes an AUTH its OK as NIP-01 owes an EVENT one, so a refused AUTH needs its id too.
        assertEquals(id, eventIdOfFrame("""["AUTH",{"id":"$id","kind":22242,"tags":[["challenge","c"]],"content":""}]"""))
        assertEquals(id, eventIdOfFrame("""["AUTH",{"kind":1,"id":"$id"}]"""))
    }

    @Test
    fun answersNullForOtherFrames() {
        assertNull(eventIdOfFrame("""["REQ","sub",{"ids":["$id"]}]"""))
        assertNull(eventIdOfFrame("""["EVENT",{"kind":1}]"""))
        assertNull(eventIdOfFrame("""["EVENT",{"id":"not-hex"}]"""))
        assertNull(eventIdOfFrame("""["EVENT",{"id":"$id"""))
        assertNull(eventIdOfFrame("not json"))
        assertNull(eventIdOfFrame("""["CLOSE","$id"]"""))
        assertNull(eventIdOfFrame("""["AUTH","$id"]"""))
    }
}
