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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
        // `1` or `1.0`: a JSON number, whatever the writer's float formatting.
        val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if (version != ObserverSite.FORMAT_VERSION.toDouble()) return null
        val title = root.string("title")
        val entries = root["editions"] as? JsonArray ?: return ObserverEditions(ObserverSite.FORMAT_VERSION, title, emptyList())
        val editions = entries.mapNotNull { (it as? JsonObject)?.let(::entry) }.distinctBy { it.day }
        return ObserverEditions(ObserverSite.FORMAT_VERSION, title, ObserverSite.newestFirst(editions))
    }

    /**
     * One entry, field by field: the required ones must be well formed or the entry is dropped;
     * an optional field of the wrong type is just absent, so one odd field never hides an edition.
     */
    private fun entry(obj: JsonObject): ObserverEdition? {
        val day = obj.string("day")?.takeIf(ObserverSite::isDay) ?: return null
        if (obj.string("path") != ObserverSite.dayPath(day)) return null
        val sha256 = obj.string("sha256")?.lowercase()?.takeIf(Hex::isHex64) ?: return null
        return ObserverEdition(
            day = day,
            path = ObserverSite.dayPath(day),
            sha256 = sha256,
            headline = obj.string("headline"),
            code = obj.string("code"),
            printedAt = obj.number("printedAt")?.longOrNull,
            posts = obj.number("posts")?.intOrNull,
            quotesVerified = obj.number("quotesVerified")?.intOrNull,
            model = obj.string("model"),
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }
}
