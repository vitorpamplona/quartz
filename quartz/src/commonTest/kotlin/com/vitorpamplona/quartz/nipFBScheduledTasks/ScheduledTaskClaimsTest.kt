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

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.Decision
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.TaskTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledTaskClaimsTest {
    private val newYork = CronZone { -5 * 3_600 } // November: EST all month
    private val hour = 3_600L
    private val day = 86_400L

    // 2026-11-11T11:00:00Z = 06:00 in New York, a Wednesday.
    private val six = 1_794_394_800L

    private val daily = ScheduledTask.create("observer", "observer", "0 6 * * *", "America/New_York", setAt = 0)

    @Test
    fun aQuorumIsMoreThanHalf() {
        assertTrue(ScheduledTaskClaims.hasQuorum(asked = 1, answered = 1))
        assertFalse(ScheduledTaskClaims.hasQuorum(asked = 2, answered = 1))
        assertTrue(ScheduledTaskClaims.hasQuorum(asked = 3, answered = 2))
        assertFalse(ScheduledTaskClaims.hasQuorum(asked = 4, answered = 2))
        assertFalse(ScheduledTaskClaims.hasQuorum(asked = 0, answered = 0))
    }

    @Test
    fun theWinnerIsTheNewestThenTheLowestId() =
        runTest {
            val a = NostrSignerInternal(KeyPair())
            val events = (1..6).map { a.sign<ScheduledTaskEvent>(ScheduledTaskEvent.update(ScheduledTaskClaims.claimed(daily, six, "i$it", null, six), a, "observer", createdAt = six + (if (it <= 3) 10 else 5))) }
            val newest = events.take(3)
            assertEquals(newest.minBy { it.id }, ScheduledTaskClaims.winner(events))
            assertEquals(newest.minBy { it.id }, ScheduledTaskClaims.winner(events.reversed()))
            assertNull(ScheduledTaskClaims.winner(emptyList<ScheduledTaskEvent>()))
        }

    @Test
    fun theOccurrenceIsTheLastDueUntilTheNextComes() {
        assertEquals(six, ScheduledTaskClaims.occurrence(daily, newYork, six))
        assertEquals(six, ScheduledTaskClaims.occurrence(daily, newYork, six + 23 * hour))
        assertEquals(six + day, ScheduledTaskClaims.occurrence(daily, newYork, six + day))
        assertNull(ScheduledTaskClaims.occurrence(daily.withCron("@daily", "UTC", 0), newYork, six))
    }

    @Test
    fun aClaimedOccurrenceStopsEveryoneElseUntilItGoesStale() {
        val claimedAt = six + 30
        val claimed = ScheduledTaskClaims.claimed(daily, six, "desk", "Desktop (Linux)", claimedAt)
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(daily, "observer", six, six + 40))
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(claimed, "observer", six, six + 40))
        // Yesterday's claim doesn't hold today's occurrence: due times compare exactly.
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed, "observer", six + day, six + day + 40))
        // Unfinished an hour after it was made (its own `at`, not the event's created_at): claimable again.
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + hour - 1))
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + hour))
        // A claim that doesn't say when it was made can't be shown fresh.
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(daily.withClaim(ClaimTag(six, "old")), "observer", six, six + 40))
        // Finished (`ran` at or after its due time): never again.
        val done = ScheduledTaskClaims.finished(claimed, six)
        assertTrue(ScheduledTaskClaims.isFinished(done))
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(done, "observer", six, claimedAt + 5 * hour))
        // A `ran` from before this claim's due time doesn't finish it.
        assertFalse(ScheduledTaskClaims.isFinished(claimed.withRan(six - day)))
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed.withRan(six - day), "observer", six, claimedAt + hour))
        // Paused, someone else's task, no task at all, or a cron this client can't read: never run.
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(daily.withEnabled(false, 0), "observer", six, six))
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(daily.replacing(TaskTag::isTag, TaskTag.assemble("digest")), "observer", six, six))
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(daily.without(TaskTag::isTag), "observer", six, six))
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(daily.withCron("0 6 * * MON", "UTC", 0), "observer", six, six))
    }

    @Test
    fun theClaimTagCarriesWhenItWasMade() {
        val claim = ClaimTag(six, "desk", "Desktop (Linux)", six + 31)
        assertContentEquals(arrayOf("claim", "1794394800", "desk", "Desktop (Linux)", "1794394831"), claim.toTagArray())
        assertEquals(claim, ClaimTag.parse(claim.toTagArray()))
        assertContentEquals(arrayOf("claim", "1794394800", "desk"), ClaimTag(six, "desk").toTagArray())
        assertEquals(ClaimTag(six, "desk", null, 7), ClaimTag.parse(arrayOf("claim", "1794394800", "desk", "", "7")))
        assertNull(ClaimTag.parse(arrayOf("claim", "soon", "desk")))
    }

    @Test
    fun settlingRunsLosesOrReadsAgain() {
        val ours = ScheduledTaskClaims.claimed(daily, six, "desk", "Desktop (Linux)", six)
        assertEquals(ScheduledTaskClaims.Settled.OURS, ScheduledTaskClaims.settle(ours, six, "desk"))
        assertEquals(ScheduledTaskClaims.Settled.LOST, ScheduledTaskClaims.settle(ours, six, "phone"))
        // An edit that didn't see the claim (no claim, or yesterday's): back to the read.
        assertEquals(ScheduledTaskClaims.Settled.AGAIN, ScheduledTaskClaims.settle(daily.withCron("0 6 * * *", "UTC", 0), six, "desk"))
        assertEquals(ScheduledTaskClaims.Settled.AGAIN, ScheduledTaskClaims.settle(ours, six + day, "desk"))
    }

    @Test
    fun ourClaimAndAnotherOne() {
        val ours = ScheduledTaskClaims.claimed(daily, six, "desk", "Desktop (Linux)", six)
        assertTrue(ScheduledTaskClaims.isOurs(ours, six, "desk"))
        assertFalse(ScheduledTaskClaims.isOurs(ours, six, "phone"))
        assertFalse(ScheduledTaskClaims.isOurs(ours, six + day, "desk"))
        assertTrue(ScheduledTaskClaims.claimsForAnother(ours, six, "phone"))
        assertFalse(ScheduledTaskClaims.claimsForAnother(ours, six, "desk"))
        // A claim on another occurrence is nobody's business now.
        assertFalse(ScheduledTaskClaims.claimsForAnother(ours, six + day, "phone"))
    }

    @Test
    fun aSkipWritesTheClaimAndRanInOneWriteWithoutRunning() {
        val skipped = ScheduledTaskClaims.skipped(daily, six, "desk", "Desktop (Linux)", six + 45)
        assertEquals(ClaimTag(six, "desk", "Desktop (Linux)", six + 45), skipped.claim)
        assertEquals(six + 45, skipped.ran)
        // It counts as done: nobody else claims it, and nothing is missed.
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(skipped, "observer", six, six + 10 * hour))
        assertNull(skipped.missedRun(newYork, six + 10 * hour))
        // Finishing changes only `ran`.
        assertEquals(skipped.withRan(six + 99), ScheduledTaskClaims.finished(skipped, six + 99))
        assertEquals(skipped.privateTags.size, ScheduledTaskClaims.finished(skipped, six + 99).privateTags.size)
    }

    @Test
    fun nothingDueBeforeTheScheduleWasSetRunsOrIsMissed() {
        // Set at 07:00 today, after today's 06:00: today's isn't claimable, tomorrow's is.
        val setLate = daily.withCron("0 6 * * *", "America/New_York", six + hour)
        assertNull(ScheduledTaskClaims.occurrence(setLate, newYork, six + 2 * hour))
        assertEquals(six + day, ScheduledTaskClaims.occurrence(setLate, newYork, six + day + 60))
        // Nor reported missed, even with an old `ran`.
        assertNull(setLate.withRan(six - 5 * day).missedRun(newYork, six + 10 * hour))
        assertEquals(six + day, setLate.withRan(six - 5 * day).missedRun(newYork, six + day + 4 * hour))
        // A missing fourth value reads as 0.
        assertEquals(0L, ScheduledTask(emptyArray(), arrayOf(arrayOf("cron", "0 6 * * *", "UTC"))).cronSetAt)
    }

    @Test
    fun resumingRestartsTheSchedule() {
        val paused = daily.withEnabled(false, six - day)
        assertTrue(paused.paused)
        // Paused: never claimed, never missed.
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(paused, "observer", six, six))
        assertNull(paused.withRan(six - 5 * day).missedRun(newYork, six + 10 * hour))
        // Resumed at 08:00: this morning's 06:00 is not anyone's to catch up.
        val resumed = paused.withEnabled(true, six + 2 * hour)
        assertFalse(resumed.paused)
        assertEquals(six + 2 * hour, resumed.cronSetAt)
        assertNull(ScheduledTaskClaims.occurrence(resumed, newYork, six + 3 * hour))
        // Enabling what isn't paused changes nothing.
        assertEquals(daily, daily.withEnabled(true, six))
    }

    @Test
    fun cronFieldsAreWhitespaceSeparated() {
        assertEquals("0 6 * * *", CronExpression.parse(" 0\t6  * *\t* ")?.text)
        // Leading zeros are numbers too.
        assertEquals(daily.withCron("0 6 * * *", "UTC", 0).expression()!!.nextAfter(six, newYork), CronExpression.parse("000 006 * * *")!!.nextAfter(six, newYork))
        assertEquals(SimpleCron(6, 0), SimpleCron.parse("00 06 * * *"))
    }

    @Test
    fun lastDueWaitsForTheGrace() {
        assertEquals(six - day, daily.lastDue(newYork, six + 3 * hour - 1))
        assertEquals(six, daily.lastDue(newYork, six + 3 * hour))
        // Weekdays only, read on Sunday evening: Friday's.
        assertEquals(six + 2 * day, daily.withCron("0 6 * * 1-5", "America/New_York", 0).lastDue(newYork, six + 4 * day + 12 * hour))
    }

    @Test
    fun aRunIsMissedOnlyAfterTheGrace() {
        val ranYesterday = daily.withRan(six - day + 600)
        assertNull(ranYesterday.missedRun(newYork, six + 3 * hour - 1))
        assertEquals(six, ranYesterday.missedRun(newYork, six + 3 * hour))
        assertNull(daily.withRan(six + 60).missedRun(newYork, six + 10 * hour))
        // No `ran` yet, off, or an unreadable cron: no warning.
        assertNull(daily.missedRun(newYork, six + 10 * day))
        assertNull(ranYesterday.withEnabled(false, 0).missedRun(newYork, six + 10 * hour))
        assertNull(ranYesterday.withCron("0 6 * MON *", "UTC", 0).missedRun(newYork, six + 10 * hour))
    }

    @Test
    fun anHourlyTaskIsMissedThreeHoursAfterItsLastRun() {
        val hourly = daily.withCron("0 * * * *", "America/New_York", 0).withRan(six + 5)
        assertNull(hourly.missedRun(newYork, six + 3 * hour - 1))
        assertNull(hourly.missedRun(newYork, six + 3 * hour))
        assertEquals(six + hour, hourly.missedRun(newYork, six + 4 * hour))
    }

    @Test
    fun anInstallTakesUpItsOwnUnfinishedClaim() {
        val claimedAt = six + 30
        val claimed = ScheduledTaskClaims.claimed(daily, six, "desk", "Desktop (Linux)", claimedAt)
        // The app died mid-run: its own claim on this occurrence is its to run again, at once.
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 60, installId = "desk", ownRun = OwnRun.DIED))
        // Nobody else's.
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 60, installId = "phone", ownRun = OwnRun.DIED))
        // Finished: not even its own.
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(ScheduledTaskClaims.finished(claimed, six + 600), "observer", six, claimedAt + 60, installId = "desk", ownRun = OwnRun.DIED))
    }

    @Test
    fun aRunThatEndedIsNotRunAgainHere() {
        val claimedAt = six + 30
        // The run ended (it failed: no `ran`), and the app was launched again the same morning.
        val claimed = ScheduledTaskClaims.claimed(daily, six, "desk", "Desktop (Linux)", claimedAt)
        assertEquals(Decision.ATTEMPTED_HERE, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 60, installId = "desk", ownRun = OwnRun.ENDED))
        // Not even once the claim is stale, which lets another device take it.
        assertEquals(Decision.ATTEMPTED_HERE, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 2 * hour, installId = "desk", ownRun = OwnRun.ENDED))
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 2 * hour, installId = "phone"))
        // With no record, its own claim waits for the hour like anyone's: a failed run is never resumed by default.
        assertEquals(Decision.ALREADY_CLAIMED, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + 60, installId = "desk"))
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(claimed, "observer", six, claimedAt + hour, installId = "desk"))
    }

    @Test
    fun aCronWithoutAZoneIsNeverRun() {
        val noZone = daily.replacing(CronTag::isTag, arrayOf("cron", "0 6 * * *"))
        assertNull(noZone.tz)
        assertFalse(noZone.isReadable())
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(noZone, "observer", six, six))
        val blankZone = daily.replacing(CronTag::isTag, arrayOf("cron", "0 6 * * *", "", "0"))
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(blankZone, "observer", six, six))
    }
}
