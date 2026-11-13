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
package com.vitorpamplona.quartz.routstr.node

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The content of a Routstr node announcement: `{"name": "...", "about": "..."}`.
 *
 * Read leniently. Empty or blank content, text that is not a JSON object, a field that is not a
 * string and a blank field all read as null; nothing here throws.
 */
@Immutable
data class RoutstrNodeContent(
    val name: String?,
    val about: String?,
) {
    companion object {
        const val NAME = "name"
        const val ABOUT = "about"

        val EMPTY = RoutstrNodeContent(null, null)

        fun parse(content: String): RoutstrNodeContent {
            val trimmed = content.trim()
            if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return EMPTY
            val json =
                try {
                    Json.parseToJsonElement(trimmed) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return EMPTY
            return RoutstrNodeContent(json.stringOrNull(NAME), json.stringOrNull(ABOUT))
        }

        /** The content Routstr writes; an absent field is left out, and both absent give `{}`. */
        fun assemble(
            name: String?,
            about: String?,
        ): String =
            buildJsonObject {
                name?.let { put(NAME, it) }
                about?.let { put(ABOUT, it) }
            }.toString()

        private fun JsonObject.stringOrNull(key: String) =
            (this[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.ifEmpty { null }
    }
}
