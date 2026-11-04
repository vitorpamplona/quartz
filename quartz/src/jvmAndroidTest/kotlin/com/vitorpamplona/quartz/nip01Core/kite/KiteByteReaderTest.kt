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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The byte reader either reads a frame exactly as Kite reads the frame's text (decoded as
 * OkHttp decodes it), or leaves it to Kite (null). Never a different event.
 */
class KiteByteReaderTest {
    private fun kite(text: String): Result<Message> = runCatching { KiteReader.readMessage(text) }

    private fun assertSameEvent(
        expected: Event,
        actual: Event,
        what: String,
    ) {
        assertEquals(expected::class, actual::class, "$what: class")
        assertEquals(expected.id, actual.id, "$what: id")
        assertEquals(expected.pubKey, actual.pubKey, "$what: pubkey")
        assertEquals(expected.createdAt, actual.createdAt, "$what: created_at")
        assertEquals(expected.kind, actual.kind, "$what: kind")
        assertEquals(expected.content, actual.content, "$what: content")
        assertEquals(expected.sig, actual.sig, "$what: sig")
        assertEquals(expected.tags.size, actual.tags.size, "$what: tag count")
        for (t in expected.tags.indices) assertTrue(expected.tags[t].contentEquals(actual.tags[t]), "$what: tag $t")
    }

    /** Checks [bytes] both ways; returns whether the byte reader read it (false: left to Kite). */
    private fun check(
        bytes: ByteArray,
        what: String = bytes.decodeToString(),
    ): Boolean {
        val read = KiteByteReader.readEventMessage(bytes, bytes.size) ?: return false
        val expected = kite(bytes.decodeToString()).getOrElse { fail("$what: Kite rejects what the byte reader read: $it") }
        if (expected !is EventMessage) fail("$what: Kite read a ${expected::class.simpleName}")
        assertEquals(expected.subId, read.subId, "$what: subId")
        assertSameEvent(expected.event, read.event, what)
        return true
    }

    private fun reads(frame: String) = assertTrue(check(frame.encodeToByteArray()), "should read: $frame")

    private fun leaves(frame: String) = assertTrue(!check(frame.encodeToByteArray()), "should leave to Kite: $frame")

    private val id = "a".repeat(64)
    private val pk = "b".repeat(64)
    private val sig = "c".repeat(128)

    private fun ev(
        content: String = "\"hi\"",
        tags: String = "[]",
        extra: String = "",
    ) = "{\"id\":\"$id\",\"pubkey\":\"$pk\",\"created_at\":1700000000,\"kind\":1,\"tags\":$tags,\"content\":$content,\"sig\":\"$sig\"$extra}"

    @Test
    fun plainFrames() {
        reads("[\"EVENT\",\"s\",${ev()}]")
        reads(" [ \"EVENT\" , \"s\" ,\n${ev()}\t] \r\n")
        reads("[\"EVENT\",\"s\",${ev(tags = "[[\"p\",\"$pk\"],[\"e\",\"$id\",\"wss://r/\",\"root\"],[],[\"t\"],[\"x\",\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\"]]")}]")
        reads("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\"}]")
        reads("[\"EVENT\",\"s\",${ev(content = "null")}]")
        reads("[\"EVENT\",\"s\",{\"created_at\":0,\"kind\":0,\"pubkey\":\"$pk\",\"pubkey\":\"$id\"}]")
        // Kite matches keys by length, first and last char: a misspelled key reads as the field.
        reads("[\"EVENT\",\"s\",{\"pubKey\":\"$pk\"}]")
    }

    @Test
    fun escapesAndUnicode() {
        reads("[\"EVENT\",\"s\",${ev(content = "\"line\\nbreak \\\"quoted\\\" \\\\ \\/ \\b\\f\\r\\t \\u00e9 \\uD83D\\uDE00\"")}]")
        reads("[\"EVENT\",\"s\",${ev(content = "\"\u00e9\u00e8 \u4e2d\u6587 \uD83D\uDE00 raw\"")}]")
        reads("[\"EVENT\",\"s\",${ev(content = "\"\u00e9\\n\u4e2d\\u0041\uD83D\uDE00\\\\\"")}]")
        reads("[\"EVENT\",\"s\",${ev(content = "\"\\n\"")}]")
        reads("[\"EVENT\",\"s\",${ev(content = "\"\u0001raw control\u001f\"")}]")
        reads("[\"EVENT\",\"s\\u0041b\",${ev(tags = "[[\"\\u0070\",\"x\\ny\"],[\"\u00e9\",\"\u00e9\"]]")}]")
        reads("[\"EVENT\",\"s\",${ev(content = "\"" + "x\\n".repeat(20_000) + "\"")}]")
    }

    @Test
    fun malformedUtf8ReadsAsTheTextWouldHaveIt() {
        val head = "[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"content\":\"".encodeToByteArray()
        val tail = "\"}]".encodeToByteArray()
        for (bad in listOf(byteArrayOf(0xC3.toByte()), byteArrayOf(0xE2.toByte(), 0x82.toByte()), byteArrayOf(0xFF.toByte(), 0x41), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertTrue(check(head + bad + tail))
            assertTrue(check(head + bad + "\\n".encodeToByteArray() + bad + tail))
        }
    }

    @Test
    fun leftToKite() {
        leaves("[\"EOSE\",\"s\"]")
        leaves("[\"NOTICE\",\"hi\"]")
        leaves("[\"EVENT\",\"s\",${ev(extra = ",\"seen\":1")}]")
        leaves("[\"EVENT\",\"s\",${ev()},\"extra\"]")
        leaves("[\"EVENT\",\"s\",${ev(tags = "[[\"p\",null]]")}]")
        leaves("[\"EVENT\",\"s\",${ev(tags = "[[\"p\",1]]")}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"created_at\":1.7e9}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"created_at\":-1}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"created_at\":0123}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"kind\":99999999999}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"$pk\",\"content\":1}]")
        leaves("[\"EVENT\",\"s\",{\"id\":\"$id\"}]")
        leaves("[\"EVENT\",\"s\",{\"pubkey\":\"\"}]")
        leaves("[\"EVENT\",\"s\",${ev(content = "\"bad \\x escape\"")}]")
        leaves("[\"EVENT\",\"s\",${ev(content = "\"cut \\u00\"")}]")
        leaves("[\"EVENT\",\"s\",${ev()}")
        leaves("[\"EVENT\",\"s\",${ev()}]x")
        leaves("[\"EVENT\",\"s\"")
        leaves("")
    }

    private fun corpus(): List<String>? {
        val path = System.getenv("KITE_CORPUS") ?: return null
        return File(path).readLines().filter { it.isNotBlank() }
    }

    @Test
    fun everyCorpusEventReadsAsKiteReadsIt() {
        val lines = corpus() ?: return
        var read = 0
        for ((n, line) in lines.withIndex()) {
            val frame = "[\"EVENT\",\"sub-1\",$line]".encodeToByteArray()
            if (check(frame, "line $n")) read++
        }
        println("KiteByteReader: read $read of ${lines.size} corpus events, ${lines.size - read} left to Kite")
        assertTrue(read > 0)
    }

    @Test
    fun mutatedFramesNeverReadDifferently() {
        val lines = corpus()?.take(2_000) ?: listOf(ev(), ev(content = "\"\\n\u00e9\""), ev(tags = "[[\"p\",\"$pk\"]]"))
        val rnd = Random(42)
        val alphabet = "\"\\{}[],: nu0123456789eE.-abcdef\u00e9\u4e2d".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x80.toByte(), 0xFF.toByte())
        var read = 0
        repeat(200_000) {
            val base = "[\"EVENT\",\"s\",${lines[rnd.nextInt(lines.size)]}]".encodeToByteArray()
            val b = base.copyOf()
            repeat(1 + rnd.nextInt(3)) {
                val at = rnd.nextInt(b.size)
                b[at] = alphabet[rnd.nextInt(alphabet.size)]
            }
            val cut = if (rnd.nextInt(10) == 0) rnd.nextInt(b.size) else b.size
            val frame = b.copyOf(cut)
            if (check(frame, "mutation $it")) read++
        }
        println("KiteByteReader: read $read of 200000 mutated frames")
    }

    @Test
    fun nothingCarriesOverBetweenFrames() {
        val a = "[\"EVENT\",\"s\",${ev(tags = "[[\"a\",\"1\",\"2\",\"3\"],[\"b\"],[\"c\"]]", content = "\"x\\ny\"")}]".encodeToByteArray()
        val bad = "[\"EVENT\",\"s\",${ev(tags = "[[\"a\",\"1\",\"2\",\"3\"],[\"b\"],[\"c\",1]]")}]".encodeToByteArray()
        val c = "[\"EVENT\",\"s\",{\"pubkey\":\"$pk\"}]".encodeToByteArray()
        assertTrue(check(a))
        assertNull(KiteByteReader.readEventMessage(bad, bad.size))
        val read = assertNotNull(KiteByteReader.readEventMessage(c, c.size))
        assertEquals(0, read.event.tags.size)
        assertEquals("", read.event.content)
        assertTrue(check(a))
    }
}
