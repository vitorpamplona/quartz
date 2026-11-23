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
package com.vitorpamplona.quartz.buzz.vectors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * Writes the templates Quartz's builders produce for the Buzz vectors ([BuzzVectorFixture.ourCases])
 * to `$BUZZ_VECTORS_DUMP`, for `quartz/tools/buzz-vector-gen/gen.sh` to sign and send to a
 * buzz-relay. Does nothing when the variable is unset.
 */
class BuzzOursDump {
    @Test
    fun dump() {
        val target = System.getenv("BUZZ_VECTORS_DUMP")?.takeIf { it.isNotBlank() } ?: return
        val cases =
            BuzzVectorFixture.ourCases().map { c ->
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(c.name),
                        "who" to JsonPrimitive(c.who),
                        "kind" to JsonPrimitive(c.template.kind),
                        "tags" to JsonArray(c.template.tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }),
                        "content" to JsonPrimitive(c.template.content),
                    ),
                )
            }
        val doc =
            JsonObject(
                linkedMapOf(
                    "placeholders" to JsonObject(BuzzVectorFixture.PLACEHOLDERS.mapValues { JsonPrimitive(it.value) }),
                    "cases" to JsonArray(cases),
                ),
            )
        val file = File(target)
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) error("could not create $dir")
        file.writeText(doc.toString())
    }
}
