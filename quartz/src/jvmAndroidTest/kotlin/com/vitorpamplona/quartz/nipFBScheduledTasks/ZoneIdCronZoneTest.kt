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
}
