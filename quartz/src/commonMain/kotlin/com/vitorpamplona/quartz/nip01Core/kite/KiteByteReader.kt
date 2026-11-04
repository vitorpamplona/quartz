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
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.utils.EventFactory

/**
 * Reads an `["EVENT", subId, {event}]` frame straight from its UTF-8 bytes, so the frame never
 * becomes a `String`: only the event's own values are built. It reads what [KiteReader] reads
 * the way it reads it (same fields, same defaults, same one-char tag names), for the plain case
 * only. Anything else (another message type, an unknown key, a number that isn't plain digits,
 * a number or literal in a tag, malformed input) returns null, and the caller decodes the frame
 * as text and hands it to Kite, which accepts or rejects it with its own rules. So a frame
 * either reads here exactly as Kite would read it, or isn't read here at all.
 *
 * Bytes are safe to scan for `"`, `\` and the structural characters: UTF-8 never uses a byte
 * below 0x80 inside a multi-byte character, so each value is decoded on its own and gets the
 * characters (and replacement characters, for malformed UTF-8) that decoding the whole frame
 * would have given it.
 *
 * One parse at a time per reader; each thread keeps one ([threadKiteByteReader]).
 */
internal class KiteByteReader {
    private var b: ByteArray = EMPTY_BYTES
    private var n = 0
    private var i = 0
    private var inUse = false
    private var cb: CharArray? = null
    private var tagScratch: Array<String?>? = null
    private var tagsScratch: Array<Array<String>?>? = null

    private var fId: String? = null
    private var fPubKey: String? = null
    private var fCreatedAt = 0L
    private var fHasCreatedAt = false
    private var fKind = 0
    private var fHasKind = false
    private var fTags: TagArray? = null
    private var fContent: String? = null
    private var fSig: String? = null

    private fun readEventMessage(
        bytes: ByteArray,
        length: Int,
    ): EventMessage? {
        b = bytes
        n = length
        i = 0
        inUse = true
        var done = false
        try {
            val msg = readFrame()
            done = true
            return msg
        } catch (_: Fallback) {
            return null
        } finally {
            b = EMPTY_BYTES
            inUse = false
            if (!done) {
                tagsScratch?.fill(null)
                tagScratch?.fill(null)
            }
            clearFields()
            if ((tagsScratch?.size ?: 0) > MAX_KEPT_TAGS) tagsScratch = null
            if ((tagScratch?.size ?: 0) > MAX_KEPT_TAGS) tagScratch = null
            if ((cb?.size ?: 0) > MAX_KEPT_CHARS) cb = null
        }
    }

    private fun clearFields() {
        fId = null
        fPubKey = null
        fTags = null
        fContent = null
        fSig = null
    }

    private fun ws() {
        while (i < n) {
            val c = b[i]
            if (c == SPACE || c == NL || c == CR || c == TAB) i++ else return
        }
    }

    private fun expect(c: Byte) {
        ws()
        if (i >= n || b[i] != c) throw Fallback
        i++
    }

    private fun readFrame(): EventMessage {
        expect(LBRACKET)
        ws()
        if (i + EVENT_LABEL.size > n) throw Fallback
        for (k in EVENT_LABEL.indices) if (b[i + k] != EVENT_LABEL[k]) throw Fallback
        i += EVENT_LABEL.size
        expect(COMMA)
        ws()
        val subId = readString()
        expect(COMMA)
        ws()
        val event = readEvent()
        expect(RBRACKET)
        ws()
        if (i != n) throw Fallback
        return EventMessage(subId, event)
    }

    private fun readEvent(): Event {
        fHasCreatedAt = false
        fHasKind = false
        clearFields()
        expect(LBRACE)
        ws()
        if (i < n && b[i] == RBRACE) {
            i++
        } else {
            while (true) {
                ws()
                if (i >= n || b[i] != QUOTE) throw Fallback
                i++
                val field = readKey()
                expect(COLON)
                ws()
                if (i >= n) throw Fallback
                when (field) {
                    F_ID -> fId = readString()
                    F_PUBKEY -> fPubKey = readString()
                    F_SIG -> fSig = readString()
                    F_CREATED_AT -> {
                        fCreatedAt = plainDigits()
                        fHasCreatedAt = true
                    }
                    F_KIND -> {
                        val k = plainDigits()
                        if (k > Int.MAX_VALUE) throw Fallback
                        fKind = k.toInt()
                        fHasKind = true
                    }
                    F_TAGS -> fTags = readTags()
                    F_CONTENT -> fContent = if (b[i] == N_LOWER) nullLiteral() else readString()
                    else -> throw Fallback
                }
                ws()
                if (i >= n) throw Fallback
                val c = b[i++]
                if (c == COMMA) continue
                if (c == RBRACE) break
                throw Fallback
            }
        }
        val pubKey = fPubKey
        if (pubKey.isNullOrEmpty()) throw Fallback
        return EventFactory.create(
            fId ?: "",
            pubKey,
            if (fHasCreatedAt) fCreatedAt else 0L,
            if (fHasKind) fKind else 0,
            fTags ?: EMPTY_TAGS,
            fContent ?: "",
            fSig ?: "",
        )
    }

    private fun nullLiteral(): String? {
        if (i + 4 > n || b[i + 1] != U_LOWER || b[i + 2] != L_LOWER || b[i + 3] != L_LOWER) throw Fallback
        i += 4
        return null
    }

    /** A key (the cursor past its opening quote), matched like Kite: by length, first and last byte. */
    private fun readKey(): Int {
        val start = i
        var q = start
        while (true) {
            if (q >= n) throw Fallback
            val c = b[q]
            if (c == QUOTE) break
            if (c == BACKSLASH) throw Fallback
            q++
        }
        i = q + 1
        if (q == start) return F_OTHER
        val first = b[start].toInt().toChar()
        val last = b[q - 1].toInt().toChar()
        return when (q - start) {
            2 -> if (first == 'i' && last == 'd') F_ID else F_OTHER
            3 -> if (first == 's' && last == 'g') F_SIG else F_OTHER
            4 ->
                when {
                    first == 'k' && last == 'd' -> F_KIND
                    first == 't' && last == 's' -> F_TAGS
                    else -> F_OTHER
                }
            6 -> if (first == 'p' && last == 'y') F_PUBKEY else F_OTHER
            7 -> if (first == 'c' && last == 't') F_CONTENT else F_OTHER
            10 -> if (first == 'c' && last == 't') F_CREATED_AT else F_OTHER
            else -> F_OTHER
        }
    }

    /** Kite's plainDigits: a non-negative integer of up to 18 digits, no leading zero; anything else falls back. */
    private fun plainDigits(): Long {
        var k = i
        if (k >= n) throw Fallback
        val first = b[k]
        if (first == ZERO) {
            if (k + 1 < n && isNumberPart(b[k + 1])) throw Fallback
            i = k + 1
            return 0
        }
        if (first < ONE || first > NINE) throw Fallback
        var v = 0L
        val limit = minOf(n, k + 18)
        while (k < limit) {
            val c = b[k]
            if (c < ZERO || c > NINE) break
            v = v * 10 + (c - ZERO)
            k++
        }
        if (k < n && isNumberPart(b[k])) throw Fallback
        i = k
        return v
    }

    private fun isNumberPart(c: Byte) = (c in ZERO..NINE) || c == DOT || c == E_LOWER || c == E_UPPER

    /** A string value, the cursor on its opening quote. */
    private fun readString(): String {
        if (i >= n || b[i] != QUOTE) throw Fallback
        val start = i + 1
        var k = start
        while (true) {
            if (k >= n) throw Fallback
            val c = b[k]
            if (c == QUOTE) {
                i = k + 1
                return b.decodeToString(start, k)
            }
            if (c == BACKSLASH) return readEscaped(start, k)
            k++
        }
    }

    private fun readEscaped(
        start: Int,
        firstEscape: Int,
    ): String {
        var buf = cb ?: CharArray(256).also { cb = it }
        var len = 0
        var from = start
        var k = firstEscape
        while (true) {
            // [from, k) is a run without escapes; k is on a backslash or the closing quote.
            buf = room(buf, len + (k - from) + 1)
            len = appendRun(buf, len, from, k)
            if (b[k] == QUOTE) {
                i = k + 1
                return buf.concatToString(0, len)
            }
            if (k + 1 >= n) throw Fallback
            val c: Char =
                when (b[k + 1].toInt().toChar()) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (k + 6 > n) throw Fallback
                        val v = (hex(b[k + 2]) shl 12) or (hex(b[k + 3]) shl 8) or (hex(b[k + 4]) shl 4) or hex(b[k + 5])
                        k += 4
                        v.toChar()
                    }
                    else -> throw Fallback
                }
            buf[len++] = c
            k += 2
            from = k
            while (true) {
                if (k >= n) throw Fallback
                val d = b[k]
                if (d == QUOTE || d == BACKSLASH) break
                k++
            }
        }
    }

    /** Appends bytes [from, to) as chars: ASCII directly, anything else through the UTF-8 decoder. */
    private fun appendRun(
        buf: CharArray,
        len: Int,
        from: Int,
        to: Int,
    ): Int {
        var ascii = true
        for (k in from until to) {
            if (b[k] < 0) {
                ascii = false
                break
            }
        }
        if (ascii) {
            var l = len
            for (k in from until to) buf[l++] = b[k].toInt().toChar()
            return l
        }
        // UTF-8 never takes fewer chars than bytes would need room for: the run fits.
        val run = b.decodeToString(from, to)
        run.toCharArray(buf, len, 0, run.length)
        return len + run.length
    }

    private fun room(
        buf: CharArray,
        needed: Int,
    ): CharArray {
        if (needed <= buf.size) return buf
        return buf.copyOf(maxOf(needed, buf.size * 2)).also { cb = it }
    }

    private fun hex(c: Byte): Int =
        when (c) {
            in ZERO..NINE -> c - ZERO
            in A_LOWER..F_LOWER -> c - A_LOWER + 10
            in A_UPPER..F_UPPER -> c - A_UPPER + 10
            else -> throw Fallback
        }

    private fun readTags(): TagArray {
        if (b[i] != LBRACKET) throw Fallback
        i++
        ws()
        if (i < n && b[i] == RBRACKET) {
            i++
            return EMPTY_TAGS
        }
        var t0: Array<String>? = null
        var t1: Array<String>? = null
        var outer: Array<Array<String>?>? = null
        var count = 0
        while (true) {
            ws()
            val tag = readTag()
            when (count) {
                0 -> t0 = tag
                1 -> t1 = tag
                else -> {
                    var o = outer
                    if (o == null) {
                        o = tagsScratch ?: arrayOfNulls<Array<String>>(16).also { tagsScratch = it }
                        o[0] = t0
                        o[1] = t1
                    } else if (count == o.size) {
                        o = o.copyOf(count * 2)
                        tagsScratch = o
                    }
                    o[count] = tag
                    outer = o
                }
            }
            count++
            ws()
            if (i >= n) throw Fallback
            val c = b[i++]
            if (c == COMMA) continue
            if (c == RBRACKET) break
            throw Fallback
        }
        if (outer == null) return if (count == 1) arrayOf(t0!!) else arrayOf(t0!!, t1!!)
        @Suppress("UNCHECKED_CAST")
        val tags = outer.copyOf(count) as TagArray
        outer.fill(null, 0, count)
        return tags
    }

    private fun readTag(): Array<String> {
        if (i >= n || b[i] != LBRACKET) throw Fallback
        i++
        ws()
        if (i < n && b[i] == RBRACKET) {
            i++
            return EMPTY_TAG
        }
        var v0: String? = null
        var v1: String? = null
        var buf: Array<String?>? = null
        var count = 0
        while (true) {
            ws()
            // Strings only: Kite reads null, booleans and numbers in a tag too, by their text.
            val value = oneCharString() ?: readString()
            when (count) {
                0 -> v0 = value
                1 -> v1 = value
                else -> {
                    var a = buf
                    if (a == null) {
                        a = tagScratch ?: arrayOfNulls<String>(8).also { tagScratch = it }
                        a[0] = v0
                        a[1] = v1
                    } else if (count == a.size) {
                        a = a.copyOf(count * 2)
                        tagScratch = a
                    }
                    a[count] = value
                    buf = a
                }
            }
            count++
            ws()
            if (i >= n) throw Fallback
            val c = b[i++]
            if (c == COMMA) continue
            if (c == RBRACKET) break
            throw Fallback
        }
        if (buf == null) return if (count == 1) arrayOf(v0!!) else arrayOf(v0!!, v1!!)
        @Suppress("UNCHECKED_CAST")
        val tag = buf.copyOf(count) as Array<String>
        buf.fill(null, 0, count)
        return tag
    }

    /** A one-character ASCII string, as Kite's shared instance. */
    private fun oneCharString(): String? {
        if (i + 2 >= n || b[i] != QUOTE || b[i + 2] != QUOTE) return null
        val c = b[i + 1]
        if (c == QUOTE || c == BACKSLASH || c < 0) return null
        i += 3
        return KiteReader.sharedOneChar(c.toInt().toChar())
    }

    /** Thrown, preallocated, when the frame is left to Kite's text path. */
    private object Fallback : RuntimeException()

    companion object {
        private const val F_OTHER = 0
        private const val F_ID = 1
        private const val F_PUBKEY = 2
        private const val F_CREATED_AT = 3
        private const val F_KIND = 4
        private const val F_TAGS = 5
        private const val F_CONTENT = 6
        private const val F_SIG = 7

        private const val MAX_KEPT_CHARS = 16 * 1024
        private const val MAX_KEPT_TAGS = 1024

        private val EMPTY_BYTES = ByteArray(0)
        private val EMPTY_TAGS: TagArray = emptyArray()
        private val EMPTY_TAG = emptyArray<String>()
        private val EVENT_LABEL = "\"EVENT\"".encodeToByteArray()

        private const val SPACE = ' '.code.toByte()
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()
        private const val COMMA = ','.code.toByte()
        private const val COLON = ':'.code.toByte()
        private const val LBRACKET = '['.code.toByte()
        private const val RBRACKET = ']'.code.toByte()
        private const val LBRACE = '{'.code.toByte()
        private const val RBRACE = '}'.code.toByte()
        private const val ZERO = '0'.code.toByte()
        private const val ONE = '1'.code.toByte()
        private const val NINE = '9'.code.toByte()
        private const val DOT = '.'.code.toByte()
        private const val E_LOWER = 'e'.code.toByte()
        private const val E_UPPER = 'E'.code.toByte()
        private const val N_LOWER = 'n'.code.toByte()
        private const val U_LOWER = 'u'.code.toByte()
        private const val L_LOWER = 'l'.code.toByte()
        private const val A_LOWER = 'a'.code.toByte()
        private const val F_LOWER = 'f'.code.toByte()
        private const val A_UPPER = 'A'.code.toByte()
        private const val F_UPPER = 'F'.code.toByte()

        private fun acquire(): KiteByteReader {
            val local = threadKiteByteReader()
            return if (local == null || local.inUse) KiteByteReader() else local
        }

        /**
         * The EVENT frame in [bytes] up to [length], as Kite reads it from text, or null when the
         * frame is another message or something only Kite's text path reads.
         */
        fun readEventMessage(
            bytes: ByteArray,
            length: Int,
        ): EventMessage? = acquire().readEventMessage(bytes, length)
    }
}

/** This thread's reusable byte reader, or null where there is none. */
internal expect fun threadKiteByteReader(): KiteByteReader?
