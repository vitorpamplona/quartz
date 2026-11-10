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
package com.vitorpamplona.quartz.nipXXAgentKeys.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * `["key", <key>, <url>]`: a key to an AI provider and the root of the OpenAI-compatible
 * API it works against. Clients call `POST <url>/chat/completions` with
 * `Authorization: Bearer <key>`. [key] is empty for an endpoint that needs none (a local
 * runner); [url] is never blank. Lives only in the encrypted content of an
 * [com.vitorpamplona.quartz.nipXXAgentKeys.AgentKeyEvent].
 */
@Immutable
data class AgentKeyTag(
    val key: String,
    val url: String,
) {
    fun hasKey() = key.isNotEmpty()

    fun toTagArray() = assemble(key, url)

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
    }
}
