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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.PausedTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.RanTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.TaskTag

/**
 * A person's recurring task (NIP-FB, [ScheduledTaskEvent]) as its two tag lists: [publicTags]
 * (the event's tags, `d` among them) and [privateTags] (the decrypted content). Readers use both,
 * private first. Every change keeps each tag on the side it was found, puts a new one on the
 * private side, and keeps every tag it doesn't know (the task's own options), so a rewrite by
 * one client never drops what another wrote.
 *
 * What to run ([task]), when ([cron] on the wall clock of [tz]; never while [paused]), the
 * latest [claim] and when that claimed occurrence finished ([ran]). A [cron] this client can't
 * read ([expression] null) is kept as written and never run.
 */
@Immutable
class ScheduledTask(
    val publicTags: TagArray = emptyArray(),
    val privateTags: TagArray = emptyArray(),
) {
    private val merged: TagArray = privateTags + publicTags

    /** The job, e.g. `observer` for the morning Observer edition. */
    val task: String? get() = merged.taskName()
    val paused: Boolean get() = merged.isPaused()
    val enabled: Boolean get() = !paused
    val cronTag: CronTag? get() = merged.cron()
    val cron: String? get() = cronTag?.expression
    val tz: String? get() = cronTag?.zone
    val claim: ClaimTag? get() = merged.claim()

    /** Unix seconds: when the claimed occurrence finished (ran, or skipped as already done). */
    val ran: Long? get() = merged.ran()

    /** The schedule, or null when [cron] is absent or outside NIP-FB's subset: then it never runs. */
    fun expression(): CronExpression? = cron?.let(CronExpression::parse)

    /** The schedule as a simple editor shows it (a time, some weekdays), or null when it can't. */
    fun simple(): SimpleCron? = cron?.let(SimpleCron::parse)

    fun withEnabled(on: Boolean) = if (on) without(PausedTag::isTag) else replacing(PausedTag::isTag, PausedTag.assemble())

    /** A new schedule, in the editor's [zone]: the expression and the zone always change together. */
    fun withCron(
        cron: String,
        zone: String,
    ) = replacing(CronTag::isTag, CronTag.assemble(cron, zone))

    fun withClaim(claim: ClaimTag) = replacing(ClaimTag::isTag, claim.toTagArray())

    fun withRan(at: Long) = replacing(RanTag::isTag, RanTag.assemble(at))

    /**
     * This task with every tag [isTag] matches replaced by [tag], on the side the first one was
     * found (public or private), else added to the private side.
     */
    fun replacing(
        isTag: (Array<String>) -> Boolean,
        tag: Array<String>,
    ): ScheduledTask {
        val inPrivate = privateTags.any(isTag)
        val inPublic = !inPrivate && publicTags.any(isTag)
        return if (inPublic) {
            ScheduledTask(swap(publicTags, isTag, tag), privateTags)
        } else if (inPrivate) {
            ScheduledTask(publicTags, swap(privateTags, isTag, tag))
        } else {
            ScheduledTask(publicTags, privateTags + arrayOf(tag))
        }
    }

    /** This task without the tags [isTag] matches, on either side. */
    fun without(isTag: (Array<String>) -> Boolean) = ScheduledTask(publicTags.filterNot(isTag).toTypedArray(), privateTags.filterNot(isTag).toTypedArray())

    /** The first matching tag becomes [tag] where it stood; any later duplicate goes. */
    private fun swap(
        tags: TagArray,
        isTag: (Array<String>) -> Boolean,
        tag: Array<String>,
    ): TagArray {
        var done = false
        return tags
            .mapNotNull {
                when {
                    !isTag(it) -> it
                    done -> null
                    else -> {
                        done = true
                        tag
                    }
                }
            }.toTypedArray()
    }

    /** The last occurrence due by [now] in [zone] whose grace ([GRACE_SECONDS]) has passed; null when none or the cron can't be read. */
    fun lastDue(
        zone: CronZone,
        now: Long,
    ): Long? = expression()?.lastAtOrBefore(now - GRACE_SECONDS, zone)

    /**
     * The occurrence that didn't run: the last one due ([lastDue]) when `ran` is older. Null when
     * the task is paused, nothing was due, or it has never run (no `ran`: nothing to compare with).
     */
    fun missedRun(
        zone: CronZone,
        now: Long,
    ): Long? {
        if (paused) return null
        val ran = ran ?: return null
        val due = lastDue(zone, now) ?: return null
        return if (ran < due) due else null
    }

    override fun equals(other: Any?) = other is ScheduledTask && other.publicTags.contentDeepEquals(publicTags) && other.privateTags.contentDeepEquals(privateTags)

    override fun hashCode() = 31 * publicTags.contentDeepHashCode() + privateTags.contentDeepHashCode()

    override fun toString() = "ScheduledTask(task=$task, paused=$paused, cron=$cron, tz=$tz, claim=$claim, ran=$ran)"

    companion object {
        /** Every day at 7:00: only the suggestion. */
        const val DEFAULT_CRON = "0 7 * * *"

        /** An occurrence counts as missed only this long after it was due. */
        const val GRACE_SECONDS = 3 * 3_600L

        /** A new task under [dTag]: only `d` public, everything else private, as NIP-FB advises. */
        fun create(
            dTag: String,
            task: String,
            cron: String = DEFAULT_CRON,
            zone: String,
            paused: Boolean = false,
        ) = ScheduledTask(
            publicTags = arrayOf(DTag.assemble(dTag)),
            privateTags = listOfNotNull(TaskTag.assemble(task), CronTag.assemble(cron, zone), if (paused) PausedTag.assemble() else null).toTypedArray(),
        )
    }
}
