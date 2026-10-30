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
package com.vitorpamplona.quartz.nip05.namecoin

import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

/**
 * Real TLS handshakes against a local ElectrumX stub serving a self-signed
 * certificate, to check that a pin only vouches for the server it is set on.
 */
class ElectrumXClientPinningTest {
    companion object {
        private lateinit var tmpDir: File
        private lateinit var servedPem: String
        private lateinit var otherPem: String
        private lateinit var stub: ServerSocket

        @JvmStatic
        @BeforeClass
        fun startStub() {
            tmpDir = createTempDirectory("electrumx-pins").toFile()
            val served = selfSigned("served")
            servedPem = pemOf(served)
            otherPem = pemOf(selfSigned("other"))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(served, PASSWORD.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            stub = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress())

            thread(isDaemon = true) {
                while (!stub.isClosed) {
                    val socket = runCatching { stub.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        // A client that rejects the certificate fails the handshake here.
                        runCatching {
                            socket.use { s ->
                                val reader = s.getInputStream().bufferedReader()
                                val writer = s.getOutputStream().bufferedWriter()
                                while (reader.readLine() != null) {
                                    writer.write("""{"jsonrpc":"2.0","id":1,"result":["ElectrumX 1.16.0","1.4"]}""")
                                    writer.newLine()
                                    writer.flush()
                                }
                            }
                        }
                    }
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun stopStub() {
            stub.close()
            if (!tmpDir.deleteRecursively()) println("Could not delete $tmpDir")
        }

        private const val PASSWORD = "changeit"

        private fun selfSigned(name: String): KeyStore {
            val file = File(tmpDir, "$name.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process =
                ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    name,
                    "-keyalg",
                    "EC",
                    "-groupname",
                    "secp256r1",
                    "-dname",
                    "CN=$name",
                    "-validity",
                    "2",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    file.path,
                    "-storepass",
                    PASSWORD,
                    "-keypass",
                    PASSWORD,
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        }

        private fun pemOf(keyStore: KeyStore): String {
            val cert = keyStore.getCertificate(keyStore.aliases().nextElement())
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
            return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
        }
    }

    private fun server(vararg pins: String) = ElectrumxServer("127.0.0.1", stub.localPort, useSsl = true, pinnedCertsPem = pins.toList())

    private fun ElectrumXClient.reaches(server: ElectrumxServer) = runBlocking { testServer(server, testName = null) }.success

    @Test
    fun `a server's own pin is trusted`() {
        assertTrue(ElectrumXClient().reaches(server(servedPem)))
    }

    @Test
    fun `an unpinned self-signed server is rejected`() {
        assertFalse(ElectrumXClient().reaches(server()))
    }

    @Test
    fun `a pin set on one server does not vouch for another`() {
        val client = ElectrumXClient()
        // The served certificate is pinned for this entry, and works...
        assertTrue(client.reaches(server(servedPem)))
        // ...but an entry that pins a different certificate still rejects it.
        assertFalse(client.reaches(server(otherPem)))
        assertFalse(client.reaches(server()))
    }

    @Test
    fun `any of a server's pins is trusted`() {
        assertTrue(ElectrumXClient().reaches(server(otherPem, servedPem)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated shared store still trusts certificates added to it`() {
        val client = ElectrumXClient()
        val legacy = ElectrumxServer("127.0.0.1", stub.localPort, useSsl = true, usePinnedTrustStore = true)
        assertFalse(client.reaches(legacy))
        client.addPinnedCert(servedPem)
        assertTrue(client.reaches(legacy))
    }
}
