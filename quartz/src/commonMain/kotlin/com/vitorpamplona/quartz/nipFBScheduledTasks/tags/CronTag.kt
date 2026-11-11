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
 * `["cron", <five fields>, <IANA zone>]`: when a scheduled task runs, read on the zone's wall
 * clock. The expression is kept as written; whether this client can read it is
 * [com.vitorpamplona.quartz.nipFBScheduledTasks.CronExpression.parse]'s question.
 */
@Immutable
data class CronTag(
    val expression: String,
    val zone: String?,
) {
    fun toTagArray() = assemble(expression, zone)

    companion object {
        const val TAG_NAME = "cron"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): CronTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotBlank()) { return null }
            return CronTag(tag[1], tag.getOrNull(2)?.takeIf { it.isNotBlank() })
        }

        fun assemble(
            expression: String,
            zone: String?,
        ) = if (zone != null) arrayOf(TAG_NAME, expression, zone) else arrayOf(TAG_NAME, expression)
    }
}
