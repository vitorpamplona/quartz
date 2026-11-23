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
package com.vitorpamplona.quartz.nip17Dm.interop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

/**
 * Writes the gift wraps Quartz's NIP-17 path produces for the vectors ([Nip17VectorFixture.ourCases])
 * to `$NIP17_VECTORS_DUMP`, for `quartz/tools/nip17-vector-gen/gen.sh` to open with nostr-tools.
 * Does nothing when the variable is unset.
 */
class Nip17OursDump {
    @Test
    fun dump() {
        val target = System.getenv("NIP17_VECTORS_DUMP")?.takeIf { it.isNotBlank() } ?: return
        val cases =
            runBlocking { Nip17VectorFixture.ourCases() }.map { c ->
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(c.name),
                        "recipient" to JsonPrimitive(c.recipient),
                        "rumor" to JsonObject(Json.parseToJsonElement(c.rumor.toJson()).jsonObject - "sig"),
                        "wrap" to Json.parseToJsonElement(c.wrap.toJson()),
                    ),
                )
            }
        val file = File(target)
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) error("could not create $dir")
        file.writeText(JsonObject(mapOf("cases" to JsonArray(cases))).toString())
    }
}
