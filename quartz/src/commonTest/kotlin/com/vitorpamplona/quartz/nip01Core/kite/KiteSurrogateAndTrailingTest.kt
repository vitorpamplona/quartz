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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.utils.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KiteSurrogateAndTrailingTest {
    private val priv = ByteArray(32).also { it[31] = 7 }
    private val pub = Hex.encode(Nip01Crypto.pubKeyCreate(priv))

    private fun signed(content: String): Event {
        val at = 1_700_000_000L
        val tags = arrayOf(arrayOf("t", content))
        val id = EventHasher.hashIdBytes(pub, at, 1, tags, content)
        val sig = Nip01Crypto.sign(id, priv, ByteArray(32))
        return Event(Hex.encode(id), pub, at, 1, tags, content, Hex.encode(sig))
    }

    @Test
    fun aLoneSurrogateIsEscapedInTextModeSoTheWireKeepsTheId() {
        // A cut emoji. Written raw, the UTF-8 wire turns it into '?', and the id no longer matches.
        val e = signed("cut emoji \uD83D")
        assertTrue(e.verify())
        val json = e.toJson()
        assertTrue(json.contains("\\ud83d"), json)
        val wire = json.encodeToByteArray().decodeToString()
        val back = Event.fromJson(wire)
        assertEquals("cut emoji \uD83D", back.content)
        assertTrue(back.verify())
    }

    @Test
    fun aValidPairAndALoneLowSurrogateRoundTrip() {
        val e = signed("ok 😀 then \uDE00 and \uD83D😀")
        val json = e.toJson()
        assertTrue(json.contains("😀"), "a valid pair stays as is")
        val back = Event.fromJson(json.encodeToByteArray().decodeToString())
        assertEquals(e.content, back.content)
        assertTrue(back.verify())
    }

    @Test
    fun trailingGarbageAfterTheTopLevelValueIsRefused() {
        val json = signed("hello").toJson()
        assertEquals("hello", Event.fromJson("$json \n").content)
        assertFailsWith<IllegalArgumentException> { Event.fromJson(json + "garbage]]{") }
    }
}
