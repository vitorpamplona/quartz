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
package com.vitorpamplona.quartz.nip55AndroidSigner

import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * NIP-55's JSON: the permission list a client sends a signer app, and the results a signer
 * returns for a batch of requests. Signer apps are other people's software, so reads are
 * lenient the way Quartz's earlier Jackson reader was: unknown fields are skipped, `package`,
 * `result`, `event` and `id` count only when they are strings, `rejected` only when it is a
 * boolean, and a permission's `type` and `kind` are coerced from whatever scalar they hold.
 * A permission's `"kind": null` reads as no kind. Malformed JSON throws [IllegalArgumentException].
 */
object JsonMapperNip55 {
    fun fromJsonToPermission(json: String): Permission = permission(Json.parseToJsonElement(json))

    fun fromJsonToPermissionArray(json: String): Array<Permission> = array(Json.parseToJsonElement(json)).map(::permission).toTypedArray()

    fun fromJsonToIntentResult(json: String): IntentResult = intentResult(Json.parseToJsonElement(json))

    fun fromJsonToIntentResultList(json: String): List<IntentResult> = array(Json.parseToJsonElement(json)).map(::intentResult)

    /** `{"type":"sign_event","kind":1}`; `kind` is written as `null` when the permission has none. */
    fun toJson(permission: Permission): String = permissionElement(permission).toString()

    fun toJson(permissions: List<Permission>): String = JsonArray(permissions.map(::permissionElement)).toString()

    /** The result's set fields, in declaration order. */
    fun toJson(result: IntentResult): String =
        buildJsonObject {
            result.`package`?.let { put("package", it) }
            result.result?.let { put("result", it) }
            result.event?.let { put("event", it) }
            result.id?.let { put("id", it) }
            result.rejected?.let { put("rejected", it) }
        }.toString()

    private fun permissionElement(permission: Permission) =
        buildJsonObject {
            put("type", permission.type?.code)
            put("kind", permission.kind)
        }

    private fun array(element: JsonElement): JsonArray = element as? JsonArray ?: throw IllegalArgumentException("expected an array")

    private fun permission(element: JsonElement): Permission {
        val fields = element as? JsonObject ?: throw IllegalArgumentException("expected an object")
        val type = fields["type"] ?: throw IllegalArgumentException("a permission without a type")
        return Permission(CommandType.parse(asText(type)), fields["kind"]?.let(::asIntOrNull))
    }

    private fun intentResult(element: JsonElement): IntentResult {
        val fields = element as? JsonObject ?: throw IllegalArgumentException("expected an object")
        return IntentResult(
            `package` = stringOrNull(fields["package"])?.intern(),
            result = stringOrNull(fields["result"]),
            event = stringOrNull(fields["event"]),
            id = stringOrNull(fields["id"])?.intern(),
            rejected = (fields["rejected"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
        )
    }

    private fun stringOrNull(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A scalar's text (`null` for JSON null), "" for an object or array. */
    private fun asText(element: JsonElement): String =
        when (element) {
            is JsonNull -> "null"
            is JsonPrimitive -> element.content
            else -> ""
        }

    /**
     * A permission's kind from whatever it holds: null stays null; a number keeps the low 32
     * bits of an integer or truncates a fraction; a string of digits is that int (0 when out of
     * range), else a number truncated, else 0; true is 1; false, an object or an array is 0.
     */
    private fun asIntOrNull(element: JsonElement): Int? =
        when {
            element is JsonNull -> {
                null
            }

            element !is JsonPrimitive -> {
                0
            }

            element.isString -> {
                val text = element.content.trim()
                val digits = text.removePrefix("-").removePrefix("+")
                if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
                    text.toIntOrNull() ?: 0
                } else {
                    text.toDoubleOrNull()?.toInt() ?: 0
                }
            }

            element.booleanOrNull != null -> {
                if (element.booleanOrNull == true) 1 else 0
            }

            else -> {
                element.longOrNull?.toInt() ?: element.doubleOrNull?.toInt() ?: 0
            }
        }
}
