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

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A simple schedule read in one zone and edited from a device in another: the reviewers' cases
 * (`0 6 * * 1-5` in New York confirmed from Auckland; `0 6 * * *` in New York nudged from London
 * in the week the two zones' clock changes don't line up).
 */
class SimpleCronZonesTest {
    private val newYork = ZoneIdCronZone.of("America/New_York")!!
    private val auckland = ZoneIdCronZone.of("Pacific/Auckland")!!
    private val london = ZoneIdCronZone.of("Europe/London")!!

    private fun runs(
        cron: SimpleCron,
        zone: CronZone,
        from: Long,
        count: Int,
        on: String,
    ): List<String> {
        val expression = CronExpression.parse(cron.toCron())!!
        var at = from
        return (1..count).map {
            at = expression.nextAfter(at, zone)!!
            Instant.ofEpochSecond(at).atZone(ZoneId.of(on)).let { "${it.dayOfWeek.toString().take(3)} ${it.toLocalTime()}" }
        }
    }

    @Test
    fun weekdaysInNewYorkAreTuesdayToSaturdayInAuckland() {
        val now = Instant.parse("2026-11-11T12:00:00Z").epochSecond
        val weekdays = SimpleCron(6, 0, setOf(1, 2, 3, 4, 5))
        assertEquals(SimpleCron(0, 0, setOf(2, 3, 4, 5, 6)), weekdays.seenFrom(newYork, auckland, now))
        // Confirming the time Auckland shows changes nothing.
        assertEquals(weekdays, weekdays.movedTo(0, 0, newYork, auckland, now))
        // An hour later on Auckland's clock: 07:00 on the same weekdays in New York, not Sunday to Thursday.
        val later = weekdays.movedTo(1, 0, newYork, auckland, now)!!
        assertEquals(SimpleCron(7, 0, setOf(1, 2, 3, 4, 5)), later)
        assertEquals(listOf("THU 07:00", "FRI 07:00", "MON 07:00", "TUE 07:00", "WED 07:00", "THU 07:00"), runs(later, newYork, now, 6, "America/New_York"))
        // 23:00 on Auckland's Tuesday to Saturday is 05:00 in New York the same day: Tuesday to Saturday there.
        assertEquals(SimpleCron(5, 0, setOf(2, 3, 4, 5, 6)), weekdays.movedTo(23, 0, newYork, auckland, now))
    }

    @Test
    fun aNudgeFromLondonKeepsNewYorksClockChanges() {
        // 20 March 2026: New York is on summer time, London not yet (until the 29th).
        val now = Instant.parse("2026-03-20T12:00:00Z").epochSecond
        val daily = SimpleCron(6, 0)
        assertEquals(SimpleCron(10, 0), daily.seenFrom(newYork, london, now))
        val nudged = daily.movedTo(10, 15, newYork, london, now)!!
        assertEquals(SimpleCron(6, 15), nudged)
        // From April, still 06:15 in New York (11:15 in London), never 05:15.
        val april = Instant.parse("2026-04-01T00:00:00Z").epochSecond
        assertEquals(listOf("WED 06:15", "THU 06:15", "FRI 06:15"), runs(nudged, newYork, april, 3, "America/New_York"))
    }

    @Test
    fun theSameZoneReadsItsOwnFields() {
        val now = Instant.parse("2026-11-11T12:00:00Z").epochSecond
        val weekdays = SimpleCron(6, 30, setOf(1, 2, 3, 4, 5))
        assertEquals(weekdays, weekdays.seenFrom(newYork, newYork, now))
        assertEquals(SimpleCron(7, 0, setOf(1, 2, 3, 4, 5)), weekdays.movedTo(7, 0, newYork, newYork, now))
    }

    @Test
    fun aRunTheSpringGapMovedIsNotTheOneShown() {
        // 02:30 every day in New York; on 8 March 2026 it runs at 03:00, the jump.
        val night = SimpleCron(2, 30)
        val saturday = Instant.parse("2026-03-07T12:00:00Z").epochSecond
        val run = night.unmovedRun(saturday, newYork)!!
        assertEquals(
            "2026-03-09T02:30",
            Instant
                .ofEpochSecond(run)
                .atZone(ZoneId.of("America/New_York"))
                .toLocalDateTime()
                .toString(),
        )
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochSecond(run).atZone(ZoneId.of("America/New_York")).dayOfWeek)
    }

    @Test
    fun aTimeLondonSkipsCantBeSet() {
        // 29 March 2026 in London: 01:00 jumps to 02:00. Saturday 21:30 in New York is 02:30 on Sunday there.
        val now = Instant.parse("2026-03-28T12:00:00Z").epochSecond
        val evening = SimpleCron(21, 30, setOf(6))
        assertEquals(SimpleCron(2, 30, setOf(7)), evening.seenFrom(newYork, london, now))
        // 01:45 never happens in London that Sunday: nothing to move it to.
        assertNull(evening.movedTo(1, 45, newYork, london, now))
        assertEquals(SimpleCron(21, 0, setOf(6)), evening.movedTo(2, 0, newYork, london, now))
    }
}
