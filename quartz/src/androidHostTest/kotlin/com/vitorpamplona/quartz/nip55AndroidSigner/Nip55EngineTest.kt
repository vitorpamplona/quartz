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

import com.vitorpamplona.quartz.nip01Core.core.JsonEngine
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [JsonMapperNip55] follows [JsonEngine], and both engines agree on well-formed signer traffic. */
class Nip55EngineTest {
    @AfterTest
    fun reset() {
        JsonEngine.current = JsonEngine.DEFAULT
    }

    private fun <T> both(block: () -> T): Pair<T, T> {
        val jackson = block()
        JsonEngine.current = JsonEngine.HAND_WRITTEN
        try {
            return jackson to block()
        } finally {
            JsonEngine.current = JsonEngine.DEFAULT
        }
    }

    private fun describe(p: Permission) = "${p.type}|${p.kind}"

    private fun describe(r: IntentResult) = "${r.`package`}|${r.result}|${r.event}|${r.id}|${r.rejected}"

    @Test
    fun writesTheSame() {
        val perms = listOf(Permission(CommandType.SIGN_EVENT, 1), Permission(CommandType.NIP44_ENCRYPT), Permission(null))
        both { Permission(CommandType.SIGN_EVENT, 22242).toJson() }.let { assertEquals(it.first, it.second) }
        both { JsonMapperNip55.toJson(perms) }.let { assertEquals(it.first, it.second) }
        both { IntentResult("pkg", "r", "{\"e\":\"\\n\"}", "1", false).toJson() }.let { assertEquals(it.first, it.second) }
        both { IntentResult(result = "\u0001 \u2028 \uD83D\uDE00", rejected = true).toJson() }.let { assertEquals(it.first, it.second) }
    }

    @Test
    fun readsTheSame() {
        val result = """{"package":"com.signer","result":"sig","event":"{\"id\":\"x\"}","id":"1","rejected":false,"extra":[1]}"""
        both { describe(IntentResult.fromJson(result)) }.let { assertEquals(it.first, it.second) }
        both { IntentResult.fromJsonArray("[$result,{}]").joinToString { describe(it) } }.let { assertEquals(it.first, it.second) }
        val perm = """{"type":"sign_event","kind":7}"""
        both { describe(Permission.fromJson(perm)) }.let { assertEquals(it.first, it.second) }
        both { Permission.fromJsonArray("[$perm,{\"type\":\"nip44_encrypt\"}]").joinToString { describe(it) } }.let { assertEquals(it.first, it.second) }
    }

    @Test
    fun coercesKindsTheSame() {
        for (kind in listOf("7", "-3", "7.9", "1e3", "4294967297", "\"5.7\"", "\" 12 \"", "\"x\"", "\"4294967297\"", "\"+5\"", "\"-7\"", "\"1e2\"", "\"\"", "true", "false", "{}", "[1]")) {
            val json = """{"type":"sign_event","kind":$kind}"""
            both { Permission.fromJson(json).kind }.let { assertEquals(it.first, it.second, "kind $kind") }
        }
    }

    @Test
    fun followsTheEngine() {
        // The one documented difference: Jackson reads `"kind": null` as 0, the hand-written reader as no kind.
        val json = """{"type":"sign_event","kind":null}"""
        assertEquals(Pair<Int?, Int?>(0, null), both { Permission.fromJson(json).kind })
    }
}
