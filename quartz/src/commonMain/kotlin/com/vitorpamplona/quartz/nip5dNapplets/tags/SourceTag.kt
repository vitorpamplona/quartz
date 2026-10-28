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
 */
class SourceTag {
    companion object {
        const val TAG_NAME = "source"

        private val URL = Regex("^(https?|ssh|git)://[^/\\s]+(/\\S*)?$", RegexOption.IGNORE_CASE)
        private val SCP_LIKE = Regex("^([^@:/\\s]+@)?[A-Za-z0-9][A-Za-z0-9.-]*:\\S+$")
        private val NOT_HOSTS = setOf("file", "javascript", "data", "vbscript")

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): String? {
            ensure(tag.size == 2) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(isLocation(tag[1])) { return null }
            return tag[1]
        }

        fun isLocation(value: String): Boolean = URL.matches(value) || (SCP_LIKE.matches(value) && value.substringBefore(':').substringAfter('@').lowercase() !in NOT_HOSTS)

        fun assemble(location: String) = arrayOf(TAG_NAME, location)
    }
}
