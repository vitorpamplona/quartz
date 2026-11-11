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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import com.vitorpamplona.quartz.utils.Hex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Reads and writes an Observer site's `/editions.json`. Lenient on the way in: unknown fields
 * are ignored, an entry missing `day`, `path` or a well-formed `sha256` is dropped (not the
 * whole file), and a file this client can't read at all is null, so the caller rebuilds the
 * list from the manifest's `path` tags. Strict on the way out: newest first, no nulls written.
 */
object ObserverEditionsCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    fun encode(index: ObserverEditions): String = json.encodeToString(ObserverEditions.serializer(), index.copy(editions = ObserverSite.newestFirst(index.editions)))

    /** Null when [text] is not a v1 editions file this client understands. */
    fun decode(text: String): ObserverEditions? {
        val root =
            try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return null
        val version = (root["v"] as? JsonPrimitive)?.intOrNull ?: return null
        if (version != ObserverSite.FORMAT_VERSION) return null
        val title = (root["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val entries = root["editions"] as? JsonArray ?: return ObserverEditions(version, title, emptyList())
        val editions =
            entries.mapNotNull { element ->
                try {
                    json.decodeFromJsonElement<ObserverEdition>(element).takeIf(::isWellFormed)
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        return ObserverEditions(version, title, ObserverSite.newestFirst(editions))
    }

    private fun isWellFormed(edition: ObserverEdition) =
        ObserverSite.isDay(edition.day) &&
            edition.path == ObserverSite.dayPath(edition.day) &&
            edition.sha256.length == 64 &&
            edition.sha256 == edition.sha256.lowercase() &&
            Hex.isHex64(edition.sha256)
}
