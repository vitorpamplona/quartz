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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronExpressionTest {
    private val utc = CronZone.UTC

    // 2026-11-11T07:00:00Z, a Wednesday.
    private val wednesday7 = 1_794_380_400L
    private val hour = 3_600L
    private val day = 86_400L

    private fun cron(text: String) = assertNotNull(CronExpression.parse(text), text)

    @Test
    fun acceptsTheSubset() {
        listOf("0 6 * * *", "0 6 * * 1-5", "0 6,18 * * *", "0 */6 * * *", "0 6 1 * *", "*/15 0-6/2 1,15 1-12 0,7", "59 23 31 12 7").forEach { cron(it) }
    }

    @Test
    fun refusesEverythingElse() {
        listOf(
            "",
            "0 6 * *",
            "0 6 * * * *",
            "@daily",
            "0 6 * * MON",
            "0 6 * JAN *",
            "0 6 ? * *",
            "0 6 L * *",
            "0 6 * * 1#2",
            "0 6 15W * *",
            "60 6 * * *",
            "0 24 * * *",
            "0 6 0 * *",
            "0 6 * 13 *",
            "0 6 * * 8",
            "0 6 * * 5-1",
            "0 6/2 * * *",
            "*/0 6 * * *",
            "0 6 * * 1,",
        ).forEach { assertNull(CronExpression.parse(it), it) }
    }

    @Test
    fun nextAfterIsStrictlyAfter() {
        val daily = cron("0 7 * * *")
        assertEquals(wednesday7 + day, daily.nextAfter(wednesday7, utc))
        assertEquals(wednesday7, daily.nextAfter(wednesday7 - 1, utc))
        assertEquals(wednesday7, daily.lastAtOrBefore(wednesday7, utc))
        assertEquals(wednesday7 - day, daily.lastAtOrBefore(wednesday7 - 1, utc))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val weekdays = cron("0 7 * * 1-5")
        // Friday 07:00 is two days later; the next after it is Monday.
        val friday7 = wednesday7 + 2 * day
        assertEquals(friday7, weekdays.nextAfter(wednesday7 + day, utc))
        assertEquals(friday7 + 3 * day, weekdays.nextAfter(friday7, utc))
        // Sunday 7 and 0 are the same day.
        assertEquals(cron("0 7 * * 0").nextAfter(wednesday7, utc), cron("0 7 * * 7").nextAfter(wednesday7, utc))
        assertEquals(wednesday7 + 4 * day, cron("0 7 * * 7").nextAfter(wednesday7, utc))
    }

    @Test
    fun dayOfMonthOrDayOfWeekWhenBothAreRestricted() {
        // The 13th, or any Friday: Friday the 13th of November 2026 and the next Friday, the 20th.
        val either = cron("0 7 13 * 5")
        val friday13 = wednesday7 + 2 * day
        assertEquals(friday13, either.nextAfter(wednesday7, utc))
        assertEquals(friday13 + 7 * day, either.nextAfter(friday13, utc))
        // Only the day of month restricted: the 13th only, then 13 December.
        val thirteenth = cron("0 7 13 * *")
        assertEquals(friday13 + 30 * day, thirteenth.nextAfter(friday13, utc))
        // `*/1` is a restriction as written, so the OR applies: every day.
        assertEquals(wednesday7 + day, cron("0 7 */1 * 5").nextAfter(wednesday7, utc))
    }

    @Test
    fun monthBoundariesAndLeapDays() {
        // 2026-11-30 07:00 then 2026-12-01: "the 31st" skips November and lands on 31 December.
        val nov30 = wednesday7 + 19 * day
        assertEquals(nov30 + 31 * day, cron("0 7 31 * *").nextAfter(nov30, utc))
        assertEquals(nov30 + day, cron("0 7 1 * *").nextAfter(nov30, utc))
        // 29 February: 2028.
        val leap = cron("0 0 29 2 *").nextAfter(wednesday7, utc)!!
        assertEquals(1_835_395_200L, leap) // 2028-02-29T00:00:00Z
        // 30 February never comes.
        assertNull(cron("0 0 30 2 *").nextAfter(wednesday7, utc))
    }

    @Test
    fun aFixedZoneShiftsTheInstant() {
        val minusFive = CronZone.fixed(-5 * 3_600)
        // 07:00 in UTC-5 is 12:00Z.
        assertEquals(wednesday7 + 5 * hour, cron("0 7 * * *").nextAfter(wednesday7, minusFive))
        // Local day of week, not UTC's: Wednesday 23:30 in UTC-5 is Thursday 04:30Z.
        assertEquals(wednesday7 + 21 * hour + 30 * 60, cron("30 23 * * 3").nextAfter(wednesday7, minusFive))
    }

    // A zone that springs forward at 2026-03-08T07:00Z (-5 → -4) and falls back at 2026-11-01T06:00Z (-4 → -5).
    private val springAt = 1_772_953_200L
    private val fallAt = 1_793_512_800L
    private val fakeNewYork =
        CronZone { at ->
            if (at in springAt until fallAt) -4 * 3_600 else -5 * 3_600
        }

    @Test
    fun aSkippedTimeRunsAtTheTransition() {
        // 02:30 doesn't exist on 8 March: the run is at 03:00 local, the transition.
        val before = springAt - 6 * hour
        assertEquals(springAt, cron("30 2 * * *").nextAfter(before, fakeNewYork))
        // Every 15 minutes through the gap runs once at the transition, then 03:15.
        val quarter = cron("*/15 * * * *")
        val atTwo = quarter.nextAfter(springAt - 60, fakeNewYork)
        assertEquals(springAt, atTwo)
        assertEquals(springAt + 15 * 60, quarter.nextAfter(atTwo!!, fakeNewYork))
    }

    @Test
    fun aRepeatedTimeRunsOnce() {
        // 01:30 happens twice on 1 November: the first (EDT, 05:30Z), and the next run is the 2nd.
        val daily = cron("30 1 * * *")
        val first = daily.nextAfter(fallAt - 3 * hour, fakeNewYork)!!
        assertEquals(fallAt - 30 * 60, first)
        assertEquals(first + 25 * hour, daily.nextAfter(first, fakeNewYork))
        // From inside the repeated hour, the last occurrence is the first 01:30, not the second.
        assertEquals(first, daily.lastAtOrBefore(fallAt + 45 * 60, fakeNewYork))
    }

    @Test
    fun theSimpleEditorShapeRoundTrips() {
        assertEquals(SimpleCron(6, 0), SimpleCron.parse("0 6 * * *"))
        assertEquals(SimpleCron(6, 30, setOf(1, 2, 3, 4, 5)), SimpleCron.parse("30 6 * * 1-5"))
        assertEquals(SimpleCron(6, 30, setOf(6, 7)), SimpleCron.parse("30 6 * * 0,6"))
        assertEquals(SimpleCron(6, 30, setOf(7)), SimpleCron.parse("30 6 * * 7"))
        assertEquals("30 6 * * 1-5", SimpleCron(6, 30, setOf(1, 2, 3, 4, 5)).toCron())
        assertEquals("30 6 * * 0,6", SimpleCron(6, 30, setOf(6, 7)).toCron())
        assertEquals("30 6 * * 1,3", SimpleCron(6, 30, setOf(1, 3)).toCron())
        assertEquals("30 6 * * *", SimpleCron(6, 30, (1..7).toSet()).toCron())
        assertEquals("30 6 * * 0-2,5", SimpleCron(6, 30, setOf(7, 1, 2, 5)).toCron())
        // Shapes the editor couldn't show again stay read-only.
        listOf("0 6,18 * * *", "0 */6 * * *", "0 6 1 * *", "0 6 * 1 *", "*/5 6 * * *", "0 6 * * */2", "nonsense").forEach {
            assertNull(SimpleCron.parse(it), it)
        }
        assertTrue(CronExpression.isValid(SimpleCron(23, 59, setOf(7)).toCron()))
        assertFalse(CronExpression.isValid("0 6 * * MON-FRI"))
    }
}
