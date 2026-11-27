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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ElectrumXProtocolTest {
    @Test
    fun buildsCanonicalNameIndexScriptAndElectrumHash() {
        assertContentEquals(
            byteArrayOf(0x53, 0x06, 0x64, 0x2f, 0x74, 0x65, 0x73, 0x74, 0x00, 0x6d, 0x75, 0x6a),
            ElectrumXProtocol.nameIndexScript("d/test"),
        )
        assertEquals(
            "f4480ac7b29ed9e356b7360a914a09719fc31850617277acef7b4f7c8f1c4765",
            ElectrumXProtocol.scriptHash("d/test"),
        )
    }

    @Test
    fun queryExtractsLatestNameValueAndExpiry() =
        runTest {
            val result = queryNamecoinName("d/test", FakeExchange())
            assertEquals("d/test", result?.name)
            assertEquals("""{"nip05":"alice@example.com"}""", result?.value)
            assertEquals(TX_HASH, result?.txid)
            assertEquals(800_000, result?.height)
            assertEquals(35_900, result?.expiresIn)
        }

    @Test
    fun emptyHistoryIsDefinitivelyNotFound() =
        runTest {
            assertFailsWith<NamecoinLookupException.NameNotFound> {
                queryNamecoinName("d/test", FakeExchange(history = JsonArray(emptyList())))
            }
        }

    @Test
    fun expiredHistoryIsDistinctFromNotFound() =
        runTest {
            assertFailsWith<NamecoinLookupException.NameExpired> {
                queryNamecoinName("d/test", FakeExchange(currentHeight = 836_000))
            }
        }

    @Test
    fun missingNameInLatestTransactionIsNotARecord() =
        runTest {
            val otherNameScript = "5307642f6f74686572027b7d6d75"
            assertNull(queryNamecoinName("d/test", FakeExchange(scriptHex = otherNameScript)))
        }

    @Test
    fun rejectsMalformedAndErrorResponses() {
        assertFailsWith<ElectrumXProtocolException> { ElectrumXProtocol.response("not-json", 1) }
        assertFailsWith<ElectrumXProtocolException> {
            ElectrumXProtocol.response("""{"jsonrpc":"2.0","id":2,"result":[]}""", 1)
        }
        assertFailsWith<ElectrumXProtocolException> {
            ElectrumXProtocol.response("""{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":"failed"}}""", 1)
        }
    }

    @Test
    fun decoderHandlesFragmentationCrLfAndMultipleFrames() {
        val decoder = ElectrumXLineDecoder(maxLineBytes = 32)
        val first = """{"id":1,"result":[]}"""
        val second = """{"id":2}"""
        decoder.append(first.encodeToByteArray().copyOfRange(0, 8))
        assertNull(decoder.nextLine())
        decoder.append((first.substring(8) + "\r\n" + second + "\n").encodeToByteArray())
        assertEquals(first, decoder.nextLine())
        assertEquals(second, decoder.nextLine())
        assertNull(decoder.nextLine())
    }

    @Test
    fun decoderRejectsOversizedCompleteAndUnfinishedLines() {
        assertFailsWith<ElectrumXProtocolException> {
            ElectrumXLineDecoder(maxLineBytes = 8).append("123456789\n".encodeToByteArray())
        }
        assertFailsWith<ElectrumXProtocolException> {
            ElectrumXLineDecoder(maxLineBytes = 8).append("123456789".encodeToByteArray())
        }
    }

    private class FakeExchange(
        private val history: JsonElement =
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("tx_hash", TX_HASH)
                        put("height", 800_000)
                    },
                ),
            ),
        private val currentHeight: Int = 800_100,
        private val scriptHex: String = NAME_SCRIPT,
    ) : ElectrumXExchange {
        override suspend fun call(
            requestId: Int,
            method: String,
            params: List<JsonElement>,
        ): JsonElement =
            when (method) {
                "server.version" -> JsonArray(listOf(JsonPrimitive("ElectrumX 1.16"), JsonPrimitive("1.4")))
                "blockchain.scripthash.get_history" -> history
                "blockchain.transaction.get" ->
                    buildJsonObject {
                        put(
                            "vout",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("scriptPubKey", buildJsonObject { put("hex", scriptHex) })
                                    },
                                ),
                            ),
                        )
                    }
                "blockchain.headers.subscribe" -> buildJsonObject { put("height", currentHeight) }
                else -> throw AssertionError("Unexpected method $method")
            }
    }

    private companion object {
        const val TX_HASH = "abababababababababababababababababababababababababababababababab"
        const val NAME_SCRIPT = "5306642f746573741d7b226e69703035223a22616c696365406578616d706c652e636f6d227d6d75"
    }
}
