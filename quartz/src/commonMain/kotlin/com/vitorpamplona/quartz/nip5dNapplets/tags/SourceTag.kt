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
package com.vitorpamplona.quartz.nip5dNapplets.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-5D source: `["source", "<location>"]`, exactly two elements. The location is an absolute
 * `http`, `https`, `ssh` or `git` URL, or Git's scp-like `[user@]host:path`; local paths and `file`
 * URLs are refused. Informational only: it does not attest that the source builds the artifact.
 *
 * The value is untrusted and may end up in front of a user or a `git clone`, so [isLocation] reads
 * it by structure, not with a deny list: printable ASCII only (no spaces, invisible or bidi
 * characters to spoof the displayed repo), no user or host starting with `-` (`git`/`ssh` would
 * take it as an option), and an scp-like host must be a dotted name, which keeps drive letters
 * (`C:\…`) and other URI schemes (`javascript:`, `mailto:`, `intent:`) out.
 */
class SourceTag {
    companion object {
        const val TAG_NAME = "source"

        private val URL_SCHEMES = setOf("http", "https", "ssh", "git")
        private val HOST = Regex("[A-Za-z0-9][A-Za-z0-9.-]*")
        private val IP_LITERAL = Regex("\\[[0-9A-Fa-f:.]+]")

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): String? {
            ensure(tag.size == 2) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(isLocation(tag[1])) { return null }
            return tag[1]
        }

        fun isLocation(value: String): Boolean {
            if (value.isEmpty()) return false
            for (c in value) if (c.code !in 0x21..0x7E) return false

            val schemeEnd = value.indexOf("://")
            return if (schemeEnd >= 0) isUrl(value, schemeEnd) else isScpLike(value)
        }

        private fun isUrl(
            value: String,
            schemeEnd: Int,
        ): Boolean {
            if (value.substring(0, schemeEnd).lowercase() !in URL_SCHEMES) return false
            val rest = value.substring(schemeEnd + 3)
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            val at = authority.lastIndexOf('@')
            if (at >= 0 && !isUser(authority.substring(0, at))) return false
            val hostPort = authority.substring(at + 1)
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            val port = hostPort.substring(host.length).removePrefix(":")
            if (port.isNotEmpty() && (port.length > 5 || port.any { it !in '0'..'9' })) return false
            return HOST.matches(host) || IP_LITERAL.matches(host)
        }

        private fun isScpLike(value: String): Boolean {
            val colon = value.indexOf(':')
            if (colon <= 0) return false
            val left = value.substring(0, colon)
            val path = value.substring(colon + 1)
            if (path.isEmpty() || path.startsWith("//") || path.startsWith("-")) return false
            if ('/' in left) return false
            val at = left.lastIndexOf('@')
            if (at >= 0 && !isUser(left.substring(0, at))) return false
            val host = left.substring(at + 1)
            return HOST.matches(host) && '.' in host
        }

        /** A URL or scp-like user: something, with no `@`, `:` or `/` inside, and never an option. */
        private fun isUser(user: String) = user.isNotEmpty() && !user.startsWith("-") && user.none { it == '@' || it == ':' || it == '/' }

        fun assemble(location: String) = arrayOf(TAG_NAME, location)
    }
}
