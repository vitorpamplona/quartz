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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag

/**
 * NIP-FB's claim protocol as pure decisions, so every client runs it the same way; the client
 * wires the relays and the timers. For an occurrence due while the task is enabled, on a device
 * where the user turned it on:
 *
 * 1. wait a random 0 to [MAX_JITTER_SECONDS];
 * 2. read the newest event from the write relays ([hasQuorum] of them must answer; none, or a
 *    content that doesn't decrypt, never runs);
 *    [decide] says whether to stop, skip (the result exists: [skipped] writes the claim and `ran`)
 *    or claim;
 * 3. subscribe to the event, publish [claimed] ([hasQuorum] of the relays must accept it);
 * 4. after [SETTLE_SECONDS], [settle] on the [winner] of everything seen: run if it holds this
 *    device's claim, stop if it holds another's, and go back to 2 (at most [MAX_ROUNDS] times,
 *    and only while the occurrence is still the latest) when it holds no claim for it, an edit
 *    by a client that didn't see the claim;
 * 5. while running, stop as soon as the newest event [claimsForAnother] this occurrence;
 * 6. when done, [finished] sets `ran`.
 */
object ScheduledTaskClaims {
    /** The random wait before reading, so devices due at the same second don't read together. */
    const val MAX_JITTER_SECONDS = 60L

    /** How long a claimer listens for competing claims before it runs. */
    const val SETTLE_SECONDS = 20L

    /** A claim with no `ran` this long after it was made may be claimed again. */
    const val STALE_CLAIM_SECONDS = 3_600L

    /** How many times a claimer goes back to the read when its claim was overwritten by an unrelated edit. */
    const val MAX_ROUNDS = 3

    /** What the newest event says once the claim settled. */
    enum class Settled {
        /** It holds this device's claim: run. */
        OURS,

        /** It claims this occurrence for another device: stop. */
        LOST,

        /** It holds no claim for this occurrence (an edit that didn't see ours): read and claim again. */
        AGAIN,
    }

    /** What to do with an occurrence after the read. */
    enum class Decision {
        /** Someone (maybe this device) already claimed this occurrence, and it isn't stale. */
        ALREADY_CLAIMED,

        /** The task is paused, or its cron or task can't be run here. */
        NOT_RUNNABLE,

        /** Claim it. */
        CLAIM,
    }

    /** More than half of the [asked] write relays answered the read, or accepted the claim. None asked: never. */
    fun hasQuorum(
        asked: Int,
        answered: Int,
    ): Boolean = asked > 0 && answered * 2 > asked

    /** The newest event: the highest `created_at`, then the lowest `id`. */
    fun <T : Event> winner(events: Collection<T>): T? = events.minWithOrNull(compareByDescending<T> { it.createdAt }.thenBy { it.id })

    /** The occurrence to run at [now]: the last one due, which stays claimable until the next comes. */
    fun occurrence(
        task: ScheduledTask,
        zone: CronZone,
        now: Long,
    ): Long? = task.expression()?.lastAtOrBefore(now, zone)

    /** Whether [task]'s claim is finished: `ran` at or after its due time. */
    fun isFinished(task: ScheduledTask): Boolean {
        val claim = task.claim ?: return false
        val ran = task.ran ?: return false
        return ran >= claim.due
    }

    /**
     * Whether the claim on [task] may be claimed again: unfinished and made (its `at`) at least
     * [STALE_CLAIM_SECONDS] before [now]. A claim that doesn't say when it was made is stale.
     */
    fun takeoverAllowed(
        task: ScheduledTask,
        now: Long,
    ): Boolean {
        val claim = task.claim ?: return true
        if (isFinished(task)) return false
        val at = claim.at ?: return true
        return now - at >= STALE_CLAIM_SECONDS
    }

    /**
     * The read's verdict for [occurrence] on [task], the newest event's tags. [taskName] is the
     * job this client runs: a task without it, or without a readable `cron`, is never run.
     */
    fun decide(
        task: ScheduledTask,
        taskName: String,
        occurrence: Long,
        now: Long,
    ): Decision {
        if (task.paused || task.expression() == null || task.task != taskName) return Decision.NOT_RUNNABLE
        val claim = task.claim
        if (claim != null && claim.due == occurrence && !takeoverAllowed(task, now)) return Decision.ALREADY_CLAIMED
        return Decision.CLAIM
    }

    /** [task] with [occurrence] claimed at [at] by the installation [installId], named [by]. */
    fun claimed(
        task: ScheduledTask,
        occurrence: Long,
        installId: String,
        by: String?,
        at: Long,
    ): ScheduledTask = task.withClaim(ClaimTag(occurrence, installId, by, at))

    /** The result already exists: claim [occurrence] and mark it done, at [at], in one write, without running. */
    fun skipped(
        task: ScheduledTask,
        occurrence: Long,
        installId: String,
        by: String?,
        at: Long,
    ): ScheduledTask = claimed(task, occurrence, installId, by, at).withRan(at)

    /** The claimed occurrence finished at [at]: only `ran` changes. */
    fun finished(
        task: ScheduledTask,
        at: Long,
    ): ScheduledTask = task.withRan(at)

    /** Step 4's verdict on [task], the newest event's tags, for [installId]'s claim on [occurrence]. */
    fun settle(
        task: ScheduledTask,
        occurrence: Long,
        installId: String,
    ): Settled =
        when {
            isOurs(task, occurrence, installId) -> Settled.OURS
            claimsForAnother(task, occurrence, installId) -> Settled.LOST
            else -> Settled.AGAIN
        }

    /** Whether [task] holds [installId]'s claim on [occurrence]. */
    fun isOurs(
        task: ScheduledTask,
        occurrence: Long,
        installId: String,
    ): Boolean = task.claim?.let { it.due == occurrence && it.id == installId } ?: false

    /** Whether [task] claims [occurrence] for an installation other than [installId]: then this one stops. */
    fun claimsForAnother(
        task: ScheduledTask,
        occurrence: Long,
        installId: String,
    ): Boolean = task.claim?.let { it.due == occurrence && it.id != installId } ?: false
}
