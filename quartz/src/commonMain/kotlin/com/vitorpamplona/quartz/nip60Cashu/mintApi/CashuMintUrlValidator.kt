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
package com.vitorpamplona.quartz.nip60Cashu.mintApi

import com.vitorpamplona.quartz.utils.Ipv4
import com.vitorpamplona.quartz.utils.Ipv6

/**
 * Thrown when a mint URL is refused before any request is issued. The message is
 * user-facing: it is surfaced verbatim in the "could not redeem" dialog.
 */
class MintUrlException(
    message: String,
) : RuntimeException(message)

/**
 * Gatekeeper for the `mintUrl` a [MintHttpClient] is about to talk to.
 *
 * A Cashu token carries its own mint URL (`token.mint`) and that URL is attacker
 * controlled: anybody can paste or post a token. Redeeming it makes the device
 * issue HTTP requests to whatever it says, which is an SSRF primitive against
 * anything the phone can reach (`http://127.0.0.1:<port>`, the home router at
 * `192.168.1.1`, `169.254.169.254` cloud metadata) plus an IP-address discloser
 * for whoever controls the URL.
 *
 * The rule:
 * - `https://` to a public host is allowed.
 * - `http://` is allowed **only** to a `.onion` host. Amethyst supports Tor and
 *   onion mints are legitimate; a blanket "https only" would break them. Onion
 *   names are self-authenticating and never resolve to a local address, so the
 *   private-range checks below don't apply to them.
 * - Any other scheme (`file:`, `ftp:`, `data:`, …) is refused.
 * - Loopback, RFC1918 private, link-local, CGNAT, unique-local, unspecified,
 *   multicast and broadcast addresses are refused, including via the usual
 *   spellings: IPv4-mapped/compatible IPv6 (`::ffff:127.0.0.1`), `inet_aton`
 *   decimal/octal/hex forms (`http://2130706433`, `http://0177.0.0.1`),
 *   `http://user@127.0.0.1`, and a trailing-dot hostname (`localhost.`).
 *
 * [userConfigured] switches the host checks off (the scheme check still runs).
 * Pass it only for a mint the user deliberately added — a mint already in their
 * NIP-60 wallet, or one they typed into the CLI. A self-hosted mint on the LAN
 * is a legitimate setup; the threat modelled here is a *pasted, untrusted* token
 * pointing at an internal address.
 *
 * NOTE: this is a pre-resolution check on the literal host, so it does not stop
 * DNS rebinding (a public name that resolves to 127.0.0.1). That is deliberately
 * out of scope — closing it needs a resolve-then-pin socket factory, not a URL
 * check.
 */
object CashuMintUrlValidator {
    /**
     * Validates [mintUrl] and returns the base URL to build request paths from
     * (trailing slashes stripped, matching the previous [MintHttpClient] behaviour).
     *
     * @throws MintUrlException with a user-facing reason when the URL is refused.
     */
    fun validatedBaseUrl(
        mintUrl: String,
        userConfigured: Boolean = false,
    ): String {
        val url = mintUrl.trim()
        if (url.isEmpty()) throw MintUrlException("The token does not name a mint.")

        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) throw MintUrlException("The mint address \"$url\" is not an https address.")

        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") {
            throw MintUrlException("The mint address uses the unsupported \"$scheme:\" scheme. Only https is allowed.")
        }

        val host = hostOf(url.substring(schemeEnd + 3))
        if (host.isEmpty()) throw MintUrlException("The mint address \"$url\" has no host.")

        val isOnion = host == "onion" || host.endsWith(".onion")

        if (scheme == "http" && !isOnion && !userConfigured) {
            throw MintUrlException("The mint address \"$url\" is not encrypted (http). Only https, or an .onion address over Tor, is allowed.")
        }

        if (!userConfigured && !isOnion && isPrivateHost(host)) {
            throw MintUrlException("The mint address \"$url\" points at a private or local address. Amethyst will not contact it.")
        }

        return url.trimEnd('/')
    }

    /**
     * Extracts the host from the part of the URL that follows `scheme://`:
     * drops the path/query/fragment, drops any `user:pass@` prefix (so
     * `http://mint.example.com@127.0.0.1/` is correctly read as `127.0.0.1`),
     * drops the port, unwraps a `[…]` IPv6 literal, and normalises case plus
     * the FQDN trailing dot.
     */
    private fun hostOf(afterScheme: String): String {
        var rest = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // userinfo goes up to the LAST '@' — an attacker can embed one in the password.
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)

        val host =
            if (rest.startsWith("[")) {
                rest.substringAfter('[').substringBefore(']')
            } else {
                rest.substringBefore(':')
            }

        return host.lowercase().trimEnd('.')
    }

    /**
     * True when [host] is a literal address inside a range we must never reach
     * from an untrusted token. Names that are not IP literals return false: the
     * check is pre-resolution (see the DNS-rebinding note on the object), except
     * for `localhost`, which is a name but always local.
     */
    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true

        if (host.contains(':')) {
            val v6 = parseIpv6(host) ?: return false
            return isPrivateIpv6(v6)
        }

        val v4 = parseIpv4(host) ?: return false
        return isPrivateIpv4(v4)
    }

    // ------------------------------------------------------------------
    // IPv4
    // ------------------------------------------------------------------

    /**
     * `inet_aton`-style parse: accepts 1..4 parts, each decimal, octal (`0…`) or
     * hex (`0x…`), because that is what a C resolver — and therefore a lot of the
     * stack below us — will happily accept. Returns the address as an unsigned
     * 32-bit value in a [Long], or null when [host] is not an IPv4 literal at all.
     *
     * The parse is [Ipv4.parseInetAton], the one the relay classifier
     * ([com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer.isLocalHost])
     * uses, so a mint and a relay spelled the same way get the same answer.
     */
    fun parseIpv4(host: String): Long? {
        val bytes = Ipv4.parseInetAton(host, 0, host.length) ?: return null
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    private fun isPrivateIpv4(addr: Long): Boolean {
        val a = ((addr shr 24) and 0xFF).toInt()
        val b = ((addr shr 16) and 0xFF).toInt()
        return when {
            a == 0 -> true // 0.0.0.0/8 — "this network", includes 0.0.0.0
            a == 10 -> true // RFC1918
            a == 127 -> true // loopback
            a == 169 && b == 254 -> true // link-local
            a == 172 && b in 16..31 -> true // RFC1918
            a == 192 && b == 168 -> true // RFC1918
            a == 100 && b in 64..127 -> true // CGNAT, RFC6598
            a == 192 && b == 0 -> true // 192.0.0.0/24 IETF protocol assignments
            a in 224..255 -> true // multicast + reserved + 255.255.255.255
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // IPv6
    // ------------------------------------------------------------------

    /**
     * Parses an IPv6 literal (with optional `::` run and optional trailing IPv4) into 16 bytes,
     * through the same [Ipv6.parse] the relay classifier uses. A zone id (`fe80::1%wlan0`)
     * doesn't change which range we're in.
     */
    fun parseIpv6(literal: String): ByteArray? = Ipv6.parse(literal.substringBefore('%'))

    private fun isPrivateIpv6(addr: ByteArray): Boolean {
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) carry an
        // IPv4 address; judge them by the IPv4 rules or ::ffff:127.0.0.1 walks in.
        val first10Zero = (0 until 10).all { addr[it].toInt() == 0 }
        if (first10Zero) {
            val ffff = (addr[10].toInt() and 0xFF) == 0xFF && (addr[11].toInt() and 0xFF) == 0xFF
            val compat = addr[10].toInt() == 0 && addr[11].toInt() == 0
            if (ffff || compat) {
                val v4 =
                    ((addr[12].toLong() and 0xFF) shl 24) or
                        ((addr[13].toLong() and 0xFF) shl 16) or
                        ((addr[14].toLong() and 0xFF) shl 8) or
                        (addr[15].toLong() and 0xFF)
                // ::0.0.0.0 / ::1 fall out of the IPv4 rules too (0/8 is blocked).
                if (compat && v4 <= 1L) return true
                return isPrivateIpv4(v4)
            }
        }

        val b0 = addr[0].toInt() and 0xFF
        val b1 = addr[1].toInt() and 0xFF

        // :: (unspecified) and ::1 (loopback)
        if (addr.all { it.toInt() == 0 }) return true
        if ((0..14).all { addr[it].toInt() == 0 } && addr[15].toInt() == 1) return true

        if (b0 == 0xFF) return true // ff00::/8 multicast
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 link-local
        if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 unique-local

        return false
    }
}
