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
package com.vitorpamplona.quartz.nip55AndroidSigner.kite

import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import com.vitorpamplona.quartz.nip01Core.kite.KiteWriter
import com.vitorpamplona.quartz.nip01Core.kite.forEachField
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission

/**
 * NIP-55's JSON: the permission list a client sends a signer app, and the results a signer
 * returns for a batch of requests.
 *
 * Read like Quartz's former Jackson deserializers (unknown fields skipped; `package`, `result`,
 * `event` and `id` only when they are strings; `rejected` only when it is a boolean), with one
 * fix: a permission's `"kind": null` reads as no kind, as it was written, instead of kind 0.
 */
object Nip55Kite {
    fun fromJsonToPermission(json: String): Permission = KiteReader.read(json) { readPermission() }

    fun fromJsonToPermissionArray(json: String): Array<Permission> = KiteReader.read(json) { readArray { readPermission() } }.toTypedArray()

    fun fromJsonToIntentResult(json: String): IntentResult = KiteReader.read(json) { readIntentResult() }

    fun fromJsonToIntentResultList(json: String): List<IntentResult> = KiteReader.read(json) { readArray { readIntentResult() } }

    /** `{"type":"sign_event","kind":1}`; `kind` is written as `null` when the permission has none. */
    fun toJson(permission: Permission): String = KiteWriter.write { permission(permission) }

    fun toJson(permissions: List<Permission>): String =
        KiteWriter.write {
            raw('[')
            permissions.forEachIndexed { index, permission ->
                if (index > 0) raw(',')
                permission(permission)
            }
            raw(']')
        }

    /** The result's set fields, in declaration order. */
    fun toJson(result: IntentResult): String =
        KiteWriter.write {
            objectStart()
            result.`package`?.let {
                field("package")
                string(it)
            }
            result.result?.let {
                field("result")
                string(it)
            }
            result.event?.let {
                field("event")
                string(it)
            }
            result.id?.let {
                field("id")
                string(it)
            }
            result.rejected?.let {
                field("rejected")
                bool(it)
            }
            objectEnd()
        }

    private fun KiteWriter.permission(permission: Permission) {
        objectStart()
        field("type")
        val code = permission.type?.code
        if (code != null) string(code) else raw("null")
        field("kind")
        val kind = permission.kind
        if (kind != null) number(kind) else raw("null")
        objectEnd()
    }

    private inline fun <T> KiteReader.readArray(item: KiteReader.() -> T): List<T> {
        if (peek() != '[') fail("expected an array")
        i++
        val out = ArrayList<T>()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            peek()
            out.add(item())
            val c = peek()
            i++
            if (c == ',') continue
            if (c == ']') return out
            i--
            fail("expected ',' or ']'")
        }
    }

    private fun KiteReader.readPermission(): Permission {
        var hasType = false
        var type: String? = null
        var kind: Int? = null
        forEachField { key ->
            when (key) {
                "type" -> {
                    hasType = true
                    type = asText()
                }

                "kind" -> {
                    kind = asIntOrNull()
                }

                else -> {
                    skipValue(1)
                }
            }
        }
        if (!hasType) fail("a permission without a type")
        return Permission(CommandType.parse(type ?: "null"), kind)
    }

    private fun KiteReader.readIntentResult(): IntentResult {
        var pkg: String? = null
        var result: String? = null
        var event: String? = null
        var id: String? = null
        var rejected: Boolean? = null
        forEachField { key ->
            when (key) {
                "package" -> pkg = stringOrSkip()?.intern()
                "result" -> result = stringOrSkip()
                "event" -> event = stringOrSkip()
                "id" -> id = stringOrSkip()?.intern()
                "rejected" -> rejected = booleanOrSkip()
                else -> skipValue(1)
            }
        }
        return IntentResult(`package` = pkg, result = result, event = event, id = id, rejected = rejected)
    }

    /** Jackson's `asText()`: a scalar's text, "" for a container. */
    private fun KiteReader.asText(): String =
        when (s[i]) {
            '"' -> {
                readString()
            }

            't' -> {
                literal("true", "true")
            }

            'f' -> {
                literal("false", "false")
            }

            'n' -> {
                literal("null", "null")
            }

            '{', '[' -> {
                skipValue(1)
                ""
            }

            else -> {
                numberText()
            }
        }

    /** Jackson's `asInt()` for a present field, except that `null` stays null. */
    private fun KiteReader.asIntOrNull(): Int? =
        when (s[i]) {
            'n' -> {
                literal("null", "")
                null
            }

            '"' -> {
                // NumberInput.parseAsInt: digits are an int (0 when out of range); anything else
                // is a double truncated, else 0.
                val text = readString().trim()
                val digits = text.removePrefix("-").removePrefix("+")
                if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
                    text.toIntOrNull() ?: 0
                } else {
                    text.toDoubleOrNull()?.toInt() ?: 0
                }
            }

            't' -> {
                literal("true", "")
                1
            }

            'f' -> {
                literal("false", "")
                0
            }

            '{', '[' -> {
                skipValue(1)
                0
            }

            else -> {
                // An integer keeps its low 32 bits, like (int) of Jackson's LongNode; a fraction
                // or exponent is truncated, like (int) of its DoubleNode.
                val text = numberText()
                text.toLongOrNull()?.toInt() ?: text.toDoubleOrNull()?.toInt() ?: 0
            }
        }

    private fun KiteReader.stringOrSkip(): String? {
        if (s[i] == '"') return readString()
        skipValue(1)
        return null
    }

    private fun KiteReader.booleanOrSkip(): Boolean? =
        when (s[i]) {
            't' -> {
                literal("true", "")
                true
            }

            'f' -> {
                literal("false", "")
                false
            }

            else -> {
                skipValue(1)
                null
            }
        }
}
