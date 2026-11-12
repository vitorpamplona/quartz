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
package com.vitorpamplona.quartz.nipFBScheduledTasks

import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ParamTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.PausedTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.RanTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.TaskTag

fun TagArray.taskName() = fastFirstNotNullOfOrNull(TaskTag::parse)

fun TagArray.cron() = fastFirstNotNullOfOrNull(CronTag::parse)

fun TagArray.isPaused() = fastAny(PausedTag::isTag)

fun TagArray.claim() = fastFirstNotNullOfOrNull(ClaimTag::parse)

fun TagArray.ran() = fastFirstNotNullOfOrNull(RanTag::parse)

/** Every `param`, in order; a name may repeat. */
fun TagArray.params(): List<ParamTag> = mapNotNull(ParamTag::parse)

/** The `param`s named [name], in order. */
fun TagArray.params(name: String): List<ParamTag> = mapNotNull { tag -> if (ParamTag.isTag(tag, name)) ParamTag.parse(tag) else null }

/**
 * The value of every `p` tag, well-formed or not: a NIP-FB task's runners, read from the PUBLIC
 * tags only. More than one is a conflict, and nobody runs the task.
 */
fun TagArray.runnerKeys(): List<String> = mapNotNull { tag -> if (tag.has(1) && tag[0] == PTag.TAG_NAME && tag[1].isNotBlank()) tag[1] else null }
