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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hedged-lookup tests against REAL sockets.
 *
 * Hedging only works if a losing attempt can actually be cancelled, and a
 * parked blocking socket read ignores coroutine cancellation — so these tests
 * use real TCP sockets, not delay()-based fakes. [StalledServer] accepts the
 * connection and then never answers, exactly like a wedged ElectrumX host.
 */
class HedgedElectrumXClientTest {
    /** Accepts the TCP connection, then never reads, writes, or closes. */
    private class StalledServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort

        /** Fires once the client connects. */
        val accepted = CountDownLatch(1)

        /** Fires once the client socket goes away (EOF or reset observed on read). */
        val clientGone = CountDownLatch(1)

        private val t =
            thread(start = true) {
                try {
                    val conn = serverSocket.accept()
                    accepted.countDown()
                    try {
                        // Parked until the client disappears: EOF (-1) or SocketException.
                        conn.getInputStream().read()
                    } catch (_: Exception) {
                        // SocketException is fine too — the client closed.
                    }
                    clientGone.countDown()
                    runCatching { conn.close() }
                } catch (_: Exception) {
                    // Server socket closed before a client connected.
                }
            }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { t.join(2_000) }
        }
    }

    /** Minimal plaintext ElectrumX server: answers the four RPCs connectAndQuery makes. */
    private class FakeElectrumXServer(
        private val identifier: String,
        private val value: String,
        private val historyEmpty: Boolean = false,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort

        private val t =
            thread(start = true) {
                try {
                    serverSocket.accept().use { conn ->
                        val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
                        val writer = PrintWriter(conn.getOutputStream(), true)
                        while (true) {
                            val line = reader.readLine() ?: break
                            val id = Regex("\"id\":(\\d+)").find(line)?.groupValues?.get(1) ?: "0"
                            val response =
                                when {
                                    line.contains("\"server.version\"") ->
                                        """{"jsonrpc":"2.0","result":["ElectrumX/1.16","1.4"],"id":$id}"""
                                    line.contains("\"blockchain.scripthash.get_history\"") ->
                                        if (historyEmpty) {
                                            """{"jsonrpc":"2.0","result":[],"id":$id}"""
                                        } else {
                                            """{"jsonrpc":"2.0","result":[{"tx_hash":"${"ab".repeat(32)}","height":800000}],"id":$id}"""
                                        }
                                    line.contains("\"blockchain.transaction.get\"") ->
                                        """{"jsonrpc":"2.0","result":{"vout":[{"scriptPubKey":{"hex":"${nameScriptHex(identifier, value)}"}}]},"id":$id}"""
                                    line.contains("\"blockchain.headers.subscribe\"") ->
                                        """{"jsonrpc":"2.0","result":{"height":800100},"id":$id}"""
                                    else ->
                                        """{"jsonrpc":"2.0","error":{"code":1,"message":"unknown"},"id":$id}"""
                                }
                            writer.println(response)
                        }
                    }
                } catch (_: Exception) {
                    // Server socket closed mid-test.
                }
            }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { t.join(2_000) }
        }

        companion object {
            /** OP_NAME_UPDATE <push(name)> <push(value)> OP_2DROP OP_DROP, hex-encoded. */
            fun nameScriptHex(
                name: String,
                value: String,
            ): String {
                fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

                fun push(data: ByteArray): String = "%02x".format(data.size) + data.hex()
                return "53" + push(name.toByteArray(Charsets.US_ASCII)) + push(value.toByteArray(Charsets.US_ASCII)) + "6d75"
            }
        }
    }

    private fun plaintextServer(port: Int) = ElectrumxServer("127.0.0.1", port, useSsl = false)

    @Test
    fun `hedged lookup returns healthy server answer while stalled loser is cancelled`() =
        runBlocking {
            StalledServer().use { stalled ->
                FakeElectrumXServer("d/test", "{}").use { healthy ->
                    val client = ElectrumXClient(connectTimeoutMs = 2_000, readTimeoutMs = 60_000)
                    val servers = listOf(plaintextServer(stalled.port), plaintextServer(healthy.port))

                    val start = System.currentTimeMillis()
                    val result =
                        withTimeoutOrNull(5_000) {
                            client.nameShowWithFallback("d/test", servers, hedgeDelayMs = 300)
                        }
                    val elapsed = System.currentTimeMillis() - start

                    assertNotNull("expected the healthy server's answer", result)
                    assertEquals("d/test", result!!.name)
                    assertEquals("{}", result.value)
                    assertTrue("lookup took ${elapsed}ms; the hedge should answer in ~300ms", elapsed < 4_000)
                    assertTrue(
                        "stalled attempt's socket was not closed after losing the race",
                        stalled.clientGone.await(2, TimeUnit.SECONDS),
                    )
                }
            }
        }

    @Test
    fun `cancellation unwinds a parked blocking read within the caller timeout`() =
        runBlocking {
            StalledServer().use { stalled ->
                val client = ElectrumXClient(connectTimeoutMs = 2_000, readTimeoutMs = 60_000)

                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(500) { client.nameShow("d/test", plaintextServer(stalled.port)) }
                val elapsed = System.currentTimeMillis() - start

                assertNull(result)
                assertTrue(
                    "withTimeoutOrNull returned after ${elapsed}ms; the 60s socket read should have been abandoned at 500ms",
                    elapsed < 2_000,
                )
                assertTrue(
                    "stalled server never saw the client disconnect — socket was not closed on cancellation",
                    stalled.clientGone.await(2, TimeUnit.SECONDS),
                )
            }
        }

    @Test
    fun `all servers stalled fails with ServersUnreachable bounded by socket timeouts`() {
        StalledServer().use { s1 ->
            StalledServer().use { s2 ->
                val client = ElectrumXClient(connectTimeoutMs = 1_000, readTimeoutMs = 800)
                val servers = listOf(plaintextServer(s1.port), plaintextServer(s2.port))

                val start = System.currentTimeMillis()
                assertThrows(NamecoinLookupException.ServersUnreachable::class.java) {
                    runBlocking { client.nameShowWithFallback("d/test", servers, hedgeDelayMs = 200) }
                }
                val elapsed = System.currentTimeMillis() - start
                assertTrue("took ${elapsed}ms; read timeouts should bound it near 1s", elapsed < 5_000)
            }
        }
    }

    @Test
    fun `NameNotFound from a fast server wins the race against a stalled one`() {
        StalledServer().use { stalled ->
            FakeElectrumXServer("d/test", "{}", historyEmpty = true).use { emptyHealthy ->
                val client = ElectrumXClient(connectTimeoutMs = 2_000, readTimeoutMs = 60_000)
                // Stalled first: the definitive NameNotFound must still win.
                val servers = listOf(plaintextServer(stalled.port), plaintextServer(emptyHealthy.port))

                assertThrows(NamecoinLookupException.NameNotFound::class.java) {
                    runBlocking { client.nameShowWithFallback("d/test", servers, hedgeDelayMs = 200) }
                }
            }
        }
    }
}
