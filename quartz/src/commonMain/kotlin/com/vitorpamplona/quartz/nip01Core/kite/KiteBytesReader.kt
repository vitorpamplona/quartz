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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.KnownEventLookup
import com.vitorpamplona.quartz.utils.EventFactory

/**
 * [KiteReader]'s event grammar over UTF-8 bytes, for the frame a relay sends most:
 * `["EVENT", subId, {event}]`. Nothing is decoded but the values the event keeps, each from its
 * own byte range, so the frame never becomes a `String`.
 *
 * Quotes and backslashes are found with [indexOfByte], which scans 8 bytes at a time where the
 * platform can load them as one `long` (SWAR). Every token the grammar reads is ASCII, so it is
 * read as [KiteReader] reads it from a decoded frame; the two must accept, reject and build the
 * same, which `KiteReaderUtf8Test` and the corpus check in `KiteUtf8Benchmark` hold them to.
 *
 * A frame with any other label returns null from [readEventFrame], and the caller reads it with
 * [KiteReader] as before: those frames are rare, and their grammar stays in one place.
 */
internal class KiteBytesReader {
    private var b = EMPTY_BYTES

    /** Where the input starts, and its end (exclusive), in [b]. */
    private var start = 0
    private var n = 0

    /** The cursor, an index into [b]. */
    private var i = 0

    /** As in [KiteReader]: the next backslash at or after the cursor, [n] when none, -1 until asked. */
    private var nextEscape = -1
    private var frameClosed = false

    internal var inUse = false
    private var bb: ByteArray? = null
    private var tagScratch: Array<String?>? = null
    private var tagsScratch: Array<Array<String>?>? = null
    private var skipStack: BooleanArray? = null

    private var fId: String? = null
    private var fPubKey: String? = null
    private var fCreatedAt = 0L
    private var fHasCreatedAt = false
    private var fKind = 0
    private var fHasKind = false
    private var fTags: TagArray? = null
    private var fContent: String? = null
    private var fSig: String? = null

    private inline fun <T> parse(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        read: () -> T,
    ): T {
        b = bytes
        start = offset
        n = offset + length
        i = offset
        nextEscape = -1
        frameClosed = false
        inUse = true
        var done = false
        try {
            val result = read()
            done = true
            return result
        } finally {
            b = EMPTY_BYTES
            inUse = false
            knownHit = null
            if (!done) {
                tagsScratch?.fill(null)
                tagScratch?.fill(null)
                clearFields()
            }
            if ((tagsScratch?.size ?: 0) > MAX_KEPT_TAGS) tagsScratch = null
            if ((tagScratch?.size ?: 0) > MAX_KEPT_TAGS) tagScratch = null
            if ((bb?.size ?: 0) > MAX_KEPT_BYTES) bb = null
        }
    }

    /**
     * The EVENT frame in [bytes], or null when the frame is not an EVENT (read it with
     * [KiteReader]).
     *
     * With [known], the event's id is looked up the moment it is read. On a hit, that event is
     * the frame's, and nothing after the id is read. The fields that come before the id and would
     * allocate (`content`, `tags`, `pubkey`, `sig`) are only skipped until then, their start
     * noted, and built from there on a miss. A miss therefore reads the same as a parse without
     * [known], at the cost of scanning those fields twice. Strfry sorts keys, so only `content`
     * comes before the id there. A frame with two ids is looked up by its first; it is read, as
     * always, with its last.
     */
    fun readEventFrame(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        known: KnownEventLookup? = null,
    ): EventMessage? =
        parse(bytes, offset, length) {
            if (!openEventFrame()) return@parse null
            val subId = requiredString()
            if (!nextElement()) fail("missing element")
            if (b[i] != LBRACE) fail("expected an object")
            val event = readEventObject(2, known)
            // A known event ends the read: the rest of the frame is the copy we did not need.
            if (event === knownHit) {
                knownHit = null
                return@parse EventMessage(subId, event)
            }
            val msg = EventMessage(subId, event)
            while (nextElement()) skipValue(1)
            msg
        }

    /** The event [KnownEventLookup] answered for this frame's id, while [readEventFrame] hands it out. */
    private var knownHit: Event? = null

    /** One event object; see [KiteReader.readEventObject]. */
    fun readEvent(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Event = parse(bytes, offset, length) { readEventObject(1) }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at offset ${i - start}")

    /** The byte at [k] as the char it is in Latin-1: ASCII tokens compare as in [KiteReader]. */
    private fun c(k: Int): Char = (b[k].toInt() and 0xFF).toChar()

    private fun ws() {
        while (i < n) {
            val x = b[i]
            if (x == SPACE || x == LF || x == CR || x == TAB) i++ else return
        }
    }

    private fun expect(ch: Char) {
        ws()
        if (i >= n || c(i) != ch) fail("expected '$ch'")
        i++
    }

    private fun peek(): Char {
        ws()
        if (i >= n) fail("unexpected end of input")
        return c(i)
    }

    // ---- the frame

    /**
     * Opens the frame and reads its label: true for an EVENT, with the cursor past the label;
     * false for anything else, which [KiteReader] then reads from the start.
     */
    private fun openEventFrame(): Boolean {
        expect('[')
        if (peek() != '"') return false
        i++
        if (i + EVENT_LABEL_QUOTE.size > n) return false
        for (k in EVENT_LABEL_QUOTE.indices) if (b[i + k] != EVENT_LABEL_QUOTE[k]) return false
        i += EVENT_LABEL_QUOTE.size
        return true
    }

    private fun nextElement(): Boolean {
        if (frameClosed) return false
        val ch = peek()
        i++
        if (ch == ',') {
            peek()
            return true
        }
        if (ch == ']') {
            frameClosed = true
            return false
        }
        i--
        fail("expected ',' or ']'")
    }

    private fun requiredString(): String {
        if (!nextElement()) fail("missing element")
        return readString()
    }

    // ---- the event

    private fun readEventObject(
        base: Int,
        known: KnownEventLookup? = null,
    ): Event {
        readEventFields(base, known)
        knownHit?.let {
            clearFields()
            return it
        }
        val pubKey = fPubKey
        if (pubKey.isNullOrEmpty()) throw IllegalArgumentException("Event not found")
        val event =
            EventFactory.create<Event>(
                fId ?: "",
                pubKey,
                if (fHasCreatedAt) fCreatedAt else 0L,
                if (fHasKind) fKind else 0,
                fTags ?: EMPTY_TAGS,
                fContent ?: "",
                fSig ?: "",
            )
        clearFields()
        return event
    }

    private fun clearFields() {
        fId = null
        fPubKey = null
        fTags = null
        fContent = null
        fSig = null
    }

    private fun readEventFields(
        base: Int,
        known: KnownEventLookup?,
    ) {
        knownHit = null
        // Until the id is read (and only with [known]): where each deferred field's value starts.
        var defer = known != null
        var contentAt = -1
        var tagsAt = -1
        var pubKeyAt = -1
        var sigAt = -1
        fId = null
        fPubKey = null
        fHasCreatedAt = false
        fHasKind = false
        fTags = null
        fContent = null
        fSig = null

        expect('{')
        ws()
        if (i < n && b[i] == RBRACE) {
            i++
            return
        }
        while (true) {
            ws()
            if (i >= n || b[i] != QUOTE) fail("expected a field name")
            i++
            val field = readKey()
            expect(':')
            ws()
            if (i >= n) fail("unexpected end of input")
            // A deferred field is skipped and noted; read later, the last occurrence still wins,
            // so a field read directly forgets an earlier deferred one.
            when (field) {
                F_ID -> {
                    fId = readString()
                    if (defer) {
                        defer = false
                        val hit = known!!.find(fId!!)
                        if (hit != null) {
                            knownHit = hit
                            return
                        }
                    }
                }
                F_PUBKEY ->
                    if (defer) {
                        pubKeyAt = i
                        skipValue(base)
                    } else {
                        fPubKey = readString()
                        pubKeyAt = -1
                    }
                F_CREATED_AT -> {
                    fCreatedAt = readLong()
                    fHasCreatedAt = true
                }
                F_KIND -> {
                    fKind = readInt()
                    fHasKind = true
                }
                F_TAGS ->
                    if (defer) {
                        tagsAt = i
                        skipValue(base)
                    } else {
                        fTags = readTags()
                        tagsAt = -1
                    }
                F_CONTENT ->
                    if (defer) {
                        contentAt = i
                        skipValue(base)
                    } else {
                        fContent = stringOrNull()
                        contentAt = -1
                    }
                F_SIG ->
                    if (defer) {
                        sigAt = i
                        skipValue(base)
                    } else {
                        fSig = readString()
                        sigAt = -1
                    }
                else -> skipValue(base)
            }
            ws()
            if (i >= n) fail("unexpected end of input inside an object")
            val x = b[i++]
            if (x == COMMA) continue
            if (x == RBRACE) break
            fail("expected ',' or '}'")
        }
        if (contentAt < 0 && tagsAt < 0 && pubKeyAt < 0 && sigAt < 0) return
        // A miss: build the fields that came before the id, from where they start.
        val end = i
        if (contentAt >= 0) fContent = readDeferred(contentAt) { stringOrNull() }
        if (tagsAt >= 0) fTags = readDeferred(tagsAt) { readTags() }
        if (pubKeyAt >= 0) fPubKey = readDeferred(pubKeyAt) { readString() }
        if (sigAt >= 0) fSig = readDeferred(sigAt) { readString() }
        i = end
        // [nextEscape] only ever looks forward: let it look again from where reading resumes.
        nextEscape = -1
    }

    /** [read] with the cursor at [at], a value skipped earlier. */
    private inline fun <T> readDeferred(
        at: Int,
        read: () -> T,
    ): T {
        i = at
        // The escape found last lies past this value: look again from its start.
        nextEscape = -1
        return read()
    }

    private fun quoteAfter(from: Int): Int = indexOfByte(b, from, n, QUOTE)

    private fun escapeAtOrAfter(from: Int): Int {
        if (nextEscape < from) nextEscape = indexOfByte(b, from, n, BACKSLASH).let { if (it < 0) n else it }
        return nextEscape
    }

    private fun readKey(): Int {
        val s0 = i
        val q = quoteAfter(s0)
        if (q < 0) fail("unexpected end of input in a field name")
        if (escapeAtOrAfter(s0) < q) return fieldOf(readStringBody())
        i = q + 1
        return fieldAt(s0, q)
    }

    private fun fieldAt(
        s0: Int,
        q: Int,
    ): Int {
        val first = c(s0)
        val last = c(q - 1)
        return when (q - s0) {
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

    private fun fieldOf(key: String): Int =
        when (key) {
            "id" -> F_ID
            "pubkey" -> F_PUBKEY
            "created_at" -> F_CREATED_AT
            "kind" -> F_KIND
            "tags" -> F_TAGS
            "content" -> F_CONTENT
            "sig" -> F_SIG
            else -> F_OTHER
        }

    // ---- strings

    private fun readString(): String {
        if (b[i] != QUOTE) fail("expected a string")
        i++
        return readStringBody()
    }

    private fun readStringBody(): String {
        val s0 = i
        val q = quoteAfter(s0)
        if (q < 0) fail("unexpected end of input in a string")
        val esc0 = escapeAtOrAfter(s0)
        if (q < esc0) {
            i = q + 1
            return b.decodeToString(s0, q)
        }
        return readEscapedBody(s0, q, esc0)
    }

    /**
     * [readStringBody]'s escape path: the raw runs are copied as bytes and each escape written as
     * UTF-8 into [bb], then the value is decoded once. An escaped surrogate pair becomes its 4-byte
     * sequence; a lone escaped surrogate, which UTF-8 cannot hold, goes to [readEscapedChars], so it
     * stays lone as [KiteReader] keeps it.
     */
    private fun readEscapedBody(
        s0: Int,
        q: Int,
        esc0: Int,
    ): String {
        var buf = bb ?: ByteArray(256).also { bb = it }
        var len = 0
        var quote = q
        var esc = esc0
        var from = s0
        while (esc < quote) {
            buf = roomBytes(buf, len + (esc - from) + 4)
            b.copyInto(buf, len, from, esc)
            len += esc - from
            i = esc
            val ch = readEscape().code
            when {
                ch < 0x80 -> buf[len++] = ch.toByte()
                ch < 0x800 -> {
                    buf[len++] = (0xC0 or (ch shr 6)).toByte()
                    buf[len++] = (0x80 or (ch and 0x3F)).toByte()
                }
                ch in 0xD800..0xDBFF && i + 1 < n && b[i] == BACKSLASH && b[i + 1] == U_LOWER -> {
                    val low = readEscape().code
                    if (low !in 0xDC00..0xDFFF) return readEscapedChars(s0)
                    val cp = 0x10000 + ((ch - 0xD800) shl 10) + (low - 0xDC00)
                    buf[len++] = (0xF0 or (cp shr 18)).toByte()
                    buf[len++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    buf[len++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    buf[len++] = (0x80 or (cp and 0x3F)).toByte()
                }
                ch in 0xD800..0xDFFF -> return readEscapedChars(s0)
                else -> {
                    buf[len++] = (0xE0 or (ch shr 12)).toByte()
                    buf[len++] = (0x80 or ((ch shr 6) and 0x3F)).toByte()
                    buf[len++] = (0x80 or (ch and 0x3F)).toByte()
                }
            }
            from = i
            if (quote < i) quote = quoteAfter(i).also { if (it < 0) fail("unexpected end of input in a string") }
            esc = indexOfByte(b, i, n, BACKSLASH).let { if (it < 0) n else it }
        }
        buf = roomBytes(buf, len + (quote - from))
        b.copyInto(buf, len, from, quote)
        len += quote - from
        i = quote + 1
        nextEscape = esc
        return buf.decodeToString(0, len)
    }

    /** A value holding a lone escaped surrogate, built char by char so the surrogate stays lone. */
    private fun readEscapedChars(s0: Int): String {
        val sb = StringBuilder()
        i = s0
        var from = s0
        while (true) {
            if (i >= n) fail("unexpected end of input in a string")
            val x = b[i]
            if (x == QUOTE || x == BACKSLASH) {
                sb.append(b.decodeToString(from, i))
                if (x == QUOTE) break
                sb.append(readEscape())
                from = i
            } else {
                i++
            }
        }
        i++
        nextEscape = -1
        return sb.toString()
    }

    private fun roomBytes(
        buf: ByteArray,
        needed: Int,
    ): ByteArray {
        if (needed <= buf.size) return buf
        return buf.copyOf(maxOf(needed, buf.size * 2)).also { bb = it }
    }

    private fun readEscape(): Char {
        if (i + 1 >= n) fail("unexpected end of input in an escape")
        val ch =
            when (c(i + 1)) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 6 > n) fail("unexpected end of input in a \\u escape")
                    val v = (hex(c(i + 2)) shl 12) or (hex(c(i + 3)) shl 8) or (hex(c(i + 4)) shl 4) or hex(c(i + 5))
                    i += 4
                    v.toChar()
                }
                else -> fail("bad escape")
            }
        i += 2
        return ch
    }

    private fun hex(ch: Char): Int =
        when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> fail("bad hex digit in a \\u escape")
        }

    private fun stringOrNull(): String? {
        if (b[i] != N_LOWER) return readString()
        literal("null", "")
        return null
    }

    private fun literal(
        word: String,
        value: String,
    ): String {
        if (i + word.length > n) fail("expected $word")
        for (k in word.indices) if (c(i + k) != word[k]) fail("expected $word")
        i += word.length
        return value
    }

    // ---- numbers

    private fun scanNumber(): Boolean {
        if (i < n && b[i] == MINUS) i++
        if (i >= n) fail("expected a number")
        val first = c(i)
        if (first == '0') {
            i++
            if (i < n && c(i) in '0'..'9') fail("leading zeros are not allowed")
        } else if (first in '1'..'9') {
            i++
            while (i < n && c(i) in '0'..'9') i++
        } else {
            fail("expected a number")
        }
        var real = false
        if (i < n && c(i) == '.') {
            i++
            real = true
            if (i >= n || c(i) !in '0'..'9') fail("expected a digit after the decimal point")
            while (i < n && c(i) in '0'..'9') i++
        }
        if (i < n && (c(i) == 'e' || c(i) == 'E')) {
            i++
            real = true
            if (i < n && (c(i) == '+' || c(i) == '-')) i++
            if (i >= n || c(i) !in '0'..'9') fail("expected a digit in the exponent")
            while (i < n && c(i) in '0'..'9') i++
        }
        return real
    }

    private fun digitsValue(s0: Int): Long {
        val neg = b[s0] == MINUS
        var v = 0L
        for (k in (if (neg) s0 + 1 else s0) until i) v = v * 10 + (c(k) - '0')
        return if (neg) -v else v
    }

    private fun plainDigits(): Long {
        var k = i
        if (k >= n) return -1
        val first = c(k)
        if (first == '0') {
            val next = if (k + 1 < n) c(k + 1) else ' '
            if (next in '0'..'9' || next == '.' || next == 'e' || next == 'E') return -1
            i = k + 1
            return 0
        }
        if (first !in '1'..'9') return -1
        var v = 0L
        val limit = minOf(n, k + 18)
        while (k < limit) {
            val ch = c(k)
            if (ch !in '0'..'9') break
            v = v * 10 + (ch - '0')
            k++
        }
        if (k < n) {
            val ch = c(k)
            if (ch in '0'..'9' || ch == '.' || ch == 'e' || ch == 'E') return -1
        }
        i = k
        return v
    }

    private fun ascii(
        from: Int,
        to: Int,
    ): String = b.decodeToString(from, to)

    private fun readLong(): Long {
        val plain = plainDigits()
        if (plain >= 0) return plain
        val s0 = i
        if (!scanNumber()) {
            if (i - s0 <= 18) return digitsValue(s0)
            return ascii(s0, i).toLongOrNull() ?: fail("number out of range of long")
        }
        val d = ascii(s0, i).toDouble()
        if (!(d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble())) fail("number out of range of long")
        return d.toLong()
    }

    private fun readInt(): Int {
        val plain = plainDigits()
        if (plain >= 0) {
            if (plain > Int.MAX_VALUE) fail("number out of range of int")
            return plain.toInt()
        }
        val s0 = i
        if (!scanNumber()) {
            val v = if (i - s0 <= 18) digitsValue(s0) else ascii(s0, i).toLongOrNull() ?: fail("number out of range of int")
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("number out of range of int")
            return v.toInt()
        }
        val d = ascii(s0, i).toDouble()
        if (!(d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble())) fail("number out of range of int")
        return d.toInt()
    }

    private fun numberText(): String {
        val s0 = i
        scanNumber()
        return ascii(s0, i)
    }

    // ---- tags

    private fun readTags(): TagArray {
        if (b[i] != LBRACKET) fail("expected the tags array")
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
            if (i >= n) fail("unexpected end of input in tags")
            val x = b[i++]
            if (x == COMMA) continue
            if (x == RBRACKET) break
            fail("expected ',' or ']' in tags")
        }
        if (outer == null) return if (count == 1) arrayOf(t0!!) else arrayOf(t0!!, t1!!)
        @Suppress("UNCHECKED_CAST")
        val tags = outer.copyOf(count) as TagArray
        outer.fill(null, 0, count)
        return tags
    }

    private fun readTag(): Array<String> {
        if (i >= n || b[i] != LBRACKET) fail("expected a tag array")
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
            if (i >= n) fail("unexpected end of input in a tag")
            val value =
                when (c(i)) {
                    '"' -> oneCharString() ?: readString()
                    'n' -> literal("null", "")
                    't' -> literal("true", "true")
                    'f' -> literal("false", "false")
                    else -> numberText()
                }
            when (count) {
                0 -> v0 = value
                1 -> v1 = value
                else -> {
                    var bf = buf
                    if (bf == null) {
                        bf = tagScratch ?: arrayOfNulls<String>(8).also { tagScratch = it }
                        bf[0] = v0
                        bf[1] = v1
                    } else if (count == bf.size) {
                        bf = bf.copyOf(count * 2)
                        tagScratch = bf
                    }
                    bf[count] = value
                    buf = bf
                }
            }
            count++
            ws()
            if (i >= n) fail("unexpected end of input in a tag")
            val x = b[i++]
            if (x == COMMA) continue
            if (x == RBRACKET) break
            fail("expected ',' or ']' in a tag")
        }
        if (buf == null) return if (count == 1) arrayOf(v0!!) else arrayOf(v0!!, v1!!)
        @Suppress("UNCHECKED_CAST")
        val tag = buf.copyOf(count) as Array<String>
        buf.fill(null, 0, count)
        return tag
    }

    private fun oneCharString(): String? {
        if (i + 2 >= n || b[i + 2] != QUOTE) return null
        val x = b[i + 1].toInt()
        // A high byte is negative here: never a one-char ASCII string.
        if (x < 0 || x == QUOTE.toInt() || x == BACKSLASH.toInt()) return null
        i += 3
        return ONE_CHAR_STRINGS[x]
    }

    // ---- skipping

    private fun skipValue(base: Int) {
        var depth = 0
        var stack = skipStack ?: BooleanArray(16).also { skipStack = it }
        while (true) {
            var complete = true
            when (c(i)) {
                '"' -> {
                    i++
                    skipStringBody()
                }
                '{', '[' -> {
                    val isObject = b[i] == LBRACE
                    if (base + depth + 1 > MAX_DEPTH) fail("nested too deeply")
                    i++
                    ws()
                    if (i < n && b[i] == (if (isObject) RBRACE else RBRACKET)) {
                        i++
                    } else {
                        if (depth == stack.size) {
                            stack = stack.copyOf(depth * 2)
                            skipStack = stack
                        }
                        stack[depth++] = isObject
                        if (isObject) skipKeyAndColon()
                        ws()
                        if (i >= n) fail("unexpected end of input")
                        complete = false
                    }
                }
                't' -> literal("true", "")
                'f' -> literal("false", "")
                'n' -> literal("null", "")
                else -> scanNumber()
            }
            if (!complete) continue
            while (true) {
                if (depth == 0) return
                ws()
                if (i >= n) fail("unexpected end of input")
                val x = b[i++]
                val isObject = stack[depth - 1]
                if (x == COMMA) {
                    if (isObject) skipKeyAndColon()
                    ws()
                    if (i >= n) fail("unexpected end of input")
                    break
                }
                if (x == (if (isObject) RBRACE else RBRACKET)) {
                    depth--
                    continue
                }
                fail(if (isObject) "expected ',' or '}'" else "expected ',' or ']'")
            }
        }
    }

    private fun skipKeyAndColon() {
        ws()
        if (i >= n || b[i] != QUOTE) fail("expected a field name")
        i++
        skipStringBody()
        expect(':')
    }

    private fun skipStringBody() {
        var quote = quoteAfter(i)
        if (quote < 0) fail("unexpected end of input in a string")
        var esc = escapeAtOrAfter(i)
        while (esc < quote) {
            i = esc
            readEscape()
            if (quote < i) quote = quoteAfter(i).also { if (it < 0) fail("unexpected end of input in a string") }
            esc = indexOfByte(b, i, n, BACKSLASH).let { if (it < 0) n else it }
        }
        i = quote + 1
        nextEscape = esc
    }

    companion object {
        private const val F_OTHER = 0
        private const val F_ID = 1
        private const val F_PUBKEY = 2
        private const val F_CREATED_AT = 3
        private const val F_KIND = 4
        private const val F_TAGS = 5
        private const val F_CONTENT = 6
        private const val F_SIG = 7

        private const val MAX_DEPTH = 1000
        private const val MAX_KEPT_BYTES = 32 * 1024
        private const val MAX_KEPT_TAGS = 1024

        private const val QUOTE = '"'.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()
        private const val COMMA = ','.code.toByte()
        private const val LBRACE = '{'.code.toByte()
        private const val RBRACE = '}'.code.toByte()
        private const val LBRACKET = '['.code.toByte()
        private const val RBRACKET = ']'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val MINUS = '-'.code.toByte()
        private const val U_LOWER = 'u'.code.toByte()
        private const val N_LOWER = 'n'.code.toByte()

        private val EVENT_LABEL_QUOTE = "EVENT\"".encodeToByteArray()
        private val EMPTY_BYTES = ByteArray(0)
        private val EMPTY_TAGS: TagArray = emptyArray()
        private val EMPTY_TAG = emptyArray<String>()
        private val ONE_CHAR_STRINGS = Array(128) { it.toChar().toString() }
    }
}

/**
 * The index of the first [target] in [bytes] from [from] to [to] (exclusive), or -1. Platforms
 * that can load 8 bytes as one `long` test them all at once (SWAR); the rest loop.
 */
internal expect fun indexOfByte(
    bytes: ByteArray,
    from: Int,
    to: Int,
    target: Byte,
): Int
