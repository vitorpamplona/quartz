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
package com.vitorpamplona.quartz.nipFAAgentKeys.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * `["key", <key>, <url>]`: a key to an AI provider and the root of the OpenAI-compatible
 * API it works against. Clients call `POST <url>/chat/completions` with
 * `Authorization: Bearer <key>`. [key] is empty for an endpoint that needs none (a local
 * runner); [url] is never blank. Lives only in the encrypted content of an
 * [com.vitorpamplona.quartz.nipFAAgentKeys.AgentKeyEvent].
 */
@Immutable
data class AgentKeyTag(
    val key: String,
    val url: String,
) {
    fun hasKey() = key.isNotEmpty()

    fun toTagArray() = assemble(key, url)

    /** [url] without one trailing `/`: what NIP-FA readers append `/chat/completions` and `/models` to. */
    fun apiRoot() = apiRoot(url)

    /**
     * Whether NIP-FA lets this key be sent to its [url]: `https`, or `http` to a loopback host,
     * or any `http(s)` when there is no key to leak. A client never sends a key that fails this.
     */
    fun isUrlAllowed() = isUrlAllowed(key, url)

    /** Keeps the key out of logs and crash reports. */
    override fun toString() = "AgentKeyTag(url=$url, key=${if (hasKey()) "<redacted>" else "<none>"})"

    companion object {
        const val TAG_NAME = "key"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): AgentKeyTag? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[2].isNotBlank()) { return null }
            return AgentKeyTag(tag[1], tag[2])
        }

        fun assemble(
            key: String,
            url: String,
        ) = arrayOf(TAG_NAME, key, url)

        fun assemble(tag: AgentKeyTag) = assemble(tag.key, tag.url)

        /** [url] trimmed, without one trailing `/`. */
        fun apiRoot(url: String) = url.trim().removeSuffix("/")

        /** See [AgentKeyTag.isUrlAllowed]. */
        fun isUrlAllowed(
            key: String,
            url: String,
        ): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return false
            val scheme = trimmed.substringBefore("://", "").lowercase()
            val authority = authorityOf(trimmed) ?: return false
            return when (scheme) {
                "https" -> true
                "http" -> key.isEmpty() || isLoopbackAuthority(authority)
                else -> false
            }
        }

        /** A loopback host (`localhost`, `127.0.0.0/8`, `[::1]`) in an `http(s)` URL. */
        fun isLoopback(url: String): Boolean = authorityOf(url.trim())?.let(::isLoopbackAuthority) ?: false

        private fun authorityOf(url: String): String? {
            if (!url.contains("://")) return null
            val authority =
                url
                    .substringAfter("://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
            return authority.ifEmpty { null }
        }

        private fun isLoopbackAuthority(authority: String): Boolean {
            // `http://localhost@evil.example` is evil.example: refuse any userinfo outright.
            if (authority.contains('@')) return false
            val host =
                if (authority.startsWith("[")) {
                    authority.substringBefore(']', "") + "]"
                } else {
                    authority.substringBefore(':')
                }.lowercase()
            if (host == "localhost" || host == "[::1]") return true
            val octets = host.split('.')
            return octets.size == 4 &&
                octets[0] == "127" &&
                octets.all { part -> part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() <= 255 }
        }
    }
}
