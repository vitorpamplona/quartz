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
package com.vitorpamplona.quartz.nip01Core.kite

import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The caching decoder hands Kite the subscription id and event id it already cut from a frame;
 * the parse reuses them only where the frame holds exactly those characters.
 */
class KiteReaderHintTest {
    private val id = "98b574c3527f0ffb30b7271084e3f07480733c7289f8de424d29eae82e36c758"
    private val pk = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val frame = """["EVENT","feed-1",{"id":"$id","pubkey":"$pk","created_at":1,"kind":1,"tags":[],"content":"hi","sig":"${"0".repeat(128)}"}]"""

    private fun read(
        subIdHint: String?,
        idHint: String?,
    ) = KiteReader.readMessage(frame, subIdHint, idHint) as EventMessage

    @Test
    fun matchingHintsAreReturnedAsTheSameInstances() {
        val subHint = "feed-1".let { StringBuilder(it).toString() }
        val idHint = StringBuilder(id).toString()
        val msg = read(subHint, idHint)
        assertSame(subHint, msg.subId)
        assertSame(idHint, msg.event.id)
    }

    @Test
    fun aHintTheFrameDoesNotHoldIsIgnored() {
        val other = "a".repeat(64)
        val msg = read("feed-2", other)
        assertEquals("feed-1", msg.subId)
        assertEquals(id, msg.event.id)
        assertNotSame(other, msg.event.id)
        // a prefix of the real value is not a match either
        assertEquals("feed-1", read("feed", null).subId)
    }

    @Test
    fun hintsDoNotOutliveTheirParse() {
        read("feed-1", StringBuilder(id).toString())
        val plain = OptimizedJsonMapper.fromJsonToMessage(frame) as EventMessage
        assertEquals(id, plain.event.id)
        assertEquals("feed-1", plain.subId)
    }

    @Test
    fun hintedAndPlainParsesAgree() {
        val plain = OptimizedJsonMapper.fromJsonToMessage(frame) as EventMessage
        val hinted = read("feed-1", id)
        assertEquals(plain.subId, hinted.subId)
        assertEquals(plain.event.toJson(), hinted.event.toJson())
    }
}
