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

import com.vitorpamplona.quartz.utils.Hex
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EXPERIMENT: can the id be checked by hashing the event's own text, instead of re-serializing
 * the parsed event? Only when the text is already NIP-01 canonical (as KiteWriter writes it).
 * Runs only with KITE_CORPUS set.
 */
class RawIdHashEval {
    /** Value spans [start, end) of the top-level keys of one event object, in a String. */
    private class Spans {
        var pk0 = -1
        var pk1 = -1
        var ca0 = -1
        var ca1 = -1
        var k0 = -1
        var k1 = -1
        var t0 = -1
        var t1 = -1
        var c0 = -1
        var c1 = -1
    }

    private fun locate(s: CharSequence): Spans? {
        val sp = Spans()
        var i = 0
        val n = s.length

        fun ws() {
            while (i < n && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun skipString() { // i on opening quote
            i++
            while (i < n && s[i] != '"') i += if (s[i] == '\\') 2 else 1
            i++
        }
        ws()
        if (s[i] != '{') return null
        i++
        while (true) {
            ws()
            if (s[i] == '}') break
            val ks = i + 1
            skipString()
            val key = s.subSequence(ks, i - 1).toString()
            ws()
            i++ // ':'
            ws()
            val v0 = i
            when (s[i]) {
                '"' -> skipString()
                '[', '{' -> {
                    var depth = 0
                    while (true) {
                        val c = s[i]
                        if (c == '"') {
                            skipString()
                            continue
                        }
                        if (c == '[' || c == '{') depth++
                        if (c == ']' || c == '}') depth--
                        i++
                        if (depth == 0) break
                    }
                }
                else -> while (i < n && s[i] != ',' && s[i] != '}' && s[i] != ' ') i++
            }
            when (key) {
                "pubkey" -> {
                    sp.pk0 = v0
                    sp.pk1 = i
                }
                "created_at" -> {
                    sp.ca0 = v0
                    sp.ca1 = i
                }
                "kind" -> {
                    sp.k0 = v0
                    sp.k1 = i
                }
                "tags" -> {
                    sp.t0 = v0
                    sp.t1 = i
                }
                "content" -> {
                    sp.c0 = v0
                    sp.c1 = i
                }
            }
            ws()
            if (s[i] == ',') i++
        }
        return if (sp.pk0 < 0 || sp.ca0 < 0 || sp.k0 < 0 || sp.t0 < 0 || sp.c0 < 0) null else sp
    }

    /** Builds `[0,pubkey,created_at,kind,tags,content]` from the raw spans, UTF-8, into [out]; false when the text is not canonical. */
    private class RawPreimage {
        var buf = ByteArray(64 * 1024)
        var pos = 0

        private fun put(b: Int) {
            if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[pos++] = b.toByte()
        }

        private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f'

        /** Copies s[a, b) as UTF-8, checking JSON string canonicity inside quotes; [inTags] also allows only [ ] , and strings outside them. */
        fun copy(
            s: CharSequence,
            a: Int,
            b: Int,
            inTags: Boolean,
        ): Boolean {
            if (pos + (b - a) * 3 > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + (b - a) * 3))
            var inString = !inTags
            var k = a
            while (k < b) {
                val c = s[k]
                if (!inString) {
                    if (c == '"') {
                        inString = true
                    } else if (c != '[' && c != ']' && c != ',') {
                        return false
                    }
                    put(c.code)
                    k++
                    continue
                }
                when {
                    c == '"' -> {
                        if (inTags) inString = false
                        put(c.code)
                        k++
                    }
                    c == '\\' -> {
                        val e = s[k + 1]
                        if (e == 'u') {
                            if (s[k + 2] != '0' || s[k + 3] != '0' || !hex(s[k + 4]) || !hex(s[k + 5])) return false
                            val v = (s[k + 4].digitToInt(16) shl 4) or s[k + 5].digitToInt(16)
                            if (v >= 0x20 || v == 8 || v == 9 || v == 10 || v == 12 || v == 13) return false
                            for (j in 0 until 6) put(s[k + j].code)
                            k += 6
                        } else {
                            if (e != '"' && e != '\\' && e != 'b' && e != 'f' && e != 'n' && e != 'r' && e != 't') return false
                            put('\\'.code)
                            put(e.code)
                            k += 2
                        }
                    }
                    c < ' ' -> return false
                    c.code < 0x80 -> {
                        buf[pos++] = c.code.toByte()
                        k++
                    }
                    c.code < 0x800 -> {
                        buf[pos++] = (0xC0 or (c.code shr 6)).toByte()
                        buf[pos++] = (0x80 or (c.code and 0x3F)).toByte()
                        k++
                    }
                    c.isHighSurrogate() && k + 1 < b && s[k + 1].isLowSurrogate() -> {
                        val cp = Character.toCodePoint(c, s[k + 1])
                        buf[pos++] = (0xF0 or (cp shr 18)).toByte()
                        buf[pos++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
                        k += 2
                    }
                    c.isSurrogate() -> return false
                    else -> {
                        buf[pos++] = (0xE0 or (c.code shr 12)).toByte()
                        buf[pos++] = (0x80 or ((c.code shr 6) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or (c.code and 0x3F)).toByte()
                        k++
                    }
                }
            }
            return !inTags || !inString
        }

        private fun digits(
            s: CharSequence,
            a: Int,
            b: Int,
        ): Boolean {
            if (b <= a || (s[a] == '0' && b - a > 1)) return false
            for (k in a until b) {
                if (s[k] !in '0'..'9') return false
                put(s[k].code)
            }
            return true
        }

        fun build(
            s: CharSequence,
            sp: Spans,
        ): Boolean {
            pos = 0
            put('['.code)
            put('0'.code)
            put(','.code)
            if (!copy(s, sp.pk0, sp.pk1, inTags = false)) return false
            put(','.code)
            if (!digits(s, sp.ca0, sp.ca1)) return false
            put(','.code)
            if (!digits(s, sp.k0, sp.k1)) return false
            put(','.code)
            if (!copy(s, sp.t0, sp.t1, inTags = true)) return false
            put(','.code)
            if (s[sp.c0] != '"' || !copy(s, sp.c0, sp.c1, inTags = false)) return false
            put(']'.code)
            return true
        }
    }

    /** The same check and hash over the frame's UTF-8 bytes: no copy, the digest reads the frame. */
    private fun rawBytesHash(
        b: ByteArray,
        sp: Spans,
        md: MessageDigest,
        out: ByteArray,
    ): Boolean {
        // Canonical check over bytes (escapes, controls, structure); valid UTF-8 is assumed checked by the parser.
        fun ok(
            a: Int,
            e: Int,
            inTags: Boolean,
        ): Boolean {
            var inString = !inTags
            var k = a
            while (k < e) {
                val c = b[k].toInt()
                if (!inString) {
                    if (c == '"'.code) {
                        inString = true
                    } else if (c != '['.code && c != ']'.code && c != ','.code) {
                        return false
                    }
                    k++
                    continue
                }
                if (c == '"'.code) {
                    if (inTags) inString = false
                    k++
                } else if (c == '\\'.code) {
                    val x = b[k + 1].toInt().toChar()
                    if (x == 'u') return false // rare: leave to the rewrite
                    if (x != '"' && x != '\\' && x != 'b' && x != 'f' && x != 'n' && x != 'r' && x != 't') return false
                    k += 2
                } else if (c in 0..0x1F) {
                    return false
                } else {
                    k++
                }
            }
            return true
        }
        if (!ok(sp.pk0, sp.pk1, false) || !ok(sp.t0, sp.t1, true) || !ok(sp.c0, sp.c1, false)) return false
        md.update(PREFIX)
        md.update(b, sp.pk0, sp.pk1 - sp.pk0)
        md.update(COMMA)
        md.update(b, sp.ca0, sp.ca1 - sp.ca0)
        md.update(COMMA)
        md.update(b, sp.k0, sp.k1 - sp.k0)
        md.update(COMMA)
        md.update(b, sp.t0, sp.t1 - sp.t0)
        md.update(COMMA)
        md.update(b, sp.c0, sp.c1 - sp.c0)
        md.update(CLOSE)
        md.digest(out, 0, 32)
        return true
    }

    private inline fun time(
        label: String,
        n: Int,
        rounds: Int,
        body: () -> Long,
    ) {
        var sink = 0L
        repeat(rounds / 2) { sink += body() }
        val t0 = System.nanoTime()
        repeat(rounds) { sink += body() }
        println("RAWID %-52s %6.3f us/event (%d)".format(label, (System.nanoTime() - t0) / 1000.0 / rounds / n, sink and 1))
    }

    @Test
    fun evaluate() {
        val path = System.getenv("KITE_CORPUS") ?: return
        val lines = File(path).readLines().filter { it.isNotBlank() }
        val events = lines.map { KiteReader.readEvent(it) }
        val spans = lines.map { locate(it)!! }
        val raw = RawPreimage()
        val md = MessageDigest.getInstance("SHA-256")
        val out = ByteArray(32)

        // Coverage and exactness: canonical raw text must give KiteWriter's preimage, byte for byte.
        var canonical = 0
        var idMatches = 0
        var nonCanonicalButValid = 0
        val reasons = HashMap<String, Int>()
        for (n in lines.indices) {
            val e = events[n]
            val expected = KiteWriter.idPreimageBytes(e.pubKey, e.createdAt, e.kind, e.tags, e.content)
            val validId = Hex.isEqual(e.id, MessageDigest.getInstance("SHA-256").digest(expected))
            if (raw.build(lines[n], spans[n])) {
                canonical++
                assertEquals(expected.toList(), raw.buf.copyOf(raw.pos).toList(), "line $n: canonical raw text must equal KiteWriter's preimage")
                if (validId) idMatches++
            } else {
                if (validId) nonCanonicalButValid++
                val l = lines[n]
                val why =
                    when {
                        Regex("\"tags\":\\[[^\\]]*\\s").containsMatchIn(l.substring(spans[n].t0, spans[n].t1)) -> "whitespace in tags"
                        l.substring(spans[n].c0, spans[n].c1).contains("\\u") -> "\\u escape in content"
                        l.substring(spans[n].t0, spans[n].t1).contains("\\u") -> "\\u escape in tags"
                        l.substring(spans[n].c0, spans[n].c1).contains("\\/") || l.substring(spans[n].t0, spans[n].t1).contains("\\/") -> "\\/ escape"
                        else -> "other"
                    }
                reasons[why] = (reasons[why] ?: 0) + 1
            }
        }
        println("RAWID ${lines.size} events: $canonical canonical as sent ($idMatches with a valid id), ${lines.size - canonical} not; $nonCanonicalButValid of those still have a valid id")
        println("RAWID not canonical, by reason: $reasons")

        val bytes = lines.map { it.encodeToByteArray() }
        val byteSpans = bytes.map { locate(it.decodeToString())!! } // ASCII-only lines keep the same offsets
        val asciiOnly = lines.indices.filter { lines[it].all { c -> c.code < 0x80 } }
        println("RAWID ${asciiOnly.size} ASCII-only events for the byte timing")

        for (pass in 1..2) {
            println("RAWID pass $pass")
            time("today: re-serialize parsed event + SHA-256 (idHashInto)", events.size, 20) {
                var s = 0L
                for (e in events) {
                    KiteWriter.idHashInto(e.pubKey, e.createdAt, e.kind, e.tags, e.content, out)
                    s += out[0]
                }
                s
            }
            time("raw String spans: check + UTF-8 copy + SHA-256", lines.size, 20) {
                var s = 0L
                for (n in lines.indices) {
                    if (raw.build(lines[n], spans[n])) {
                        md.update(raw.buf, 0, raw.pos)
                        md.digest(out, 0, 32)
                        s += out[0]
                    }
                }
                s
            }
            time("raw frame bytes: check + SHA-256 in place (ASCII set)", asciiOnly.size, 20) {
                var s = 0L
                for (n in asciiOnly) if (rawBytesHash(bytes[n], byteSpans[n], md, out)) s += out[0]
                s
            }
            time("today on the same ASCII set", asciiOnly.size, 20) {
                var s = 0L
                for (n in asciiOnly) {
                    val e = events[n]
                    KiteWriter.idHashInto(e.pubKey, e.createdAt, e.kind, e.tags, e.content, out)
                    s += out[0]
                }
                s
            }
        }
    }

    companion object {
        private val PREFIX = "[0,".encodeToByteArray()
        private val COMMA = ",".encodeToByteArray()
        private val CLOSE = "]".encodeToByteArray()
    }
}
