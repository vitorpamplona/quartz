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
package com.vitorpamplona.quartz.routstr.node

import androidx.compose.runtime.Immutable

/**
 * One `u` of a Routstr node announcement, with what kind of address it is.
 *
 * Live nodes publish public https hosts, Tor v3 onions over plain `http://`, and addresses no
 * client should call from an announcement: plain http to a public or LAN host
 * (`http://paygate:8000`, `http://99.68.36.178:8000`), loopback, IP literals over https, and
 * FIPS mesh names (`http://npub1….fips:28080`). [type] sorts them:
 *
 * - [Type.HTTPS]: `https://` to a public DNS name on https's own port (none written, or 443). No
 *   IP literal, no credentials in the URL, no single-label host, no reserved or private-use
 *   top-level name (`.local`, `.localhost`, `.internal`, `.lan`, `.home`, `.test`, `.invalid`,
 *   `.example`, `.arpa`, `.alt`, `.fips`), and no Kubernetes service name (`redis.default`,
 *   `x.default.svc`, `….svc.cluster.local`, `api.kube-system`). Another port is [Type.OTHER]: an
 *   announcement must not point a client at some other service on a host (a database, an admin
 *   page). The rule is for announced addresses only; a URL the user typed is theirs to call, and
 *   the app takes it without this check.
 * - [Type.ONION]: `http://` or `https://` to a Tor v3 onion (a 56-character base32 label before
 *   `.onion`), on any port. Only reachable through Tor; v2 onions and junk such as `test.onion`
 *   are [Type.OTHER].
 * - [Type.OTHER]: everything else, kept so a client can show what the node published.
 *
 * [isUsable] is the rule a client applies before it sends a request or a payment to an announced
 * node, and it is a SYNTAX filter only: it reads the URL, it resolves nothing. A public name can
 * resolve to a private, loopback or link-local address (or change to one later), so a client must
 * also refuse those addresses when it connects (check every address the name resolves to, and
 * connect to the one it checked) before it sends money or a request.
 */
@Immutable
data class RoutstrEndpoint(
    val url: String,
    val type: Type,
) {
    enum class Type { HTTPS, ONION, OTHER }

    fun isHttps() = type == Type.HTTPS

    fun isOnion() = type == Type.ONION

    /**
     * https always; an onion only when the client routes through Tor ([torEnabled]); nothing else,
     * ever. A syntax filter: the client still refuses a private address at resolve time.
     */
    fun isUsable(torEnabled: Boolean) = type == Type.HTTPS || (torEnabled && type == Type.ONION)

    companion object {
        private const val ONION_TLD = "onion"
        private const val ONION_V3_LENGTH = 56

        private const val HTTPS_PORT = "443"

        /**
         * Top-level names that never resolve publicly, that name another network (FIPS), or that a
         * Kubernetes cluster resolves to its own services: `<svc>.<namespace>` (`default`,
         * `kube-system`, `kube-public`), `<svc>.<ns>.svc`, `….svc.cluster` and `….cluster.local`.
         */
        private val NON_PUBLIC_TLDS =
            setOf(
                "local",
                "localhost",
                "localdomain",
                "internal",
                "intranet",
                "lan",
                "home",
                "corp",
                "private",
                "test",
                "invalid",
                "example",
                "arpa",
                "alt",
                "fips",
                "svc",
                "cluster",
                "default",
                "kube-system",
                "kube-public",
                "kube-node-lease",
                "pod",
            )

        fun classify(url: String) = RoutstrEndpoint(url, typeOf(url))

        fun typeOf(url: String): Type {
            val trimmed = url.trim()
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd <= 0) return Type.OTHER
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "https" && scheme != "http") return Type.OTHER

            val rest = trimmed.substring(schemeEnd + 3)
            val authorityEnd = rest.indexOfAny(AUTHORITY_END).let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            // Credentials in the URL, or an IPv6 literal: never a node to call.
            if ('@' in authority || authority.startsWith('[')) return Type.OTHER

            val colon = authority.lastIndexOf(':')
            val port = if (colon >= 0) authority.substring(colon + 1) else null
            if (port != null && !isPort(port)) return Type.OTHER
            val host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase().removeSuffix(".")

            val labels = host.split('.')
            if (labels.size < 2 || !labels.all(::isDnsLabel)) return Type.OTHER
            val tld = labels.last()

            if (tld == ONION_TLD) {
                return if (isOnionV3Label(labels[labels.size - 2])) Type.ONION else Type.OTHER
            }

            if (scheme != "https") return Type.OTHER
            if (port != null && port.trimStart('0') != HTTPS_PORT) return Type.OTHER
            if (tld in NON_PUBLIC_TLDS) return Type.OTHER
            // A public top-level name is letters (or an IDN's `xn--` form), so this also rules
            // out IPv4 literals, whose last label is digits.
            if (!(tld.length >= 2 && tld.all { it in 'a'..'z' }) && !tld.startsWith("xn--")) return Type.OTHER
            return Type.HTTPS
        }

        private val AUTHORITY_END = charArrayOf('/', '?', '#')

        private fun isPort(port: String) = port.length in 1..5 && port.all { it in '0'..'9' } && port.toInt() in 1..65535

        private fun isDnsLabel(label: String): Boolean {
            if (label.isEmpty() || label.length > 63) return false
            if (label.first() == '-' || label.last() == '-') return false
            return label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }

        private fun isOnionV3Label(label: String) = label.length == ONION_V3_LENGTH && label.all { it in 'a'..'z' || it in '2'..'7' }
    }
}
