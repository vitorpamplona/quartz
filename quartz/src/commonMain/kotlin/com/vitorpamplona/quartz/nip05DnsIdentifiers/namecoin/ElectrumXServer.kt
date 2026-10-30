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

import kotlinx.serialization.Serializable

/**
 * Result of an ElectrumX name_show query.
 *
 * Maps to the JSON fields returned by Namecoin Core / Electrum-NMC:
 *   { "name": "d/example", "value": "{...}", "txid": "abc...", "height": 12345, ... }
 */
@Serializable
data class NameShowResult(
    val name: String,
    val value: String,
    val txid: String? = null,
    val height: Int? = null,
    val expiresIn: Int? = null,
)

/**
 * Represents a single ElectrumX server endpoint.
 *
 * Quartz ships no server list: which servers to query, and which certificates
 * to trust for each, is the app's choice, the same as for Nostr relays.
 */
data class ElectrumxServer(
    val host: String,
    val port: Int,
    val useSsl: Boolean = true,
    /**
     * If true and [pinnedCertsPem] is empty, use the legacy shared trust store
     * (Quartz's former built-in certificates, plus those added with
     * `ElectrumXClient.addPinnedCert`, plus the system CAs). Every certificate
     * in that store is accepted for every server that sets this flag.
     */
    @Deprecated("Put this server's certificate in pinnedCertsPem instead. Removed in 3.0.0.")
    val usePinnedTrustStore: Boolean = false,
    /**
     * PEM certificates to trust for this server only, on top of the system CAs.
     *
     * Namecoin ElectrumX servers often use self-signed certificates. Pin each
     * one on the server that serves it: a certificate pinned here is not
     * accepted for any other server. When this is empty, the server must
     * present a certificate the system CAs accept (or, for a `.onion` host,
     * Tor's own authentication stands in, see `ElectrumXClient`).
     */
    val pinnedCertsPem: List<String> = emptyList(),
) {
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    constructor(
        host: String,
        port: Int,
        useSsl: Boolean = true,
        usePinnedTrustStore: Boolean = false,
    ) : this(host, port, useSsl, usePinnedTrustStore, emptyList())

    @Suppress("DEPRECATION")
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    fun copy(
        host: String = this.host,
        port: Int = this.port,
        useSsl: Boolean = this.useSsl,
        usePinnedTrustStore: Boolean = this.usePinnedTrustStore,
    ) = ElectrumxServer(host, port, useSsl, usePinnedTrustStore, pinnedCertsPem)
}

/**
 * Specific exception types for Namecoin resolution failures.
 * Allows callers to distinguish "name doesn't exist" from "servers unreachable".
 */
sealed class NamecoinLookupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The name was queried successfully but does not exist on the blockchain. */
    class NameNotFound(
        val name: String,
    ) : NamecoinLookupException("Name not found: $name")

    /** The name has expired (>36000 blocks since last update). */
    class NameExpired(
        val name: String,
    ) : NamecoinLookupException("Name expired: $name")

    /** All ElectrumX servers were unreachable or returned errors. */
    class ServersUnreachable(
        val lastError: Throwable? = null,
    ) : NamecoinLookupException("All ElectrumX servers unreachable", lastError)
}

/**
 * Result of testing connectivity to a single ElectrumX server.
 */
data class ServerTestResult(
    val server: ElectrumxServer,
    val success: Boolean,
    val responseTimeMs: Long,
    val error: String? = null,
    val tlsVersion: String? = null,
    /** PEM-encoded server certificate, captured during test for TOFU pinning. */
    val serverCertPem: String? = null,
    /** SHA-256 fingerprint of the server certificate. */
    val certFingerprint: String? = null,
)

// The self-signed certificates of the servers in the deprecated lists below.
// They go with those lists in 3.0.0; until then, ElectrumXClient's legacy shared
// trust store (usePinnedTrustStore) also trusts them.
//
// To update: `echo | openssl s_client -connect HOST:PORT 2>/dev/null | openssl x509 -outform PEM`
// For .onion: `python3 -c "import socks,ssl,socket,base64; s=socks.socksocket(); s.set_proxy(socks.SOCKS5,'127.0.0.1',9050); s.connect(('HOST',PORT)); ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE; ss=ctx.wrap_socket(s); print(base64.encodebytes(ss.getpeercert(True)).decode())"`

/** nmc2.bitcoins.sk:57002 / 46.229.238.187:57002 — expires 2030-10-22. */
internal val NMC2_BITCOINS_SK_CERT_PEM =
    """
-----BEGIN CERTIFICATE-----
MIID+TCCAuGgAwIBAgIUdmJGukmfPvqmAYpTfuGcjRoYHJ8wDQYJKoZIhvcNAQEL
BQAwgYsxCzAJBgNVBAYTAlNLMREwDwYDVQQIDAhTbG92YWtpYTETMBEGA1UEBwwK
QnJhdGlzbGF2YTEUMBIGA1UECgwLYml0Y29pbnMuc2sxGTAXBgNVBAMMEG5tYzIu
Yml0Y29pbnMuc2sxIzAhBgkqhkiG9w0BCQEWFGRlYWZib3lAY2ljb2xpbmEub3Jn
MB4XDTIwMTAyNDE5MjQzOVoXDTMwMTAyMjE5MjQzOVowgYsxCzAJBgNVBAYTAlNL
MREwDwYDVQQIDAhTbG92YWtpYTETMBEGA1UEBwwKQnJhdGlzbGF2YTEUMBIGA1UE
CgwLYml0Y29pbnMuc2sxGTAXBgNVBAMMEG5tYzIuYml0Y29pbnMuc2sxIzAhBgkq
hkiG9w0BCQEWFGRlYWZib3lAY2ljb2xpbmEub3JnMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAzBUkZNDfaz7kc28l5tDKohJjekWmz1ynzfGx3ZLsqOZE
c+kNfcMaWU+zT/j0mV6pX6KSH7G9pPAku+8PRdKRq+d63wiJDEjGSaFztQWKW6L1
vTxgCK5gu+Eir3BkTagJObsrLKS+T6qH610/3+btGgoR3lunB5TzCgB/9oQanjDW
zjg2CwmxgR5Iw1Eqfenx7zkSK33FSXSF2SvbUs1Atj2oPU4DLivyrx0RaUmaPemn
cmcpnax+py4pQeB6dJWU1INhzXt3hTJRyoqsSGY3vCECIKIBIkh8GsYjAX4z+Y9y
6pJx0da2b88qPWdsoxaIMvrQiuWknDrSJwAyw2Yd8QIDAQABo1MwUTAdBgNVHQ4E
FgQUT2J83B2/9jxGGdFeWrxMohTzHNwwHwYDVR0jBBgwFoAUT2J83B2/9jxGGdFe
WrxMohTzHNwwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAsbxX
wN8tZaXOybImMZCQS7zfxmKl2IAcqu+R01KPfnIfrFqXPsGDDl3rYLkwh1O4/hYQ
NKNW9KTxoJxuBmAkm7EXQQh1XUUzajdEDqDBVRyvR0Z2MdMYnMSAiiMXMl2wUZnc
QXYftBo0HbtfsaJjImQdDjmlmRPSzE/RW6iUe+1cesKBC7e8nVf69Yu/fxO4m083
VWwAstlWJfk1GyU7jzVc8svealg/oIiDoOMe6CFSLx1BDv2FeHSpRdqd3fn+AC73
bK2N2smrHUOQnFijuiFw3WOrjERi0eMhjVNfVu9W9ZYa/Wd6SdIzV55LbG+NpmSf
5W7ix41hRvdT6cTAJA==
-----END CERTIFICATE-----
    """.trimIndent()

/**
 * relay.testls.bit:50012 / 23.158.233.10:50012 — expires 2036-04-30.
 *
 * Self-signed RSA-2048 cert (CN=relay.testls.bit) served by ElectrumX-NMC
 * alongside the Namecoin-anchored Nostr relay. Served on :50002 until
 * 2026-10-08, when the box's default 50001/50002 ports switched to the
 * LE-cert electrumx2 endpoint.
 *
 * SHA-256 (DER): BB:0B:35:E6:42:35:A7:94:E1:57:B6:9A:CD:72:DA:4C:CC:16:A4:D8:1D:0F:F6:B1:D8:F7:FD:FC:6C:CA:6C:BA
 */
internal val RELAY_TESTLS_BIT_CERT_PEM =
    """
-----BEGIN CERTIFICATE-----
MIIDFzCCAf+gAwIBAgIUAz+Ky5Lu2u1QchHKTwQIStWD8fQwDQYJKoZIhvcNAQEL
BQAwGzEZMBcGA1UEAwwQcmVsYXkudGVzdGxzLmJpdDAeFw0yNjA1MDMwNjEyNDBa
Fw0zNjA0MzAwNjEyNDBaMBsxGTAXBgNVBAMMEHJlbGF5LnRlc3Rscy5iaXQwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCngMim0wilCdclMyIIBRAeFBjE
HWA0l5n3ebCtyepYmyXUrbth2TDdyMHiUNVvE3f7RSAUFtE+Tjb9+xzmLf04qlbC
i3b2DV3xdVOZpRCI4kybmraYG6lRLQJ5I/N8NWgPsFgcy2mZF3q0yMVEkzGAKqUT
QGZd2eBs/OVicbCWKmgyhXlnqGeHIs4iKOqenHKSZ8QE5bhAKaMn+Q116QEdBg12
svGNoSZSlX8hDNUf5N5pOcsK8vj0Yb0ypJOd0J5eVpYS/KA0oMMwALnEs0H17hfO
IIaWqrbfaqmdR67uzUfci2EoiwQJrXSy7WkNiVX0ikN02VlUPCb2OaSABmwjAgMB
AAGjUzBRMB0GA1UdDgQWBBSgnHYFU93wufP131xk5w843VWhATAfBgNVHSMEGDAW
gBSgnHYFU93wufP131xk5w843VWhATAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
DQEBCwUAA4IBAQAiVC5RJj/Y3S5uNYraPmRLQrgPui9eGlWDh6LeZvjftACA3WiN
XabJtTmHxOFFwxmBtzGVCWKR6udETHyfqaq0me/XoYLjGstYTd14GfoV9Klx7Glh
gLOgqC3Do04o2xnXxhPh00c0jUggKdI05KLVAc4dLfU/XDrOfxBl4XHZY4lYz4CF
bn+n5Q1cqqcGqoJIHl2cgDdrxSMigIpsunjk8cJXO+hsteA/Pd1UUY5plvE9nbNv
hy4QgfNqjy36b0Nbm7Fc9Te9W6zgXjHM+q7KhuQvTfKh2sHbCJzRcy7Chgwqyrfq
NsK0JcBkyRPB+fXmEoE/Xmj/UnOD0fZCCanD
-----END CERTIFICATE-----
    """.trimIndent()

/** Well-known public Namecoin ElectrumX servers (clearnet), as of 2026-10-30. */
@Deprecated("Quartz no longer picks ElectrumX servers: keep your own list, with each server's pinnedCertsPem. Removed in 3.0.0.")
val DEFAULT_ELECTRUMX_SERVERS =
    listOf(
        // electrumx.testls.space — since 2026-10-02 this operator serves a publicly-
        // trusted Let's Encrypt cert (previously self-signed). The system trust
        // store is sufficient.
        ElectrumxServer("electrumx.testls.space", 50002, useSsl = true),
        ElectrumxServer("nmc2.bitcoins.sk", 57002, useSsl = true, pinnedCertsPem = listOf(NMC2_BITCOINS_SK_CERT_PEM)),
        ElectrumxServer("46.229.238.187", 57002, useSsl = true, pinnedCertsPem = listOf(NMC2_BITCOINS_SK_CERT_PEM)),
        // relay.testls.bit — second public Namecoin ElectrumX deployment, served
        // alongside the Namecoin-anchored Nostr relay at wss://relay.testls.bit/.
        // TLS endpoint pinned to a self-signed cert.
        // Since 2026-10-08 the self-signed deployment lives on port 50012; the
        // default 50001/50002 ports on this box now serve the LE-cert
        // electrumx2.testls.space endpoint below. Also exposed as
        // wss://relay.testls.bit/electrumx (browser-viable, future WS transport —
        // see N1 §"Browser / WebSocket transport" in mstrofnone/nips).
        ElectrumxServer("relay.testls.bit", 50012, useSsl = true, pinnedCertsPem = listOf(RELAY_TESTLS_BIT_CERT_PEM)),
        // Bare IP peer of relay.testls.bit — same operator, same cert, same box.
        // Listed as a separate entry (mirrors how 46.229.238.187 sits beside
        // nmc2.bitcoins.sk) so resolvers that have an unhealthy DNS path can
        // still reach the server. The pin is checked against the certificate
        // chain only, with no hostname verification, so no SNI is required.
        ElectrumxServer("23.158.233.10", 50012, useSsl = true, pinnedCertsPem = listOf(RELAY_TESTLS_BIT_CERT_PEM)),
        // electrumx2.testls.space — redundancy endpoint for the testls.space
        // operator, on the same box as relay.testls.bit (23.158.233.10) but
        // terminating TLS at nginx with a publicly-trusted Let's Encrypt cert
        // (CN=electrumx2.testls.space, issuer LE YE1) instead of the self-signed
        // relay.testls.bit cert (which moved to port 50012). The system trust
        // store is sufficient.
        //
        // Since 2026-10-08 this endpoint lives on the default ElectrumX ports:
        // 50001 (TCP), 50002 (SSL, what this client uses), 50003 (WS) and 50004
        // (WSS), matching electrumx.testls.space. WSS on 50004 serves
        // browser-based Nostr clients doing Namecoin NIP-05 lookups without a
        // backend proxy — browsers refuse WSS to self-signed certs, so the LE
        // cert on electrumx2 makes it a browser-viable public Namecoin
        // ElectrumX endpoint. Port 50014 remains as a WSS alias for previously
        // deployed clients.
        ElectrumxServer("electrumx2.testls.space", 50002, useSsl = true),
        // electrum.nmc.ethicnology.com was removed: NXDOMAIN since 2026-10-30.
    )

/** Tor-preferred server list: onion primary, clearnet fallback. */
@Deprecated("Quartz no longer picks ElectrumX servers: keep your own list, with each server's pinnedCertsPem. Removed in 3.0.0.")
val TOR_ELECTRUMX_SERVERS =
    listOf(
        ElectrumxServer(
            "i665jpwsq46zlsdbnj4axgzd3s56uzey5uhotsnxzsknzbn36jaddsid.onion",
            50002,
            useSsl = true,
        ),
        // relay.testls.bit — hidden service shared with the Nostr relay's onion.
        // Plaintext over Tor (port 50001) since the onion key already authenticates
        // the endpoint; TLS-on-Tor would only add round-trips with no security gain.
        ElectrumxServer(
            "6cbn4rskfdr647otej7gpqlmpqcmj723vg2eoeuu7ljbwu6cpdebozyd.onion",
            50001,
            useSsl = false,
        ),
        ElectrumxServer("electrumx.testls.space", 50002, useSsl = true),
        ElectrumxServer("nmc2.bitcoins.sk", 57002, useSsl = true, pinnedCertsPem = listOf(NMC2_BITCOINS_SK_CERT_PEM)),
        ElectrumxServer("relay.testls.bit", 50012, useSsl = true, pinnedCertsPem = listOf(RELAY_TESTLS_BIT_CERT_PEM)),
        // Bare IP peer (same operator/cert/box). See clearnet list above.
        ElectrumxServer("23.158.233.10", 50012, useSsl = true, pinnedCertsPem = listOf(RELAY_TESTLS_BIT_CERT_PEM)),
        // electrumx2.testls.space — LE-cert redundancy endpoint. See clearnet list above.
        ElectrumxServer("electrumx2.testls.space", 50002, useSsl = true),
    )
