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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip89AppHandlers.clientTag.ClientTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ParamTag
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
 * read ([expression] null), or one without a zone ([tz] null; one this platform doesn't know is the
 * client's to check), is kept as written and never run.
 *
 * The task's options are its [params] (`["param", name, values…]`), ordered and repeatable, kept
 * as read, the ones this client doesn't understand included. A public `p` names a [runner]:
 * another key that runs the task for the author, to which the private tags are encrypted, and
 * then no device of the author runs it on schedule. More than one `p` is a conflict
 * ([hasRunnerConflict]), and so is one that isn't a lowercase hex key of someone other than the
 * author ([runnerFor]): the author's devices still stop, and nobody runs it.
 */
@Immutable
class ScheduledTask(
    val publicTags: TagArray = emptyArray(),
    val privateTags: TagArray = emptyArray(),
) {
    private val merged: TagArray = privateTags + publicTags

    /** Parsed once: the screens, the claim and the timers all ask. */
    private val parsed: CronExpression? by lazy { cron?.let(CronExpression::parse) }

    /** The job, e.g. `observer` for the morning Observer edition. */
    val task: String? get() = merged.taskName()
    val paused: Boolean get() = merged.isPaused()
    val enabled: Boolean get() = !paused
    val cronTag: CronTag? get() = merged.cron()
    val cron: String? get() = cronTag?.expression
    val tz: String? get() = cronTag?.zone

    /** When the schedule was last set or resumed: nothing due before it is claimed, caught up or missed. */
    val cronSetAt: Long get() = cronTag?.setAt ?: 0
    val claim: ClaimTag? get() = merged.claim()

    /** Unix seconds: when the claimed occurrence finished (ran, or skipped as already done). */
    val ran: Long? get() = merged.ran()

    /** Every `param`: the private ones first, then the public ones, each side in its order. */
    val params: List<ParamTag> by lazy { merged.params() }

    /** The `param`s named [name], in order (private first). */
    fun params(name: String): List<ParamTag> = merged.params(name)

    /** The first `param` named [name] (private first), or null. */
    fun param(name: String): ParamTag? = params(name).firstOrNull()

    /** The values of the first `param` named [name], or null when there is none. */
    fun paramValues(name: String): List<String>? = param(name)?.values

    /**
     * The value of every public `p` tag, valid or not: a blank `["p", ""]` counts (it stops the
     * devices, and nobody runs the task), a `["p"]` with no value doesn't. NIP-FB reads the runner
     * from the public tags only; a private `p` is just a tag this client keeps.
     */
    val runners: List<String> by lazy { publicTags.runnerKeys() }

    /** Whether the task names a runner at all (one `p` or more): then no device of the author runs it on schedule. */
    val namesRunner: Boolean get() = runners.isNotEmpty()

    /** More than one public `p`: NIP-FB's runner conflict. Nobody runs the task. */
    val hasRunnerConflict: Boolean get() = runners.size > 1

    /**
     * The one key that runs the task for its author, or null when it names none, more than one
     * ([hasRunnerConflict]), or a `p` that isn't a lowercase hex key ([isRunnerKey]). The tags
     * don't say who the author is: [runnerFor] also refuses the author's own key.
     */
    val runner: HexKey? by lazy { runners.singleOrNull()?.takeIf(::isRunnerKey) }

    /** The [runner] of [author]'s task: null also when the only `p` is [author] itself, which names nobody who runs it. */
    fun runnerFor(author: HexKey): HexKey? = runner?.takeIf { it != author }

    /**
     * The other key of the NIP-44 conversation the private tags are encrypted in, for [author]:
     * the runner ([runnerFor]) when there is one, else the author itself (NIP-51's
     * encrypt-to-self). A task with a runner conflict, or a `p` that names nobody, is encrypted to
     * self: nobody runs it, and only the author must read it. The author decrypts with the same key.
     */
    fun privateTagsPeer(author: HexKey): HexKey = runnerFor(author) ?: author

    /** The schedule, or null when [cron] is absent or outside NIP-FB's subset: then it never runs. */
    fun expression(): CronExpression? = parsed

    /** Whether the schedule can be read at all: an expression in NIP-FB's subset and a zone. Whether the zone is one the platform knows is the caller's to add. */
    fun isReadable(): Boolean = parsed != null && tz != null

    /** The schedule as a simple editor shows it (a time, some weekdays), or null when it can't. */
    fun simple(): SimpleCron? = cron?.let(SimpleCron::parse)

    /**
     * Paused, or resumed at [now]: resuming a paused task restarts its schedule from [now], so the
     * occurrences it slept through are nobody's to run or report.
     */
    fun withEnabled(
        on: Boolean,
        now: Long,
    ): ScheduledTask {
        if (!on) return replacing(PausedTag::isTag, PausedTag.assemble())
        if (!paused) return this
        val resumed = without(PausedTag::isTag)
        val cron = cronTag ?: return resumed
        return resumed.replacing(CronTag::isTag, cron.copy(setAt = now).toTagArray())
    }

    /** A new schedule, set at [now] in the editor's [zone]: the expression, the zone and the time it was set change together. */
    fun withCron(
        cron: String,
        zone: String,
        now: Long,
    ) = replacing(CronTag::isTag, CronTag.assemble(cron, zone, now))

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

    /**
     * This task with the `param`s named [name] set to one `param` holding [values]: it takes the
     * place of the first one (private side first, as readers see them) and every other one of
     * that name goes, on either side. With none, it is added to the private side. Every other
     * tag, unknown params included, stays where it was.
     */
    fun withParam(
        name: String,
        values: List<String>,
    ): ScheduledTask {
        val isName = { tag: Array<String> -> ParamTag.isTag(tag, name) }
        val tag = ParamTag.assemble(name, values)
        return when {
            privateTags.any(isName) -> ScheduledTask(publicTags.filterNot(isName).toTypedArray(), swap(privateTags, isName, tag))
            publicTags.any(isName) -> ScheduledTask(swap(publicTags, isName, tag), privateTags)
            else -> ScheduledTask(publicTags, privateTags + arrayOf(tag))
        }
    }

    fun withParam(
        name: String,
        vararg values: String,
    ) = withParam(name, values.asList())

    /**
     * This task with one more `param` named [name], after the last one of that name as readers
     * see them (private first, then public), on that side; with none, at the end of the private side.
     */
    fun withAddedParam(
        name: String,
        values: List<String>,
    ): ScheduledTask {
        val isName = { tag: Array<String> -> ParamTag.isTag(tag, name) }
        val tag = ParamTag.assemble(name, values)
        return when {
            publicTags.any(isName) -> ScheduledTask(insertAfterLast(publicTags, isName, tag), privateTags)
            privateTags.any(isName) -> ScheduledTask(publicTags, insertAfterLast(privateTags, isName, tag))
            else -> ScheduledTask(publicTags, privateTags + arrayOf(tag))
        }
    }

    /** This task without any `param` named [name], on either side. */
    fun withoutParam(name: String) = without { ParamTag.isTag(it, name) }

    /**
     * This task run by [runner], or by the author's own devices when it is null, changed at [now]:
     * every public `p` goes and, for a runner, one public `["p", runner]` is added. A private `p`
     * isn't a runner and stays. The event built from it ([ScheduledTaskEvent.update]) encrypts the
     * private tags to the new peer ([privateTagsPeer]): the runner, or the author when cleared.
     *
     * Adding, changing or removing the runner restarts the schedule from [now] (`cron`'s set
     * time, as [withEnabled] does on resume): whoever runs it from now on never claims, runs or
     * reports an occurrence that was due while the other one did. Without it, a device would print
     * again the morning the runner just printed, and a runner would print the one a device has
     * claimed. A task that already names exactly [runner] (or none, for null) comes back as is.
     */
    fun withRunner(
        runner: HexKey?,
        now: Long,
    ): ScheduledTask {
        if (if (runner == null) runners.isEmpty() else runners.size == 1 && runners[0] == runner) return this
        val public = publicTags.filterNot(::isPublicRunnerTag)
        val moved = ScheduledTask((if (runner != null) public + arrayOf(PTag.assemble(runner, null)) else public).toTypedArray(), privateTags)
        val cron = cronTag ?: return moved
        return moved.replacing(CronTag::isTag, cron.copy(setAt = now).toTagArray())
    }

    private fun isPublicRunnerTag(tag: Array<String>) = tag.isNotEmpty() && tag[0] == PTag.TAG_NAME

    private fun insertAfterLast(
        tags: TagArray,
        isTag: (Array<String>) -> Boolean,
        tag: Array<String>,
    ): TagArray {
        val at = tags.indexOfLast(isTag) + 1
        return (tags.copyOfRange(0, at).asList() + listOf(tag) + tags.copyOfRange(at, tags.size).asList()).toTypedArray()
    }

    /**
     * This task without a public `client` tag (it tells anyone which app the user schedules with);
     * a private one stays. The one exception to keeping every tag where it was.
     */
    fun withoutPublicClientTag(): ScheduledTask = if (publicTags.any(::isPublicClientTag)) ScheduledTask(publicTags.filterNot(::isPublicClientTag).toTypedArray(), privateTags) else this

    private fun isPublicClientTag(tag: Array<String>) = tag.isNotEmpty() && tag[0] == ClientTag.TAG_NAME

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

    /**
     * The last occurrence due more than [GRACE_SECONDS] before [now] in [zone] (NIP-FB: "more than
     * 3 hours ago", so not at exactly 3 hours); null when none or the cron can't be read.
     */
    fun lastDue(
        zone: CronZone,
        now: Long,
    ): Long? = expression()?.lastAtOrBefore(now - GRACE_SECONDS - 1, zone)?.takeIf { it >= cronSetAt }

    /**
     * The occurrence that didn't run: the last one due ([lastDue], never one before the schedule
     * was set) when `ran` is older. Null when the task is paused, nothing was due, or it has never
     * run (no `ran`: nothing to compare with).
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

    override fun toString() = "ScheduledTask(task=$task, paused=$paused, cron=$cron, tz=$tz, claim=$claim, ran=$ran, runners=${runners.size}, params=${params.map { it.name }})"

    companion object {
        /**
         * Whether a `p` can name a runner: a pubkey as NIP-01 writes it, 64 lowercase hex
         * characters. Any other `p` (uppercase, an npub, junk) still stops the author's devices,
         * and nobody runs the task.
         */
        fun isRunnerKey(value: String): Boolean {
            if (value.length != 64) return false
            for (c in value) if (c !in '0'..'9' && c !in 'a'..'f') return false
            return true
        }

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
            setAt: Long,
            paused: Boolean = false,
        ) = ScheduledTask(
            publicTags = arrayOf(DTag.assemble(dTag)),
            privateTags = listOfNotNull(TaskTag.assemble(task), CronTag.assemble(cron, zone, setAt), if (paused) PausedTag.assemble() else null).toTypedArray(),
        )
    }
}
