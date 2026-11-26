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
package com.vitorpamplona.quartz.nip90Dvms.status.tags

import androidx.compose.runtime.Stable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-90 job feedback status: `["status", <status>, <extra-info>]`.
 *
 * [description] is the tag's own extra information (slot 2), empty when the tag has none. The
 * feedback event's `content` is a separate field (a sample of partial results, or the encrypted
 * payload of a private job) and is never read here.
 */
@Stable
class StatusTag(
    val code: String,
    val description: String,
) {
    /** The tag's human-readable extra information, or null when it has none. */
    val extraInfo: String? get() = description.ifEmpty { null }

    companion object {
        const val TAG_NAME = "status"

        fun isTag(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1].isNotEmpty()

        fun parse(tag: Array<String>): StatusTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            return StatusTag(tag[1], tag.getOrNull(2) ?: "")
        }

        @Deprecated(
            "Reads the event content as the description whenever it is not empty, but NIP-90 keeps the " +
                "content (partial results, encrypted payloads) apart from the tag's extra info. Use " +
                "parse(tag) for the extra info and the event's content for partial results. Removed in 3.0.0.",
            ReplaceWith("parse(tag)"),
        )
        fun parse(
            tag: Array<String>,
            content: String,
        ): StatusTag? {
            val status = parse(tag) ?: return null
            return if (content.isEmpty()) status else StatusTag(status.code, content)
        }

        fun assemble(
            code: String,
            description: String,
        ) = arrayOf(TAG_NAME, code, description)

        fun assemble(status: StatusTag) = assemble(status.code, status.description)
    }
}
