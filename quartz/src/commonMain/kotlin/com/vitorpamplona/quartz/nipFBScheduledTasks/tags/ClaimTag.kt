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
package com.vitorpamplona.quartz.nipFBScheduledTasks.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * `["claim", <due>, <install id>, <device name>, <claimed at>]`: the latest claimed occurrence of
 * a scheduled task. [due] is the unix second the occurrence runs (the cron's instant, so every
 * device computes the same value), [id] the random id the claiming installation keeps for this
 * user, [by] a name the user recognizes ("Desktop (Linux)"), [at] when the claim was made.
 */
@Immutable
data class ClaimTag(
    val due: Long,
    val id: String,
    val by: String? = null,
    val at: Long? = null,
) {
    fun toTagArray() = assemble(due, id, by, at)

    companion object {
        const val TAG_NAME = "claim"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): ClaimTag? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            val due = tag[1].toLongOrNull() ?: return null
            ensure(tag[2].isNotBlank()) { return null }
            return ClaimTag(due, tag[2], tag.getOrNull(3)?.takeIf { it.isNotBlank() }, tag.getOrNull(4)?.toLongOrNull())
        }

        fun assemble(
            due: Long,
            id: String,
            by: String?,
            at: Long?,
        ) = arrayOf(TAG_NAME, due.toString(), id, by.orEmpty(), at?.toString().orEmpty()).let { tag ->
            // Trailing empty values add nothing.
            tag.copyOf(tag.indexOfLast { it.isNotEmpty() } + 1).requireNoNulls()
        }
    }
}
