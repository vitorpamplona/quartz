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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CronExpression.nextAfter] and [CronExpression.lastAtOrBefore] against a brute-force reading of
 * every minute through `java.time`, around every clock change of 2025 and 2026 in zones with odd
 * offsets, half-hour changes, two-hour changes and changes at midnight. Kept to a few seconds.
 */
class CronBruteForceTest {
    private val zones = listOf("America/New_York", "Pacific/Auckland", "Pacific/Chatham", "Australia/Lord_Howe", "America/Santiago", "Antarctica/Troll", "Asia/Kolkata", "UTC")
    private val crons = listOf("0 6 * * *", "30 2 * * *", "*/15 2 * * *", "0,30 1,2 * * 0", "45 1 * * 1-5", "0 0 31 * *", "0 */5 * * *", "59 23 * * 6,7", "0 2 * 3,4,9,10,11 0", "5 2-3 * * *")

    /** Every instant [cron] runs at in [zone] between the two days, the way NIP-FB reads it. */
    private fun runs(
        cron: CronExpression,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): List<Long> {
        val rules = zone.rules
        val out = ArrayList<Long>()
        var d = from
        while (!d.isAfter(to)) {
            if (cron.runsOnDay(d.year, d.monthValue, d.dayOfMonth, d.dayOfWeek.value % 7)) {
                for (h in 0..23) {
                    for (m in 0..59) {
                        if (!cron.runsAt(h, m)) continue
                        val wall = LocalDateTime.of(d, LocalTime.of(h, m))
                        val offsets = rules.getValidOffsets(wall)
                        // Skipped: the jump. Repeated: the first, at the larger offset.
                        out += if (offsets.isEmpty()) rules.getTransition(wall).instant.epochSecond else wall.toEpochSecond(offsets.maxBy { it.totalSeconds })
                    }
                }
            }
            d = d.plusDays(1)
        }
        return out.distinct().sorted()
    }

    @Test
    fun matchesEveryMinuteReadThroughJavaTime() {
        val random = Random(42)
        val failures = mutableListOf<String>()
        for (id in zones) {
            val zone = ZoneId.of(id)
            val cronZone = ZoneIdCronZone(zone)
            val probes = mutableListOf<Long>()
            var transition = zone.rules.nextTransition(Instant.parse("2025-01-01T00:00:00Z"))
            while (transition != null && transition.instant.isBefore(Instant.parse("2027-01-01T00:00:00Z"))) {
                val at = transition.instant.epochSecond
                for (delta in listOf(-90_000L, -3_601L, -1_800L, -1L, 0L, 1L, 1_800L, 3_600L, 7_200L, 90_000L)) probes += at + delta
                transition = zone.rules.nextTransition(transition.instant)
            }
            repeat(8) { probes += 1_735_689_600L + random.nextLong(0, 2L * 365 * 86_400) }
            for (text in crons) {
                val cron = CronExpression.parse(text)!!
                for (t in probes) {
                    val day = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
                    val expected = runs(cron, zone, day.minusDays(3), day.plusDays(40))
                    val next = expected.firstOrNull { it > t }
                    val last = expected.lastOrNull { it <= t }
                    if (next != null && cron.nextAfter(t, cronZone) != next) failures += "$id '$text' next after ${Instant.ofEpochSecond(t)}: ${cron.nextAfter(t, cronZone)?.let(Instant::ofEpochSecond)}, expected ${Instant.ofEpochSecond(next)}"
                    // The window starts 3 days back, so a run in it before t is the last one.
                    if (last != null && cron.lastAtOrBefore(t, cronZone) != last) failures += "$id '$text' last at ${Instant.ofEpochSecond(t)}: ${cron.lastAtOrBefore(t, cronZone)?.let(Instant::ofEpochSecond)}, expected ${Instant.ofEpochSecond(last)}"
                }
            }
        }
        assertEquals(emptyList(), failures.distinct().take(20))
    }

    @Test
    fun rareDatesAreFoundYearsAhead() {
        val leap = CronExpression.parse("0 0 29 2 *")!!
        val ny = ZoneIdCronZone.of("America/New_York")!!
        val from = Instant.parse("2026-11-11T12:00:00Z").epochSecond
        assertEquals(
            "2028-02-29T00:00",
            Instant
                .ofEpochSecond(leap.nextAfter(from, ny)!!)
                .atZone(ZoneId.of("America/New_York"))
                .toLocalDateTime()
                .toString(),
        )
        assertEquals(
            "2024-02-29T00:00",
            Instant
                .ofEpochSecond(leap.lastAtOrBefore(from, ny)!!)
                .atZone(ZoneId.of("America/New_York"))
                .toLocalDateTime()
                .toString(),
        )
        // A Friday the 13th in a month list, either matching (day of month OR weekday).
        val fridays = CronExpression.parse("0 12 13 * 5")!!
        assertEquals(
            "2026-11-13T12:00",
            Instant
                .ofEpochSecond(fridays.nextAfter(from, ny)!!)
                .atZone(ZoneId.of("America/New_York"))
                .toLocalDateTime()
                .toString(),
        )
    }
}
