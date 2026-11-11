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
 * `["cron", <five fields>, <IANA zone>, <set at>]`: when a scheduled task runs, read on the
 * zone's wall clock, and when the schedule was last set (unix seconds; 0 when absent). Nothing
 * due before [setAt] is ever claimed, caught up or reported missed. The expression is kept as
 * written; whether this client can read it is
 * [com.vitorpamplona.quartz.nipFBScheduledTasks.CronExpression.parse]'s question.
 */
@Immutable
data class CronTag(
    val expression: String,
    val zone: String?,
    val setAt: Long = 0,
) {
    fun toTagArray() = assemble(expression, zone, setAt)

    companion object {
        const val TAG_NAME = "cron"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): CronTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotBlank()) { return null }
            return CronTag(tag[1], tag.getOrNull(2)?.takeIf { it.isNotBlank() }, tag.getOrNull(3)?.toLongOrNull() ?: 0)
        }

        fun assemble(
            expression: String,
            zone: String?,
            setAt: Long,
        ) = arrayOf(TAG_NAME, expression, zone.orEmpty(), setAt.toString())
    }
}
