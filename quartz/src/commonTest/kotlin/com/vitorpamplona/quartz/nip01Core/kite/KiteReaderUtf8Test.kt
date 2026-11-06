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

import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.KnownEventLookup
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [KiteReader.readMessageUtf8] must read exactly what decoding the bytes first (as a socket does)
 * and then [KiteReader.readMessage] reads: the same values, and the same failures.
 */
class KiteReaderUtf8Test {
    private val eAcute = "\u00e9"
    private val smile = "\uD83D\uDE00"

    private val pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    private fun frame(
        content: String,
        tagValue: String = "x",
    ) = """["EVENT","s${eAcute}b",{"content":"$content","created_at":1,"id":"ab","kind":1,"pubkey":"$pubkey","sig":"cd","tags":[["t","$tagValue"],["e","1","2","3"]]}]"""

    private fun describe(m: Message): String =
        when (m) {
            is EventMessage -> {
                val e = m.event
                "EVENT ${m.subId}|${e.id}|${e.pubKey}|${e.createdAt}|${e.kind}|${e.tags.map { it.toList() }}|<${e.content}>|${e.sig}"
            }
            is NoticeMessage -> "NOTICE ${m.message}"
            is OkMessage -> "OK ${m.eventId} ${m.success} ${m.message}"
            is ClosedMessage -> "CLOSED ${m.subId} ${m.message}"
            is EoseMessage -> "EOSE ${m.subId}"
            else -> m.toString()
        }

    private fun assertSame(bytes: ByteArray) {
        val viaString = runCatching { KiteReader.readMessage(bytes.decodeToString()) }
        assertSameAs(bytes, viaString, "bytes reader", runCatching { KiteReader.readMessageUtf8(bytes) })
        assertSameAs(bytes, viaString, "Latin-1 view", runCatching { KiteReader.readMessageLatin1View(bytes) })
        // Deferred fields: a lookup that never answers must read exactly as no lookup.
        val asked = ArrayList<String>()
        assertSameAs(
            bytes,
            viaString,
            "deferred, missed",
            runCatching {
                KiteReader.readMessageUtf8(
                    bytes,
                    0,
                    bytes.size,
                    KnownEventLookup {
                        asked.add(it)
                        null
                    },
                )
            },
        )
        val expected = viaString.getOrNull()
        if (expected is EventMessage && asked.isNotEmpty()) assertEquals(1, asked.size, "asked once per frame")
    }

    private fun assertSameAs(
        bytes: ByteArray,
        viaString: Result<Message>,
        name: String,
        viaBytes: Result<Message>,
    ) {
        if (viaString.isFailure) {
            assertTrue(viaBytes.isFailure, "$name parsed what the string path rejects: ${bytes.decodeToString()}")
            assertTrue(viaBytes.exceptionOrNull() is IllegalArgumentException)
            return
        }
        val expected = viaString.getOrThrow()
        val actual = viaBytes.getOrElse { fail("$name failed where the string path parsed ${bytes.decodeToString()}: $it") }
        assertEquals(describe(expected), describe(actual), name)
        if (expected is EventMessage && actual is EventMessage) {
            assertTrue(expected.event.tags.contentDeepEquals(actual.event.tags))
        }
    }

    private fun assertSame(text: String) = assertSame(text.encodeToByteArray())

    @Test
    fun plainAndNonAsciiValues() {
        assertSame(frame("hello"))
        assertSame(frame("ol\u00e1 \u00e9\u00e8 caf\u00e9"))
        assertSame(frame("\u4f60\u597d\u4e16\u754c"))
        assertSame(frame("emoji \uD83D\uDE00\uD83D\uDC4D\uD83C\uDFFD end", "\uD83D\uDE80"))
        assertSame(frame(""))
        assertSame(frame("a".repeat(70_000) + "\u00e9" + "b".repeat(70_000)))
    }

    @Test
    fun escapesNextToNonAscii() {
        assertSame(frame("line\\nnext \u00e9\\t\\\"quoted\\\" \\\\ back"))
        assertSame(frame("\\u00e9\\u4f60\\u0041\\u007f\\u0080\\u07ff\\u0800\\uffff"))
        assertSame(frame("pair \\ud83d\\ude00 and raw \uD83D\uDE00 \\n"))
        assertSame(frame("\\ud83d\\ude00\\ud83d\\ude00"))
        assertSame(frame("\uD83D\uDE00\\n\uD83D\uDE00\\n" + "\u00e9\\n".repeat(500)))
        assertSame(frame("\\/slash \\b\\f\\r"))
    }

    @Test
    fun loneEscapedSurrogatesStayLone() {
        assertSame(frame("lone high \\ud83d end"))
        assertSame(frame("lone high then escape \\ud83d\\n"))
        assertSame(frame("lone high then char \\ud83d\\u0041"))
        assertSame(frame("lone low \\ude00 end"))
        assertSame(frame("\u00e9\\ude00\u00e9\\ud83d"))
        assertSame(frame("high at end \\ud83d"))
    }

    @Test
    fun escapedKeysAndOtherFrames() {
        assertSame("""["EVENT","s",{"\u0063ontent":"x","pubkey":"$pubkey","t\u0061gs":[["$eAcute"]]}]""")
        assertSame("""["EVENT","s",{"\u0063ontent":"$eAcute","pubkey":"$pubkey"}]""")
        assertSame("""["NOTICE","r${eAcute}lay \n says $smile"]""")
        assertSame("""["OK","ab",false,"blocked: $eAcute"]""")
        assertSame("""["CLOSED","s$eAcute","rate-limited: \u00e9"]""")
        assertSame("""["EOSE","$smile"]""")
        assertSame("""  [ "EVENT" , "s" , { "content" : "$eAcute" , "pubkey" : "$pubkey" } ]  """)
    }

    @Test
    fun malformedUtf8IsReplacedTheSameWay() {
        val prefix = """["EVENT","s",{"pubkey":"$pubkey","content":"""".encodeToByteArray()
        val suffix = """"}]""".encodeToByteArray()
        val bad =
            listOf(
                byteArrayOf(0xC3.toByte()),
                byteArrayOf(0xE2.toByte(), 0x82.toByte()),
                byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()),
                byteArrayOf(0xFF.toByte(), 0x41, 0xFE.toByte()),
                byteArrayOf(0x80.toByte(), 0xBF.toByte()),
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
                byteArrayOf(0xC3.toByte(), 0x5C, 0x6E),
                byteArrayOf(0xE2.toByte(), 0x5C, 0x75, 0x30, 0x30, 0x65, 0x39),
                byteArrayOf(0x41, 0x5C, 0x6E, 0x80.toByte(), 0x42),
            )
        for (b in bad) assertSame(prefix + b + suffix)
        // A bad byte outside a string fails both ways.
        assertSame("""["EVENT","s",""".encodeToByteArray() + byteArrayOf(0xC3.toByte()) + """{"pubkey":"$pubkey"}]""".encodeToByteArray())
    }

    private fun event(body: String) = """["EVENT","s",{"pubkey":"$pubkey",$body}]"""

    @Test
    fun numbersAsTheStringReaderReadsThem() {
        for (v in listOf("0", "1", "-1", "1710026692", "123456789012345678", "1234567890123456789", "99999999999999999999", "1.9", "1e3", "-0", "2.5E+2", "01", "1.", "-", "+1", "1e", "9.3e18", "1e400")) {
            assertSame(event(""""created_at":$v"""))
            assertSame(event(""""kind":$v"""))
        }
    }

    @Test
    fun tagValuesFieldsAndFrameShapes() {
        assertSame(event(""""tags":[]"""))
        assertSame(event(""""tags":[[]]"""))
        assertSame(event(""""tags":[["e",null,true,false,12,-1.5e2,"x"]]"""))
        assertSame(event(""""tags":[["p","a"],["p","b"],["p","c"],["p","d"]]"""))
        assertSame(event(""""tags":[["a"${",\"v\"".repeat(40)}]]"""))
        assertSame(event(""""tags":[${(1..2000).joinToString(",") { """["p","$it"]""" }}]"""))
        assertSame(event(""""tags":[["e",["nested"]]]"""))
        assertSame(event(""""tags":[["e",{"o":1}]]"""))
        assertSame(event(""""tags":[["",""," "]]"""))
        assertSame(event(""""extra":{"a":[1,2,{"b":null}],"c":"\"q\""},"more":[true,false,null,-3e-2],"content":"ok""""))
        assertSame(event(""""content":null"""))
        assertSame(event(""""content":5"""))
        assertSame(event(""""id":5"""))
        assertSame(event(""""pubKey":"x""""))
        assertSame("""["EVENT","s",{"content":"no pubkey"}]""")
        assertSame("""["EVENT","s",{}]""")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey"},"extra",[1,{"a":2}],null]""")
        assertSame("""["EVENT","s"]""")
        assertSame("""["EVENT"]""")
        assertSame("""["EVENT",5,{"pubkey":"$pubkey"}]""")
        assertSame("""["EVENT","s",[]]""")
        assertSame("""["EV\u0045NT","s",{"pubkey":"$pubkey"}]""")
        assertSame("""["EVENTS","s",{"pubkey":"$pubkey"}]""")
        assertSame("""[5,"s"]""")
        assertSame("""{"EVENT":1}""")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey","x":${"[".repeat(1200)}${"]".repeat(1200)}}]""")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey","x":${"[".repeat(990)}${"]".repeat(990)}}]""")
        assertSame("\t[\r\n\"EVENT\" ,\n\"s\"\t,\r{ \"pubkey\" :\"$pubkey\" , \"kind\" : 1 } ]\n")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey"}]trailing""")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey"},]""")
        assertSame("""["EVENT","s",{"pubkey":"$pubkey",}]""")
        assertSame("""["EVENT","s",{"p\u0075bkey":"$pubkey","\u0069d":"ab"}]""")
    }

    private val idA = "a".repeat(64)

    /** One event in every key order seen on the wire, each with the same fields. */
    private fun orders(
        id: String,
        content: String = "caf\u00e9 \\n x",
    ): List<String> {
        val f =
            mapOf(
                "id" to "\"$id\"",
                "pubkey" to "\"$pubkey\"",
                "created_at" to "1700000000",
                "kind" to "1",
                "tags" to "[[\"p\",\"$pubkey\"],[\"t\",\"x\",\"y\"]]",
                "content" to "\"$content\"",
                "sig" to "\"${"cd".repeat(64)}\"",
            )

        fun obj(keys: List<String>) = keys.joinToString(",", "{", "}") { "\"$it\":${f[it]}" }
        return listOf(
            obj(listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig")),
            obj(listOf("content", "created_at", "id", "kind", "pubkey", "sig", "tags")),
            obj(listOf("pubkey", "created_at", "kind", "tags", "content", "id", "sig")),
            obj(listOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig")),
        ).map { """["EVENT","sub",$it]""" }
    }

    @Test
    fun deferredFieldsReadTheSameInEveryKeyOrder() {
        val first = KiteReader.readMessage(orders(idA)[0]) as EventMessage
        for (frame in orders(idA) + orders(idA, "\\ud83d\\ude00 \\\"q\\\" \\ud83d")) {
            assertSame(frame)
            val asked = ArrayList<String>()
            val msg =
                KiteReader.readMessageUtf8(
                    frame.encodeToByteArray(),
                    0,
                    frame.encodeToByteArray().size,
                    KnownEventLookup {
                        asked.add(it)
                        null
                    },
                ) as EventMessage
            assertEquals(listOf(idA), asked, frame)
            assertEquals(describe(KiteReader.readMessage(frame)), describe(msg))
        }
        assertEquals("caf\u00e9 \n x", first.event.content)
    }

    @Test
    fun aKnownIdEndsTheReadInEveryKeyOrder() {
        val cached = KiteReader.readEvent(orders(idA)[0].substringAfter("\"sub\",").dropLast(1))
        for (frame in orders(idA)) {
            val msg = KiteReader.readMessageUtf8(frame.encodeToByteArray(), 0, frame.encodeToByteArray().size, KnownEventLookup { if (it == idA) cached else null })
            assertTrue(msg is EventMessage)
            assertTrue(msg.event === cached, "the known event, not a copy: $frame")
            assertEquals("sub", msg.subId)
        }
        // Spaced and with an escaped key, as some relays send them.
        val spaced = """[ "EVENT" , "s2" , { "content" : "x" , "\u0069d" : "$idA" , "kind" : 1 } ]"""
        val hit = KiteReader.readMessageUtf8(spaced.encodeToByteArray(), 0, spaced.encodeToByteArray().size, KnownEventLookup { cached }) as EventMessage
        assertTrue(hit.event === cached)
        assertEquals("s2", hit.subId)
    }

    @Test
    fun aHitStopsReadingSoWhatFollowsTheIdIsNotChecked() {
        val cached = KiteReader.readEvent(orders(idA)[0].substringAfter("\"sub\",").dropLast(1))
        // Broken after the id: a hit hands out the known event, as CachingEventDecoder's scan did;
        // a miss reads on and fails.
        val broken = """["EVENT","s",{"content":"x","id":"$idA","kind":"""
        val bytes = broken.encodeToByteArray()
        val msg = KiteReader.readMessageUtf8(bytes, 0, bytes.size, KnownEventLookup { cached }) as EventMessage
        assertTrue(msg.event === cached)
        assertTrue(runCatching { KiteReader.readMessageUtf8(bytes, 0, bytes.size, KnownEventLookup { null }) }.isFailure)
    }

    @Test
    fun theLastOccurrenceOfAFieldStillWins() {
        // A deferred field, then the same field read directly after the id, and the other way.
        assertSame("""["EVENT","s",{"content":"first","pubkey":"$pubkey","id":"$idA","content":"second"}]""")
        assertSame("""["EVENT","s",{"tags":[["a"]],"id":"$idA","pubkey":"$pubkey","tags":[["b"]]}]""")
        assertSame("""["EVENT","s",{"id":"$idA","content":"first","pubkey":"$pubkey","content":"second"}]""")
        assertSame("""["EVENT","s",{"content":"first","content":"second","pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"sig":"a","sig":"b","pubkey":"$pubkey"}]""")
        // Two ids: looked up by the first, read with the last.
        val idB = "b".repeat(64)
        val twoIds = """["EVENT","s",{"id":"$idA","pubkey":"$pubkey","id":"$idB"}]"""
        assertSame(twoIds)
        val asked = ArrayList<String>()
        val read =
            KiteReader.readMessageUtf8(
                twoIds.encodeToByteArray(),
                0,
                twoIds.encodeToByteArray().size,
                KnownEventLookup {
                    asked.add(it)
                    null
                },
            ) as EventMessage
        assertEquals(listOf(idA), asked)
        assertEquals(idB, read.event.id)
    }

    @Test
    fun deferredValuesStillFailWhereTheyShould() {
        // Skipped while deferred, these fail when read on a miss, as they do without a lookup.
        assertSame("""["EVENT","s",{"content":5,"pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"pubkey":5,"id":"$idA"}]""")
        assertSame("""["EVENT","s",{"tags":{"a":1},"pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"tags":[["a",["b"]]],"pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"sig":null,"pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"content":null,"pubkey":"$pubkey","id":"$idA"}]""")
        assertSame("""["EVENT","s",{"content":"\uZZZZ","pubkey":"$pubkey","id":"$idA"}]""")
    }

    @Test
    fun malformedJsonFailsBothWays() {
        assertSame("""["EVENT","s",{"content":"$eAcute""")
        assertSame("""["EVENT","s",{"content":"$eAcute\""")
        assertSame("""["EVENT","s",{"content":"\ud83d\""")
        assertSame("""["EVENT","s",{"content":"\uZZZZ","pubkey":"$pubkey"}]""")
        assertSame("""["EVENT","s",{"content":"$eAcute","pubkey":"$pubkey"}""")
    }

    @Test
    fun offsetAndLength() {
        val text = frame("caf\u00e9 \\n \uD83D\uDE00")
        val inner = text.encodeToByteArray()
        val padded = byteArrayOf(0x7B, 0xC3.toByte()) + inner + byteArrayOf(0x5D, 0xFF.toByte())
        assertEquals(describe(KiteReader.readMessage(text)), describe(KiteReader.readMessageUtf8(padded, 2, inner.size)))
        val event = """{"content":"$eAcute\n","pubkey":"$pubkey"}"""
        val e = KiteReader.readEventUtf8(byteArrayOf(0x20) + event.encodeToByteArray(), 1)
        assertEquals("\u00e9\n", e.content)
    }
}
