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
package com.vitorpamplona.quartz.concord.cord04Roles

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * RFC 8259 JSON read the way Vector's serde reads Roles v2 Control content (CORD-04 §2, §8, §9,
 * CORD-03 §1), a port of Armada's `strictJson.ts`: no lenient forms (unquoted keys or strings,
 * single quotes, comments, trailing commas), no raw control character inside a string, no lone
 * surrogate (unless asked), at most 127 nested containers, and every object that **repeated** a key
 * remembered, so a reader can refuse it where serde's struct would ([Parsed.hadRepeatedKeys]). A
 * repeated key keeps its last value, as serde's `Value` map does.
 *
 * Numbers keep their source text ([JsonPrimitive.content]), so `1`, `1.0` and `1e0` stay apart, as
 * serde keeps a u64 apart from an f64.
 *
 * Legacy Control content (vsk 0–12) is still read by [ConcordJson]'s lenient decoder: only what
 * Roles v2 introduced reads strictly.
 */
internal object ConcordStrictJson {
    /** serde_json's recursion limit: entering a 128th nested array or object fails. */
    private const val MAX_DEPTH = 127

    /** A parsed document and the objects in it that repeated a key. */
    class Parsed(
        val value: JsonElement,
        private val repeated: List<JsonObject>,
    ) {
        /** Whether [element] is an object of this document that repeated a key. */
        fun hadRepeatedKeys(element: JsonElement?): Boolean = element is JsonObject && repeated.any { it === element }
    }

    /** Any JSON value, or null when the text is not strict JSON (or holds a lone surrogate, unless [allowLoneSurrogates]). */
    fun parse(
        text: String,
        allowLoneSurrogates: Boolean = false,
    ): Parsed? =
        try {
            val reader = Reader(text, allowLoneSurrogates)
            val value = reader.value(0)
            reader.ws()
            if (reader.pos != text.length) null else Parsed(value, reader.repeated)
        } catch (_: MalformedJson) {
            null
        }

    /** An object that repeats no top-level key (Armada `parseStrictObject`), or null. */
    fun parseObject(text: String): Parsed? {
        val parsed = parse(text) ?: return null
        val value = parsed.value as? JsonObject ?: return null
        return if (parsed.hadRepeatedKeys(value)) null else parsed
    }

    /** A control-flow signal out of the recursive descent; never escapes [parse]. */
    private class MalformedJson : Exception()

    private class Reader(
        val text: String,
        val allowLoneSurrogates: Boolean,
    ) {
        var pos = 0
        val repeated = ArrayList<JsonObject>(0)

        fun fail(): Nothing = throw MalformedJson()

        fun ws() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(depth: Int): JsonElement {
            ws()
            if (pos >= text.length) fail()
            return when (text[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> number()
            }
        }

        fun literal(
            word: String,
            v: JsonElement,
        ): JsonElement {
            if (!text.startsWith(word, pos)) fail()
            pos += word.length
            return v
        }

        fun obj(depth: Int): JsonElement {
            if (depth >= MAX_DEPTH) fail()
            pos++
            val out = LinkedHashMap<String, JsonElement>()
            var hadRepeat = false
            ws()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return JsonObject(out)
            }
            while (true) {
                ws()
                if (pos >= text.length || text[pos] != '"') fail()
                val key = string()
                ws()
                if (pos >= text.length || text[pos] != ':') fail()
                pos++
                val v = value(depth + 1)
                if (out.containsKey(key)) hadRepeat = true
                out[key] = v
                ws()
                if (pos >= text.length) fail()
                when (text[pos++]) {
                    '}' -> {
                        val o = JsonObject(out)
                        if (hadRepeat) repeated.add(o)
                        return o
                    }
                    ',' -> continue
                    else -> fail()
                }
            }
        }

        fun arr(depth: Int): JsonElement {
            if (depth >= MAX_DEPTH) fail()
            pos++
            val out = ArrayList<JsonElement>()
            ws()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JsonArray(out)
            }
            while (true) {
                out.add(value(depth + 1))
                ws()
                if (pos >= text.length) fail()
                when (text[pos++]) {
                    ']' -> return JsonArray(out)
                    ',' -> continue
                    else -> fail()
                }
            }
        }

        fun string(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail()
                val c = text[pos++]
                when {
                    c == '"' -> break
                    c == '\\' -> {
                        if (pos >= text.length) fail()
                        when (text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail()
                                var code = 0
                                for (i in 0 until 4) {
                                    code = code * 16 + hexDigit(text[pos + i])
                                }
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> fail()
                        }
                    }
                    c.code < 0x20 -> fail()
                    else -> sb.append(c)
                }
            }
            val s = sb.toString()
            if (!allowLoneSurrogates && hasLoneSurrogate(s)) fail()
            return s
        }

        /** An ASCII hex digit's value; `digitToInt` would also take other scripts' digits. */
        fun hexDigit(c: Char): Int =
            when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> fail()
            }

        @OptIn(ExperimentalSerializationApi::class)
        fun number(): JsonElement {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length) fail()
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (pos < text.length && text[pos] in '0'..'9') pos++
            } else {
                fail()
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail()
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail()
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return JsonUnquotedLiteral(text.substring(start, pos))
        }
    }

    /** Whether [s] holds a surrogate code unit that is not half of a valid pair. */
    fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }
}
