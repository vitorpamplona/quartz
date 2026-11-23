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
package com.vitorpamplona.quartz.concord.appEvents

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

/**
 * Writes the rumors Quartz's builders produce for the Concord app-event vectors
 * ([ConcordAppEventFixture.ourCases]) to `$CONCORD_VECTORS_DUMP`, for
 * `quartz/tools/concord-vector-gen/gen.sh` to feed through Armada. Does nothing when the variable
 * is unset, so an ordinary test run writes no file.
 */
class ConcordAppEventOursDump {
    private fun json(event: Event): JsonElement = JsonObject(Json.parseToJsonElement(event.toJson()).jsonObject - "sig")

    @Test
    fun dump() {
        val target = System.getenv("CONCORD_VECTORS_DUMP")?.takeIf { it.isNotBlank() } ?: return
        val cases =
            ConcordAppEventFixture.ourCases().map { c ->
                val fields = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(c.name), "plane" to JsonPrimitive(c.plane))
                c.rumor?.let { fields["rumor"] = json(it) }
                c.rumors?.let { list -> fields["rumors"] = JsonArray(list.map(::json)) }
                JsonObject(fields)
            }
        val doc =
            JsonObject(
                mapOf(
                    "control" to JsonArray(ConcordAppEventFixture.ourControl().map(::json)),
                    "cases" to JsonArray(cases),
                ),
            )
        val file = File(target)
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) error("could not create $dir")
        file.writeText(doc.toString())
    }
}
