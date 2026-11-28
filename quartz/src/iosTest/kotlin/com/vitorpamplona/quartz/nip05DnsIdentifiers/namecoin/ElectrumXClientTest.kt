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

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElectrumXClientTest {
    private val server = ElectrumxServer("electrum.invalid", 50002)

    @Test
    fun lookupExtractsLatestNameValue() =
        runTest {
            val value = "{\"nostr\":\"npub1test\"}"
            val client = clientFor(successfulNameConnection("d/testls", value))

            val result = assertNotNull(client.nameShowWithFallback("d/testls", listOf(server)))

            assertEquals("d/testls", result.name)
            assertEquals(value, result.value)
            assertEquals("abcd", result.txid)
            assertEquals(35_990, result.expiresIn)
        }

    @Test
    fun emptyHistoryIsNameNotFound() =
        runTest {
            val connection =
                FakeConnection { _, method, _ ->
                    when (method) {
                        "server.version" -> versionResult()
                        "blockchain.scripthash.get_history" -> JsonArray(emptyList())
                        else -> error("Unexpected method: $method")
                    }
                }

            assertFailsWith<NamecoinLookupException.NameNotFound> {
                clientFor(connection).nameShowWithFallback("d/missing", listOf(server))
            }
        }

    @Test
    fun serverFailureBecomesUnreachable() =
        runTest {
            val connection =
                FakeConnection { _, _, _ ->
                    throw ElectrumXProtocolException("ElectrumX server error: fixture")
                }

            val error =
                assertFailsWith<NamecoinLookupException.ServersUnreachable> {
                    clientFor(connection).nameShowWithFallback("d/testls", listOf(server))
                }
            assertTrue(error.lastError is ElectrumXProtocolException)
        }

    @Test
    fun readTimeoutSettlesHedgedLookupAsUnreachable() =
        runTest {
            val connection = FakeConnection { _, _, _ -> awaitCancellation() }

            val error =
                assertFailsWith<NamecoinLookupException.ServersUnreachable> {
                    clientFor(connection, readTimeoutMs = 25).nameShowWithFallback("d/testls", listOf(server))
                }

            assertTrue(error.lastError is ElectrumXProtocolException)
            assertTrue(error.lastError?.message?.contains("timed out") == true)
        }

    @Test
    fun serverTestTimeoutReturnsFailure() =
        runTest {
            val connection = FakeConnection { _, _, _ -> awaitCancellation() }

            val result = clientFor(connection, readTimeoutMs = 25).testServer(server, testName = null)

            assertFalse(result.success)
            assertTrue(result.error?.contains("timed out") == true)
        }

    @Test
    fun callerCancellationIsNotConvertedToLookupFailure() =
        runTest {
            val connection = FakeConnection { _, _, _ -> awaitCancellation() }
            val client = clientFor(connection, readTimeoutMs = 60_000)

            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client.nameShowWithFallback("d/testls", listOf(server))
                }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
        }

    @Test
    fun unavailableRouteNeverOpensAConnection() =
        runTest {
            var connections = 0
            val client =
                ElectrumXClient(
                    connectTimeoutMs = 100,
                    readTimeoutMs = 100,
                    routeProvider = ElectrumXRouteProvider { ElectrumXRoute.Unavailable },
                    hedgeDelayMs = 0,
                    connectionFactory =
                        ElectrumXConnectionFactory { _, _, _ ->
                            connections++
                            error("Unavailable route must not reach the transport")
                        },
                )

            assertFailsWith<NamecoinLookupException.ServersUnreachable> {
                client.nameShowWithFallback("d/testls", listOf(server))
            }
            assertEquals(0, connections)
        }

    private fun clientFor(
        connection: ElectrumXConnection,
        readTimeoutMs: Long = 1_000,
    ) = ElectrumXClient(
        connectTimeoutMs = 100,
        readTimeoutMs = readTimeoutMs,
        routeProvider = ElectrumXRouteProvider { ElectrumXRoute.Direct },
        hedgeDelayMs = 0,
        connectionFactory = ElectrumXConnectionFactory { _, _, _ -> connection },
    )

    private class FakeConnection(
        private val handler: suspend (Int, String, List<JsonElement>) -> JsonElement,
    ) : ElectrumXConnection {
        override val tlsVersion: String? = null
        override val serverCertPem: String? = null
        override val certFingerprint: String? = null

        override suspend fun call(
            requestId: Int,
            method: String,
            params: List<JsonElement>,
        ): JsonElement = handler(requestId, method, params)

        override suspend fun close() = Unit
    }

    private companion object {
        fun versionResult() = JsonArray(listOf(JsonPrimitive("ElectrumX 1.16"), JsonPrimitive("1.4")))

        fun successfulNameConnection(
            identifier: String,
            value: String,
        ): FakeConnection {
            val name = identifier.encodeToByteArray()
            val record = value.encodeToByteArray()
            val script =
                byteArrayOf(0x53, name.size.toByte()) + name +
                    byteArrayOf(record.size.toByte()) + record + byteArrayOf(0x6d, 0x75, 0x6a)
            return FakeConnection { _, method, _ ->
                when (method) {
                    "server.version" -> versionResult()
                    "blockchain.scripthash.get_history" ->
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("tx_hash", "abcd")
                                    put("height", 100)
                                },
                            ),
                        )
                    "blockchain.transaction.get" ->
                        buildJsonObject {
                            put(
                                "vout",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put(
                                                "scriptPubKey",
                                                buildJsonObject { put("hex", script.toHexKey()) },
                                            )
                                        },
                                    ),
                                ),
                            )
                        }
                    "blockchain.headers.subscribe" -> buildJsonObject { put("height", 110) }
                    else -> error("Unexpected method: $method")
                }
            }
        }
    }
}
