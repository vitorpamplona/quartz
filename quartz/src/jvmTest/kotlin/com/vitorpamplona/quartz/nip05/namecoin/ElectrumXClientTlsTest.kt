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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * TLS trust tests for [ElectrumXClient] against a local server that speaks just
 * enough of the Electrum protocol to answer `server.version`:
 *
 *  - a rejected handshake still captures the certificate, so the user can pin it;
 *  - a pinned self-signed certificate then connects;
 *  - a certificate chaining to a user-trusted CA connects with the CA trusted
 *    alone (the self-hosted/StartOS arrangement: TLS signed by a root CA the
 *    user deliberately trusts);
 *  - trusting an unrelated CA does not connect.
 */
class ElectrumXClientTlsTest {
    private var serverSocket: SSLServerSocket? = null

    @After
    fun tearDown() {
        runCatching { serverSocket?.close() }
    }

    // ── fixture server ──────────────────────────────────────────────────

    /** Starts a TLS server on an ephemeral port from the given PKCS12 key material. */
    private fun startServer(
        pkcs12Base64: String,
        password: String,
    ): Int {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(Base64.getDecoder().decode(pkcs12Base64)), password.toCharArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password.toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        val socket = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket = socket
        thread(start = true, isDaemon = true) {
            while (!socket.isClosed) {
                val conn =
                    try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                thread(start = true, isDaemon = true) {
                    try {
                        conn.soTimeout = READ_TIMEOUT_MS
                        val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
                        val writer = PrintWriter(conn.getOutputStream(), true)
                        var line = reader.readLine()
                        while (line != null) {
                            val id = ID_REGEX.find(line)?.groupValues?.get(1) ?: "1"
                            val result =
                                if (line.contains("server.version")) {
                                    "[\"ElectrumX 1.16.0\",\"1.4\"]"
                                } else {
                                    "[]"
                                }
                            writer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                            line = reader.readLine()
                        }
                    } catch (_: Exception) {
                        // Client aborted the handshake (untrusted-cert tests) or went away.
                    } finally {
                        runCatching { conn.close() }
                    }
                }
            }
        }
        return socket.localPort
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun pemToCert(pem: String): X509Certificate =
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate

    private fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    // ── tests ───────────────────────────────────────────────────────────

    @Test
    fun rejectedHandshakeStillCapturesTheCertificate() =
        runBlocking {
            val port = startServer(SELF_SIGNED_P12_BASE64, SELF_SIGNED_P12_PASSWORD)
            val client = ElectrumXClient()
            val result = client.testServer(ElectrumxServer("127.0.0.1", port, useSsl = true), testName = null)

            assertFalse(result.success)
            assertNotNull(result.error)
            // The pin flow needs the certificate even though validation failed:
            // it must come back from a capture-only second connection.
            assertNotNull(result.serverCertPem)
            val expected = sha256Fingerprint(pemToCert(SELF_SIGNED_PEM))
            assertEquals(expected, sha256Fingerprint(pemToCert(result.serverCertPem!!)))
            assertEquals(expected, result.certFingerprint)
            Unit
        }

    @Test
    fun pinnedSelfSignedCertificateConnects() =
        runBlocking {
            val port = startServer(SELF_SIGNED_P12_BASE64, SELF_SIGNED_P12_PASSWORD)
            val client = ElectrumXClient()
            val server =
                ElectrumxServer(
                    "127.0.0.1",
                    port,
                    useSsl = true,
                    pinnedCertsPem = listOf(SELF_SIGNED_PEM),
                )
            val result = client.testServer(server, testName = null)

            assertNull(result.error)
            assertTrue(result.success)
            assertNotNull(result.tlsVersion)
            assertEquals(sha256Fingerprint(pemToCert(SELF_SIGNED_PEM)), result.certFingerprint)
            Unit
        }

    @Test
    fun certificateChainingToTrustedCaConnects() =
        runBlocking {
            // The server presents only its leaf, signed by a CA nothing ships.
            // Trusting the CA certificate — what installing a CA into the user
            // store does — must be enough to connect.
            val port = startServer(LEAF_P12_BASE64, LEAF_P12_PASSWORD)
            val client = ElectrumXClient()
            val server =
                ElectrumxServer(
                    "127.0.0.1",
                    port,
                    useSsl = true,
                    pinnedCertsPem = listOf(TEST_CA_PEM),
                )
            val result = client.testServer(server, testName = null)

            assertNull(result.error)
            assertTrue(result.success)
            assertEquals(sha256Fingerprint(pemToCert(LEAF_PEM)), result.certFingerprint)
            Unit
        }

    @Test
    fun unrelatedTrustedCaDoesNotConnect() =
        runBlocking {
            // Trusting some other CA must not make a self-signed server valid.
            val port = startServer(SELF_SIGNED_P12_BASE64, SELF_SIGNED_P12_PASSWORD)
            val client = ElectrumXClient()
            val server =
                ElectrumxServer(
                    "127.0.0.1",
                    port,
                    useSsl = true,
                    pinnedCertsPem = listOf(TEST_CA_PEM),
                )
            val result = client.testServer(server, testName = null)

            assertFalse(result.success)
            assertNotNull(result.error)
            Unit
        }

    private companion object {
        const val SELF_SIGNED_P12_PASSWORD = "selfpass"
        const val LEAF_P12_PASSWORD = "leafpass"
        const val READ_TIMEOUT_MS = 10_000
        val ID_REGEX = "\"id\":(\\d+)".toRegex()

        /** Self-signed server certificate (CN=localhost, CA:TRUE), as ElectrumX serves. */
        val SELF_SIGNED_PEM =
            """
-----BEGIN CERTIFICATE-----
MIIC2jCCAcKgAwIBAgIJANGXEFrm+AMcMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNV
BAMMCWxvY2FsaG9zdDAeFw0yNjExMDkwMjA0MTNaFw0zNjExMDYwMjA0MTNaMBQx
EjAQBgNVBAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
ggEBANbM2a4mERbkR8gNZniNPdCn+eaPAYtGnICjYhy5Xl3NsBS0+UnTLZ4+iJ5H
d2SNFwbDJ7wu7dW9hQXTZggm9aqc0cfs9lgpUVOx8UFGZRSa5DeIHz7Ni/1m6pLr
2MwMVbg2g4c6rGvhO1j3pl/i2QHe4OPc4XFxOsBXl57hZQHNy8gqgc1qTKiFB6Uy
Bzr4ATCvFUvuaC1amWuyWQb+Tx7BlxTFQaELfMBl7OjYio9Zn0WJbIDHRioEM5Wm
hxM5JeeNFpx8jfmKbQyxss2y2oq2LE7dqZE/0yOKrGdXDfkh5z9PJuJ6YT/olbdA
dOnpaBmJTYorH3pb9faDna+JrIsCAwEAAaMvMC0wDwYDVR0TAQH/BAUwAwEB/zAa
BgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcNAQELBQADggEBAEYh
T1YRrzx3XOn2r0qsdkyvDvpsDEZgz6OqjM95X7C3Z/9wcMlLBr8h2OMOYnEZwR9r
AK0Qi1b4bBZqxiRRjJ5uYEjwswRksrrbzb7L0hyu3SwPgG3twaVoXVgXWGYIpvk6
9hvq3mGqg+ErUxGaa2yh24GwN+Y9qwNWOWGC/rMvA31ytfMV9VfSDthfs8u9QgfK
cKy5P2p6KYrkxeLhTnhUGUyQHw13wa02N3qnRsZhom8xF0iiR51KaN942BeHqDe1
dTChbZF8lVFFkmZleI2dziFAqjCBS8sH1bIUuJHFL3ELtfDfFSzsD010DabAkwa5
vBE7Q9/+6GUtnqgfD9s=
-----END CERTIFICATE-----
            """.trimIndent()

        /** Leaf certificate (CN=localhost) signed by [TEST_CA_PEM]; the server sends only this. */
        val LEAF_PEM =
            """
-----BEGIN CERTIFICATE-----
MIIC3DCCAcSgAwIBAgIJAPxvTsPLKn/OMA0GCSqGSIb3DQEBCwUAMBkxFzAVBgNV
BAMMDlF1YXJ0eiBUZXN0IENBMB4XDTI2MTEwOTAxNTc1NVoXDTM2MTEwNjAxNTc1
NVowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
MIIBCgKCAQEAyAhUka6WplUAo4sVZqTYrLXH2whoE0WCrviGS0BWI9+FMG8jt1XK
UxCT7UCB+xy4Ju+rqjyf4iWPnBZVEZCisOOc15h+vrs5lfnyu+RwXdMwvRQ2YZ06
xmJcf6guCv57Z33SWIGcQLyG5bP/HW8TG6pMFh/MbJGNt2fsC79nh0Rqz3oJZKzL
NwJr0U7e8TjmFtMJHbX5IlP/PnQ4lp2SHlnYZlHhngHoemcH53zibaxiWW0qd+XK
eoZTtC+WNkgX7rueJWKVuSntXzBio0On1GgS2Z2YUjon0iwzGqcCnFyLeCmbzLS4
/JNqt0fgYxZPy4FmaUwZLylk8xa6A7TZaQIDAQABoywwKjAMBgNVHRMBAf8EAjAA
MBoGA1UdEQQTMBGCCWxvY2FsaG9zdIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
m066yZXizoVoXWfcBFHVUdOJWKzrW8j8UqmwoVdmG3lQKXFdstKs2y8IVNZUdTin
DxtWbrUd4UNLMIABThsuTg7ptWsu7HjFclJ8ngnP/mpFO0znxQtQy0B2+RmHODZF
BFwsC29aCMH0ErsDwH1pJjR4dcIYo1HnKg7oL67Tr6PeCSvuSbq3C47lVdn9o/mF
g9zWghOQpCYQMiKB/IkAXzcjBe06Re7o0dntWF62IO+a4hDfY8QLrqzE2326wCAF
8cC+TgdMxZT6tjDXBGq/iRLz9k7R6jiNrABYQQGHy86EfE9nC03Ci2cgu5+VaQyp
JsvbdbrsU8EDVZlNuKNA3A==
-----END CERTIFICATE-----
            """.trimIndent()

        /** Test-only certificate authority that signs [LEAF_PEM]. */
        val TEST_CA_PEM =
            """
-----BEGIN CERTIFICATE-----
MIICyDCCAbCgAwIBAgIJAIr8EkkrKQY1MA0GCSqGSIb3DQEBCwUAMBkxFzAVBgNV
BAMMDlF1YXJ0eiBUZXN0IENBMB4XDTI2MTEwOTAxNTc1NVoXDTM2MTEwNjAxNTc1
NVowGTEXMBUGA1UEAwwOUXVhcnR6IFRlc3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUA
A4IBDwAwggEKAoIBAQCbyGQvdK0U2XORlGRyEGh6vrcP9j5bWBFHBqzAtqTTz2eN
BlcDVlGhdSb1CuV5W1zVRpUIk8gnLkXO3pyLALIfMIy/UwWCeAX0CAwMPZTB1Y2g
5WQXoXUy8Ysf4AH1O8Y9gK/hVXi2jiuMkbF9Hf+qIrXOkbuoebr73IQZwlaNMAHn
/+3G5Ts4sex5IuM9vYziywhdAWWdkjh0hBs3sUZBj4QrasHKwU6/bz05q7msifDM
o6jo/HDWpRuJvbNrV0H7K5SWtkMPpWf2ZdWZepnYHruscTfHX0LFneWhQun4rihG
0Pd17yxcfUnpl5l6QK+0xZLaT3nIjuxXpxnW2h8jAgMBAAGjEzARMA8GA1UdEwEB
/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAE6BEmmNAXX4bllQcpPSmj/aBHFD
HmMibdidPAiG7arkfIr3dSyf15LihiPrW3pbQJS47CftZVHJtx0L+xneWL5DfWao
291S5jYM3hBFlb1Sngv5fuC7PQMqTPOGiuTN0ysZXS00A7MC1FIkD7SbKaVotugs
vIMeFxDQNWy8N+5Ueo9eQsZGL3v8q2Twk4utXSXyOVCv/das6bFX6A02ABje8P7f
tV99IYbzrC6UuFx+u4PWB6zrQfvNwsA9nmWQHOZsPUpx8qoSAZ0aRGvHrIwXVchB
nQKHLyQJmx5YBN6bNJjZP7onWgB17ejZWfeJcy1RjHBejs9tBRWU8Rxlnxk=
-----END CERTIFICATE-----
            """.trimIndent()

        /** PKCS12 with the self-signed server key + certificate. */
        const val SELF_SIGNED_P12_BASE64 = "MIIJGQIBAzCCCN8GCSqGSIb3DQEHAaCCCNAEggjMMIIIyDCCA38GCSqGSIb3DQEHBqCCA3AwggNsAgEAMIIDZQYJKoZIhvcNAQcBMBwGCiqGSIb3DQEMAQYwDgQI7frjDzpiDggCAggAgIIDOJmDbNu8hgUuUmwil0Tmp7GEej+hqdilV38tfLfn/Oqw7e6oSGCUIqmaP/rDbqxaGhS3imLHGC1FEAYM6YAoIHXQk42c6XxX1hVQ+16fJbnF8rFU/EcDuR8La80H7VOewDTNf/+s84mEz3pKL6R1hKqfbJEhcMO2bkRl+15+B/rqFBSqErc45OwzzYAOojrLasGLs9RfHD0VhBm7anMH8nUK3n7qtY+NhDnnTeEWtLd1+cpGyb4JUN8tTzy45yrhIY2vTVUO8akLhUK4ZibkijGa5Qz2iPwyACWIU1wolhzRAnfFpmNnH124ICZ9CLZx6S+ct7TTEXBVD3KCt79+vczmWLQMZoJCpbnvf+V1Wcb2i7yJkHN0R5yBCUgBsiYmATSX1aKOsAwy383jUljqtdH8rS5IvM2wqHIxpmx1+XDutWBgT3U5g6cPf4hbiQl1YpI521PssZAbCd/YyN4j9+N1OwcYRmJbl/5Ki8K2D2IZjbYH3cUj9GfW06LyBEXEqH9/LjUWx2cuF6eZlX4iYG9AsTkTrHsoaGvAZzG/ierjPVI8tOQTRPoMPVZPdDdulJeEMsMMsru5DpUKivJXMj6oS0R3RIel+iaJVM6Su7PgkRsu08k9wTiAs5HlQQHglIfsCyjhk2f5FA3zx5OgJebw8Nzt7UHApgxgUaeKolQnu9xfRQAr/ShNokWdNcr8mKND6VJyHq6zPuWzh02l4xsg79hsRsMuBIgciJ6FcgNYuXN8AUR+7TfpLR7owTFCOlBw8LnHV6EmlBV74G/z0lTDm6jK14HkiiG87H1l/VqBquI8uIR1QLZbvLBX37bZyN4RxEE/W3TzpWR2hV7qPWT8rw7ObExF6/UdpiO+OpCHFKL7TaWHPWeVxdhbVB9ALhgkkY/zbD9yrBCHgwovar4yjeFnLTTK5m3ssnPEfdgQ5m3wX7zZL1VWmiQeB8gNgOpBE/kCmbPxPlLLjttrCSIzXw6VyQYTJf17DjsheEmaK6Hhx3e3ZUPmy6V+byrLOqhkD4M6s41wbCXQ5fN5JJ44BKGmVDGHZq4TSJPB+Ya36mkEurgtZl70arseHBAi49ODKKzPoKYuMIIFQQYJKoZIhvcNAQcBoIIFMgSCBS4wggUqMIIFJgYLKoZIhvcNAQwKAQKgggTuMIIE6jAcBgoqhkiG9w0BDAEDMA4ECJF3JschNdTYAgIIAASCBMhD0oPruO5TyUoOOJvRnegmEoMs3wD0jbDKAVQ4tB31Z6k20Dos6bRgfaxwbnOxlVuCUDmpavmKKCDV4hL+4Fs8lFdvMnb/ILEK5qchEefaFDIpIJyJCzhEOfgjeeP1XIWT5ZzWvZUSsSEi1jeJI5A7TzH+UgLvo9rTWVuzg1Ud8ErLMHX7L8AIhgifBflnE0053KPPPGfH8cuzZjZ7+ODTTU/YbzxYEjl8LQJVAZuCBlH+equ0PGZvoux/JANwQaOSyVGmTtEvo9j76tyeBZRQUkXHugUWU1tYD9/mxkYRUpdtGrElK546opTMkCsihTWIIaGfGkydHCP4JBw7wuaL86C5/LMS/Ay/YVj6zaiuNrwuITzCMf/SvKkOmnDJQB7CAUJOufiV7cBr8w1+8WSzFj6JHux2lOskkz5bYGzg/XEHpn0+3YQPUN56Z2DDcM5VR2t0l8zSTSCvQ18qU8mwcvd7MahIz8fmYUGkR7SyamKrSkRgJt/WohtUYEBbH0YVVPutd7yCaL/cB3eX/LI4w63OvuluIOlxKf0X/hGW+MmkuxuhFEAEJ0l5c+nr/eXM5Hchswch5b0i7RPo2tNDYPy75HdDix+UxSedxg+pRCNc3t9x5J6AVt17Vop5Z//7XuKMZ6naw1xqUsMJ3pd4yV8sRXEOtVrnleAzi97Wix8knTUJVBcmlpmKY/VSCkVlh+f+GUPOvuwApWnwlEfWdZFpIeepY1JOf/RLPNDU+Fk0qxOwaAl75NS34IDb58oUFxDmp+ShA0AzM6Zr+DQoo7rK/1dKaid+LQVP0cNvcjTb7kA/R7sTINBMAqtC8j1eGJKGxIFoxKhSbxqe/G5N2TmwLrh0kUmQILs0A4afAd1fjqlD8Z4jwIvhcSnvBhq/miy61EWDjtU9QQOSvSCvbsJesL7xCpWhzwklQ52gaFDqm4u9nPefpeoSwC9L7uywJ7gIF43evwdkNcq+YWZGS1J0FWlqIlV6WP0pgpJxvcgp3JfBOr7Rz/DO2UdSggLTamkcyHj+XLWZmoSRZV5UIMSU8Zhbu0q4J3izL3y9/e7KlZNc+/eIhvnD2VVklUwzYBz82V9/4CSdgeVcZS15BUB4kHdQqO86gZJU4WCr0DrXnSwQ7MGhWlL9S8jqAtOA6MXbcRMLp8TwZDLfBzhmMEflHJBqCzyV3lr0cB48qAf+KnwcXBm+UxZBzJlD4dAaoPYVwqlYm8F+ZNqeou6ankY5rVV0D8gui4p9+7pXNJAGkc+9fEslqPrP/qQaGpwgmOUkQvJyZTX8y0xHe8aqVnl/cb8wCXWgItFaorDVlPEQgiKTf7jxsPH6dUzzYRwcptxixQldePc7EL8+6ALN5zadljyVvjikRoJtdIxqZAyKuImeD1zcJ7S2dTSLFWhvkvpQgaW8tGsyIRC4WYXexMxxqOPU7YdEDNHSXOwO+lRmFg9fkwqxAJq2d8EJb1CZSNqmU8b7K/umzduKga4qGArAjIjWkULwfQsLoGJxeObthrc/ozLlOmdcWr2s4QUFl1mKjZdRNMH0nAS75ziFdLM5AP7Fbo7wSjcarKnO6eWfH95t2axChJLYhMMETnYFioLpehmxWQh34aOsBs9NQqiLhi7ntiAxJTAjBgkqhkiG9w0BCRUxFgQURiy0wPaZd+puEPKAOBaCgcGPY34wMTAhMAkGBSsOAwIaBQAEFIfJqB/WGSIr0nJn0VKDtP+ujbghBAgabxUMNpHYsgICCAA="

        /** PKCS12 with the leaf key + certificate (no CA in the served chain). */
        const val LEAF_P12_BASE64 = "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"
    }
}
