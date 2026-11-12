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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag

/**
 * NIP-FB's claim protocol as pure decisions, so every client runs it the same way; the client
 * wires the relays and the timers. For an occurrence due while the task is enabled, on a device
 * where the user turned it on:
 *
 * 1. wait a random 0 to [MAX_JITTER_SECONDS];
 * 2. read the newest event from the write relays ([hasQuorum] of them must answer; none, or a
 *    content that doesn't decrypt, never runs);
 *    [decide] says whether to stop or claim, from the read and this installation's own record of
 *    the occurrence ([OwnRun]: a run that died mid-way resumes, one that ended is never redone). When the result already exists, NIP-FB lets a client
 *    write the claim and `ran` together ([skipped]); a client can instead claim and settle as for
 *    a run, then write only `ran` ([finished]) when its claim won, so a skip can never take an
 *    occurrence from a device that is running it;
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

    /**
     * What this installation's own record says of its run of one occurrence. The record is the
     * client's, kept on the device (NIP-FB doesn't sync it): written as the run starts, and again
     * however it ends.
     */
    enum class OwnRun {
        /** No record: this installation never started a run of the occurrence, or doesn't remember one. */
        NONE,

        /**
         * Started and never ended: the process died mid-run (killed, crashed, the device turned
         * off). Its unfinished claim is its own to take up again, without the hour's wait.
         */
        DIED,

        /**
         * Ended, whatever the end was (printed, failed, cancelled, stopped by the platform). A run
         * that failed may already have spent the user's money, so it is never run again here on
         * its own; the user can still run it by hand.
         */
        ENDED,
    }

    /** What to do with an occurrence after the read. */
    enum class Decision {
        /** Someone (maybe this device) already claimed this occurrence, and it isn't stale. */
        ALREADY_CLAIMED,

        /** This installation already ran this occurrence ([OwnRun.ENDED]): not again on its own. */
        ATTEMPTED_HERE,

        /** The task is paused, or its cron or task can't be run here. */
        NOT_RUNNABLE,

        /**
         * The task names a runner (a public `p`, or several: a conflict). Only that runner runs it,
         * and it doesn't claim: no device of the author claims or runs it on schedule. Running it
         * by hand still works everywhere.
         */
        RUNNER_NAMED,

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

    /**
     * The occurrence to run at [now]: the last one due, which stays claimable until the next
     * comes. Never one due before the schedule was set or resumed ([ScheduledTask.cronSetAt]).
     */
    fun occurrence(
        task: ScheduledTask,
        zone: CronZone,
        now: Long,
    ): Long? = task.expression()?.lastAtOrBefore(now, zone)?.takeIf { it >= task.cronSetAt }

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
     * job this client runs: a task without it, or without a readable `cron` and zone, is never run.
     * A task that names a runner is never claimed by the author's devices ([Decision.RUNNER_NAMED]).
     *
     * [ownRun] is what this installation ([installId]) recorded of its own run of [occurrence]:
     * - [OwnRun.ENDED]: [Decision.ATTEMPTED_HERE], whatever the relays hold, even once its claim
     *   is an hour old. A run that ended with a failure is not run again automatically: it may
     *   have been paid for, and the next attempt would fail the same way.
     * - [OwnRun.DIED]: its own unfinished claim is taken up again at once.
     * - [OwnRun.NONE]: its own claim is like anyone's, claimable again only once stale.
     */
    fun decide(
        task: ScheduledTask,
        taskName: String,
        occurrence: Long,
        now: Long,
        installId: String? = null,
        ownRun: OwnRun = OwnRun.NONE,
    ): Decision {
        if (task.namesRunner) return Decision.RUNNER_NAMED
        if (task.paused || !task.isReadable() || task.task != taskName) return Decision.NOT_RUNNABLE
        if (ownRun == OwnRun.ENDED) return Decision.ATTEMPTED_HERE
        val claim = task.claim
        if (claim != null && claim.due == occurrence && !takeoverAllowed(task, now)) {
            val resumable = ownRun == OwnRun.DIED && installId != null && claim.id == installId && !isFinished(task)
            if (!resumable) return Decision.ALREADY_CLAIMED
        }
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

    /** What a runner does with a task at one moment ([decideAsRunner]). It never claims. */
    enum class RunnerVerdict {
        /** The task names another key, or none: not this runner's. */
        NOT_THIS_RUNNER,

        /** The task names more than one runner: nobody runs it. */
        RUNNER_CONFLICT,

        /** The task is paused, or its task or cron can't be run. */
        NOT_RUNNABLE,

        /** No occurrence is due: none yet, or none since the schedule was set or resumed. */
        NOT_DUE,

        /** The occurrence's result already exists (the task says how to tell): nothing to run. */
        ALREADY_DONE,

        /** This runner already ran the occurrence ([OwnRun.ENDED]): not again on its own. */
        ATTEMPTED_HERE,

        /** Run the occurrence. */
        RUN,
    }

    /** [verdict] on the occurrence due at [occurrence] (null when none is due). */
    data class RunnerDecision(
        val verdict: RunnerVerdict,
        val occurrence: Long?,
    ) {
        val shouldRun: Boolean get() = verdict == RunnerVerdict.RUN
    }

    /**
     * A runner's verdict (NIP-FB, Runner) on [task] at [now] in [zone], for the [runner] key. A
     * runner can't sign the author's event, so it never claims and never writes `claim` or `ran`;
     * its own records and the result it publishes are what keep it from running an occurrence twice.
     *
     * The occurrence is the device's ([occurrence]): the last one due, never one due before the
     * schedule was set or resumed, and only until the next one is due (a missed one isn't run
     * after that). It runs when the task names exactly this runner, isn't paused, is [taskName]
     * with a readable `cron` and zone, the occurrence's result doesn't exist yet ([alreadyDone],
     * e.g. the Observer's `ObserverSite.alreadyPrinted` over the reader's and the runner's
     * sites), and this runner didn't already end a run of it ([ownRun], as for a device).
     *
     * Call [checkRunner] on the event first: being named in `p` is not enough.
     */
    fun decideAsRunner(
        task: ScheduledTask,
        taskName: String,
        runner: HexKey,
        zone: CronZone,
        now: Long,
        ownRun: (occurrence: Long) -> OwnRun = { OwnRun.NONE },
        alreadyDone: (occurrence: Long) -> Boolean = { false },
    ): RunnerDecision {
        if (task.hasRunnerConflict) return RunnerDecision(RunnerVerdict.RUNNER_CONFLICT, null)
        if (task.runner != runner) return RunnerDecision(RunnerVerdict.NOT_THIS_RUNNER, null)
        if (task.paused || !task.isReadable() || task.task != taskName) return RunnerDecision(RunnerVerdict.NOT_RUNNABLE, null)
        val due = occurrence(task, zone, now) ?: return RunnerDecision(RunnerVerdict.NOT_DUE, null)
        if (ownRun(due) == OwnRun.ENDED) return RunnerDecision(RunnerVerdict.ATTEMPTED_HERE, due)
        if (alreadyDone(due)) return RunnerDecision(RunnerVerdict.ALREADY_DONE, due)
        return RunnerDecision(RunnerVerdict.RUN, due)
    }

    /** Why a runner may or may not act on a task event ([checkRunner]). */
    enum class RunnerCheck {
        /** Authored by the expected owner, naming exactly this runner, validly signed. */
        OK,

        /** Authored by someone other than the owner this runner serves: anyone can tag a runner. */
        NOT_OWNER,

        /** Its public `p` isn't exactly this runner (another key, none, or more than one). */
        NOT_THIS_RUNNER,

        /** Its id or signature doesn't verify. */
        BAD_SIGNATURE,
    }

    /**
     * The check a runner makes before it reads or runs [event] (NIP-FB, Runner: "being named in
     * `p` is not enough, or anyone could spend its credentials"): its author is [expectedOwner],
     * the owner the runner serves by its own records; its public `p` tags are exactly one, this
     * [runner]; and its id and signature verify. Only [RunnerCheck.OK] may run.
     */
    fun checkRunner(
        event: ScheduledTaskEvent,
        runner: HexKey,
        expectedOwner: HexKey,
    ): RunnerCheck {
        if (event.pubKey != expectedOwner) return RunnerCheck.NOT_OWNER
        val named = event.runners()
        if (named.size != 1 || named[0] != runner) return RunnerCheck.NOT_THIS_RUNNER
        if (!event.verify()) return RunnerCheck.BAD_SIGNATURE
        return RunnerCheck.OK
    }

    /** [checkRunner] is [RunnerCheck.OK]. */
    fun isRunnableBy(
        event: ScheduledTaskEvent,
        runner: HexKey,
        expectedOwner: HexKey,
    ): Boolean = checkRunner(event, runner, expectedOwner) == RunnerCheck.OK
}
