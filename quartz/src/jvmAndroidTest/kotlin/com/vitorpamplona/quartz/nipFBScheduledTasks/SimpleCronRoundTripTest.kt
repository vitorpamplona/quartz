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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-zone editor's round trip, over zones × times × days around the clock changes: the
 * time and days a reader picks on their clock are what the new schedule's next run shows there,
 * confirming the shown schedule writes nothing, and a refusal means no schedule in the zone
 * could show the pick (checked by trying every minute of the day).
 */
class SimpleCronRoundTripTest {
    private val zones =
        listOf(
            "America/New_York",
            "Europe/London",
            "Asia/Tokyo",
            "Pacific/Auckland",
            "Asia/Kolkata",
            "Australia/Lord_Howe",
            "Pacific/Chatham",
            "America/Sao_Paulo",
            "UTC",
            "Pacific/Kiritimati",
            "Pacific/Pago_Pago",
        ).associateWith { ZoneIdCronZone.of(it)!! }

    private val times = listOf(0 to 0, 0 to 30, 1 to 30, 2 to 30, 5 to 45, 6 to 0, 11 to 59, 12 to 0, 18 to 15, 22 to 0, 23 to 30, 23 to 59)

    private val daySets: List<Set<Int>?> = listOf(null, setOf(1, 2, 3, 4, 5), setOf(7), setOf(6, 7), setOf(1, 3, 5))

    private val nows =
        listOf("2026-11-12T12:00:00Z", "2026-03-07T12:00:00Z", "2026-03-28T03:00:00Z", "2026-10-31T20:00:00Z", "2026-04-04T13:00:00Z", "2026-09-26T12:00:00Z")
            .map { Instant.parse(it).epochSecond }

    private fun same(
        a: SimpleCron?,
        b: SimpleCron,
    ) = a != null && a.hour == b.hour && a.minute == b.minute && a.weekdays?.takeIf { it.size < 7 } == b.weekdays?.takeIf { it.size < 7 }

    /** Whether any time on any day shift, read in [zone], shows [picked] on [viewer]'s clock. */
    private fun anyShows(
        picked: SimpleCron,
        zone: CronZone,
        viewer: CronZone,
        now: Long,
    ): Boolean =
        (-1..1).any { shift ->
            (0 until 1_440).any { m ->
                val days = picked.weekdays?.map { (it - 1 + shift).mod(7) + 1 }?.toSet()
                same(SimpleCron(m / 60, m % 60, days).seenFrom(zone, viewer, now), picked)
            }
        }

    @Test
    fun whatTheEditorShowsAfterASaveIsWhatWasPicked() {
        val failures = mutableListOf<String>()
        var cases = 0
        var refused = 0
        for (now in nows) {
            for ((zoneId, zone) in zones) {
                for ((viewerId, viewer) in zones) {
                    if (zoneId == viewerId) continue
                    val schedule = SimpleCron(6, 0, setOf(1, 2, 3, 4, 5))
                    val shown = schedule.seenFrom(zone, viewer, now)!!
                    if (schedule.movedTo(shown.hour, shown.minute, zone, viewer, now, shown.weekdays) != schedule) {
                        failures += "confirm ${Instant.ofEpochSecond(now)} $zoneId from $viewerId wrote a change"
                    }
                    for ((hour, minute) in times) {
                        for (days in daySets) {
                            cases++
                            val picked = SimpleCron(hour, minute, days)
                            val moved = schedule.movedTo(hour, minute, zone, viewer, now, days)
                            if (moved == null) {
                                refused++
                                if (anyShows(picked, zone, viewer, now)) failures += "refused ${Instant.ofEpochSecond(now)} $zoneId from $viewerId $hour:$minute $days"
                                continue
                            }
                            val back = moved.seenFrom(zone, viewer, now)
                            if (!same(back, picked)) {
                                failures += "${Instant.ofEpochSecond(now)} $zoneId from $viewerId picked $hour:$minute $days -> ${moved.toCron()} shows $back"
                            } else if (moved.movedTo(hour, minute, zone, viewer, now, days) != moved) {
                                failures += "re-picking ${moved.toCron()} ($zoneId from $viewerId) changed it"
                            }
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $cases cases failed ($refused refused):\n" + failures.take(30).joinToString("\n"))
    }

    @Test
    fun theReviewersLondonMorningBeforeTheUsChange() {
        // Saturday 7 March 2026: New York changes its clocks the next day, London three weeks later.
        val now = Instant.parse("2026-03-07T12:00:00Z").epochSecond
        val newYork = zones.getValue("America/New_York")
        val london = zones.getValue("Europe/London")
        val weekdays = SimpleCron(6, 0, setOf(1, 2, 3, 4, 5))
        // Midnight every day on London's clock: tonight's run is still 19:00 in New York.
        val daily = weekdays.movedTo(0, 0, newYork, london, now, null)!!
        assertEquals(SimpleCron(19, 0), daily)
        assertEquals(SimpleCron(0, 0), daily.seenFrom(newYork, london, now))
        // Picking it again changes nothing: no toggling between 19:00 and 20:00.
        assertEquals(daily, daily.movedTo(0, 0, newYork, london, now, null))
        // Midnight on London's weekdays: the next is Monday's, after New York's change, so 20:00 there.
        val nights = weekdays.movedTo(0, 0, newYork, london, now)!!
        assertEquals(SimpleCron(20, 0, setOf(7, 1, 2, 3, 4)), nights)
        assertEquals(SimpleCron(0, 0, setOf(1, 2, 3, 4, 5)), nights.seenFrom(newYork, london, now))
        // A daily 06:00 nudged to 23:00 on London's clock, days as they were: tonight is 18:00 in
        // New York (the old anchor, Sunday's run after the change, gave 19:00, shown as midnight).
        val evening = SimpleCron(6, 0).movedTo(23, 0, newYork, london, now)!!
        assertEquals(SimpleCron(18, 0), evening)
        assertEquals(SimpleCron(23, 0), evening.seenFrom(newYork, london, now))
    }
}
