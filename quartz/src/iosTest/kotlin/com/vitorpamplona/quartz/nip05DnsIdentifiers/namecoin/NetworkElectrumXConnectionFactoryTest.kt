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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkElectrumXConnectionFactoryTest {
    private val factory = NetworkElectrumXConnectionFactory()

    @Test
    fun directCleartextConnectionExchangesJsonRpc() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = false)
            try {
                val port = fixture.start()
                val connection =
                    withTimeout(5_000) {
                        factory.connect(
                            ElectrumxServer("127.0.0.1", port, useSsl = false),
                            ElectrumXRoute.Direct,
                            emptyList(),
                        )
                    }
                val result = withTimeout(5_000) { connection.call(7, "server.version") }
                connection.close()

                assertEquals("ElectrumX 1.16", result.jsonArray[0].jsonPrimitive.content)
                assertEquals(1, fixture.acceptedCount)
                assertNull(fixture.protocolError)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun malformedResponseIsRejected() =
        runBlocking {
            assertTransportResponseFailure(LoopbackReply.MALFORMED, "Malformed")
        }

    @Test
    fun serverErrorIsRejected() =
        runBlocking {
            assertTransportResponseFailure(LoopbackReply.SERVER_ERROR, "fixture failure")
        }

    @Test
    fun socksCarriesUnresolvableTargetHostname() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = true)
            try {
                val proxyPort = fixture.start()
                val connection =
                    withTimeout(5_000) {
                        factory.connect(
                            ElectrumxServer("target.invalid", 50001, useSsl = false),
                            ElectrumXRoute.Socks5("127.0.0.1", proxyPort),
                            emptyList(),
                        )
                    }
                val result = withTimeout(5_000) { connection.call(9, "server.version") }
                connection.close()

                assertEquals(JsonPrimitive("1.4"), result.jsonArray[1])
                assertEquals("target.invalid", fixture.targetHost)
                assertEquals(50001, fixture.targetPort)
                assertNull(fixture.protocolError)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun failedSocksRouteDoesNotFallBackToDirectTarget() =
        runBlocking {
            val target = LoopbackElectrumXFixture(socks5 = false)
            val closedProxy = LoopbackElectrumXFixture(socks5 = true)
            try {
                val targetPort = target.start()
                val proxyPort = closedProxy.start()
                closedProxy.close()
                delay(100)

                val outcome =
                    runCatching {
                        withTimeout(3_000) {
                            factory.connect(
                                ElectrumxServer("127.0.0.1", targetPort, useSsl = false),
                                ElectrumXRoute.Socks5("127.0.0.1", proxyPort),
                                emptyList(),
                            )
                        }
                    }
                delay(100)
                outcome.getOrNull()?.close()
                assertTrue(
                    outcome.exceptionOrNull() is ElectrumXProtocolException,
                    "Unexpected outcome=$outcome, proxy accepts=${closedProxy.acceptedCount}, " +
                        "direct target accepts=${target.acceptedCount}",
                )
                assertEquals(0, closedProxy.acceptedCount)
                assertEquals(0, target.acceptedCount, "A closed required proxy must not fall back to the target")
            } finally {
                closedProxy.close()
                target.close()
            }
        }

    @Test
    fun unavailableRouteDoesNotTouchTarget() =
        runBlocking {
            val target = LoopbackElectrumXFixture(socks5 = false)
            try {
                val targetPort = target.start()
                assertFailsWith<NamecoinLookupException.ServersUnreachable> {
                    factory.connect(
                        ElectrumxServer("127.0.0.1", targetPort, useSsl = false),
                        ElectrumXRoute.Unavailable,
                        emptyList(),
                    )
                }
                assertEquals(0, target.acceptedCount)
            } finally {
                target.close()
            }
        }

    @Test
    fun rejectedTlsHandshakeCapturesCertificateForUserPinning() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = false, tlsIdentity = AppleTestIdentity.create())
            try {
                val port = fixture.start()
                val result =
                    withTimeout(5_000) {
                        ElectrumXClient(connectTimeoutMs = 3_000, readTimeoutMs = 3_000)
                            .testServer(ElectrumxServer("127.0.0.1", port, useSsl = true), testName = null)
                    }

                assertFalse(result.success)
                assertEquals(AppleTestIdentity.PEM, result.serverCertPem)
                assertEquals(AppleTestIdentity.FINGERPRINT, result.certFingerprint)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun matchingSelfSignedPinCompletesTlsAndRpc() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = false, tlsIdentity = AppleTestIdentity.create())
            try {
                val port = fixture.start()
                val server =
                    ElectrumxServer(
                        "localhost",
                        port,
                        useSsl = true,
                        pinnedCertsPem = listOf(AppleTestIdentity.PEM),
                    )
                val result =
                    withTimeout(5_000) {
                        ElectrumXClient(connectTimeoutMs = 3_000, readTimeoutMs = 3_000)
                            .testServer(server, testName = null)
                    }

                assertTrue(result.success, result.error)
                assertNotNull(result.tlsVersion)
                assertEquals(AppleTestIdentity.PEM, result.serverCertPem)
                assertEquals(AppleTestIdentity.FINGERPRINT, result.certFingerprint)
                assertNull(fixture.protocolError)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun pinnedIpLiteralUsesOnlyNarrowChainOnlyCompatibility() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = false, tlsIdentity = AppleTestIdentity.create())
            try {
                val port = fixture.start()
                val server =
                    ElectrumxServer(
                        "127.0.0.1",
                        port,
                        useSsl = true,
                        pinnedCertsPem = listOf(AppleTestIdentity.PEM),
                    )
                val result =
                    withTimeout(5_000) {
                        ElectrumXClient(connectTimeoutMs = 3_000, readTimeoutMs = 3_000)
                            .testServer(server, testName = null)
                    }

                assertTrue(result.success, result.error)
                assertEquals(AppleTestIdentity.FINGERPRINT, result.certFingerprint)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun matchingPinDoesNotBypassDnsHostnameValidation() =
        runBlocking {
            val fixture = LoopbackElectrumXFixture(socks5 = true, tlsIdentity = AppleTestIdentity.create())
            try {
                val proxyPort = fixture.start()
                val server =
                    ElectrumxServer(
                        "wrong.example",
                        50001,
                        useSsl = true,
                        pinnedCertsPem = listOf(AppleTestIdentity.PEM),
                    )
                val client =
                    ElectrumXClient(
                        connectTimeoutMs = 3_000,
                        readTimeoutMs = 3_000,
                        routeProvider = ElectrumXRouteProvider { ElectrumXRoute.Socks5("127.0.0.1", proxyPort) },
                    )
                val result = withTimeout(5_000) { client.testServer(server, testName = null) }

                assertFalse(result.success, result.error)
                assertEquals(
                    AppleTestIdentity.FINGERPRINT,
                    result.certFingerprint,
                    "error=${result.error}, proxyTarget=${fixture.targetHost}, proxyError=${fixture.protocolError}",
                )
                assertEquals("wrong.example", fixture.targetHost)
            } finally {
                fixture.close()
            }
        }

    private suspend fun assertTransportResponseFailure(
        reply: LoopbackReply,
        expectedMessage: String,
    ) {
        val fixture = LoopbackElectrumXFixture(socks5 = false, reply = reply)
        try {
            val port = fixture.start()
            val connection =
                withTimeout(5_000) {
                    factory.connect(
                        ElectrumxServer("127.0.0.1", port, useSsl = false),
                        ElectrumXRoute.Direct,
                        emptyList(),
                    )
                }
            val error =
                assertFailsWith<ElectrumXProtocolException> {
                    withTimeout(5_000) { connection.call(11, "server.version") }
                }
            connection.close()
            assertTrue(error.message?.contains(expectedMessage) == true)
            assertNull(fixture.protocolError)
        } finally {
            fixture.close()
        }
    }
}
