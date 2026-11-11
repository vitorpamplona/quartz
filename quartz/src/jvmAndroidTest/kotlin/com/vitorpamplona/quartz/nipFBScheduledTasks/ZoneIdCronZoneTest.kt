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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoneIdCronZoneTest {
    private val newYork = ZoneIdCronZone.of("America/New_York")!!
    private val ny = ZoneId.of("America/New_York")

    private fun at(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), ny).toEpochSecond()

    private fun local(sec: Long) =
        Instant
            .ofEpochSecond(sec)
            .atZone(ny)
            .toLocalDateTime()
            .toString()

    @Test
    fun readsTheWallClockOfTheZone() {
        val cron = CronExpression.parse("0 6 * * 1-5")!!
        // Wednesday 11 November 2026, 07:00 in New York: next is Thursday 06:00 there.
        assertEquals("2026-11-12T06:00", local(cron.nextAfter(at("2026-11-11T07:00"), newYork)!!))
        // Friday after 06:00: Monday.
        assertEquals("2026-11-16T06:00", local(cron.nextAfter(at("2026-11-13T06:00"), newYork)!!))
        assertEquals("2026-11-13T06:00", local(cron.lastAtOrBefore(at("2026-11-15T12:00"), newYork)!!))
    }

    @Test
    fun theSpringGapRunsAtTheTransition() {
        // 8 March 2026: 02:00 jumps to 03:00.
        val cron = CronExpression.parse("30 2 * * *")!!
        val run = cron.nextAfter(at("2026-03-07T12:00"), newYork)!!
        assertEquals("2026-03-08T03:00", local(run))
        assertEquals("2026-03-09T02:30", local(cron.nextAfter(run, newYork)!!))
    }

    @Test
    fun theAutumnRepeatRunsOnce() {
        // 1 November 2026: 01:00-02:00 happens twice.
        val cron = CronExpression.parse("30 1 * * *")!!
        val first = cron.nextAfter(at("2026-10-31T12:00"), newYork)!!
        assertEquals(ZonedDateTime.of(LocalDateTime.parse("2026-11-01T01:30"), ny).withEarlierOffsetAtOverlap().toEpochSecond(), first)
        assertEquals("2026-11-02T01:30", local(cron.nextAfter(first, newYork)!!))
    }

    @Test
    fun anUnknownZoneIsNull() {
        assertNull(ZoneIdCronZone.of("Mars/Olympus_Mons"))
    }

    private fun localIn(
        zone: ZoneId,
        sec: Long,
    ) = Instant
        .ofEpochSecond(sec)
        .atZone(zone)
        .toLocalDateTime()
        .toString()

    private fun at(
        zone: ZoneId,
        text: String,
    ) = ZonedDateTime.of(LocalDateTime.parse(text), zone).toEpochSecond()

    @Test
    fun aucklandSpringGapRunsAtTheTransition() {
        // 27 September 2026: Auckland (+12, a day ahead of UTC) jumps from 02:00 to 03:00.
        val akl = ZoneId.of("Pacific/Auckland")
        val zone = ZoneIdCronZone(akl)
        val cron = CronExpression.parse("30 2 * * *")!!
        val run = cron.nextAfter(at(akl, "2026-09-26T12:00"), zone)!!
        assertEquals(1_790_431_200L, run) // 2026-09-26T14:00:00Z, the transition
        assertEquals("2026-09-27T03:00", localIn(akl, run))
        assertEquals("2026-09-28T02:30", localIn(akl, cron.nextAfter(run, zone)!!))
        // The last one due before the gap day is the 26th's.
        assertEquals("2026-09-26T02:30", localIn(akl, cron.lastAtOrBefore(run - 1, zone)!!))
    }

    @Test
    fun aucklandAutumnRepeatRunsTheFirstOnce() {
        // 5 April 2026: 02:00-03:00 happens twice in Auckland.
        val akl = ZoneId.of("Pacific/Auckland")
        val zone = ZoneIdCronZone(akl)
        val cron = CronExpression.parse("30 2 * * *")!!
        val first = cron.nextAfter(at(akl, "2026-04-04T12:00"), zone)!!
        assertEquals(ZonedDateTime.of(LocalDateTime.parse("2026-04-05T02:30"), akl).withEarlierOffsetAtOverlap().toEpochSecond(), first)
        assertEquals(13 * 3_600, zone.offsetSecondsAt(first))
        assertEquals("2026-04-06T02:30", localIn(akl, cron.nextAfter(first, zone)!!))
        // Inside the repeated hour, the last occurrence is still the first 02:30.
        assertEquals(first, cron.lastAtOrBefore(first + 3_600 + 600, zone))
    }

    @Test
    fun chathamKeepsItsQuarterHourOffset() {
        val cht = ZoneId.of("Pacific/Chatham")
        val zone = ZoneIdCronZone(cht)
        val daily = CronExpression.parse("0 6 * * *")!!
        // +13:45 in November.
        val run = daily.nextAfter(at(cht, "2026-11-11T00:00"), zone)!!
        assertEquals(at(cht, "2026-11-11T06:00"), run)
        assertEquals("2026-11-10T16:15:00Z", Instant.ofEpochSecond(run).toString())
        // 27 September 2026: 02:45 jumps to 03:45, so 03:00 runs at the transition.
        val skipped = CronExpression.parse("0 3 * * *")!!.nextAfter(at(cht, "2026-09-26T12:00"), zone)!!
        assertEquals("2026-09-27T03:45", localIn(cht, skipped))
        assertEquals(1_790_431_200L, skipped)
    }

    @Test
    fun onlyNamedZonesAreZones() {
        assertNull(ZoneIdCronZone.of("Mars/Olympus_Mons"))
        assertNull(ZoneIdCronZone.of(""))
        assertNull(ZoneIdCronZone.of("+05:00"))
        assertNull(ZoneIdCronZone.of("GMT+5"))
        assertNull(ZoneIdCronZone.of("GMT-3"))
        assertNull(ZoneIdCronZone.of("UTC+5"))
        assertNull(ZoneIdCronZone.of("UTC-03:30"))
        assertNull(ZoneIdCronZone.of("UT+1"))
        assertNull(ZoneIdCronZone.of("-03:00"))
        assertNull(ZoneIdCronZone.of("Z"))
        assertEquals(0, ZoneIdCronZone.of("UTC")!!.offsetSecondsAt(0))
        assertEquals(0, ZoneIdCronZone.of("Etc/UTC")!!.offsetSecondsAt(0))
        assertEquals(0, ZoneIdCronZone.of("GMT")!!.offsetSecondsAt(0))
        assertEquals(-5 * 3_600, ZoneIdCronZone.of("Etc/GMT+5")!!.offsetSecondsAt(0))
    }
}
