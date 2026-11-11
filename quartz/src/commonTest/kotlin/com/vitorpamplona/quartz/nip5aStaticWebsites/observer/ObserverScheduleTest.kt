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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipFBScheduledTasks.CronZone
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverScheduleTest {
    @Test
    fun theMorningPaperIsTheObserverTask() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val fresh = ObserverSchedule.new("Europe/Lisbon")
            assertEquals("observer", fresh.task)
            assertFalse(fresh.enabled)
            assertEquals("Europe/Lisbon", fresh.tz)
            // Only `d` is public.
            assertEquals(1, fresh.publicTags.size)
            val older: ScheduledTaskEvent = signer.sign(ObserverSchedule.build(fresh, signer, previous = null, now = 100))
            val newer: ScheduledTaskEvent = signer.sign(ObserverSchedule.build(fresh.withEnabled(true, 0), signer, previous = older, now = 50))
            assertEquals(101, newer.createdAt)
            assertEquals(ObserverSchedule.D_TAG, newer.dTag())
            assertTrue(ObserverSchedule.isObserverSchedule(newer))
            val other: ScheduledTaskEvent = signer.sign(ScheduledTaskEvent.build("digest", CronTag("0 8 * * 0", "UTC"), signer, dTag = "digest", createdAt = 500))
            assertFalse(ObserverSchedule.isObserverSchedule(other))
            assertEquals(ObserverSchedule.address(signer.pubKey), newer.address())
            assertEquals(listOf(ObserverSchedule.D_TAG), ObserverSchedule.filter(signer.pubKey).tags?.get("d"))
        }

    // New York in November: UTC-5. Wednesday 2026-11-11, 06:00 there is 11:00 UTC.
    private val newYork = CronZone.fixed(-5 * 3_600)
    private val hours = 3_600L
    private val sixToday = 1_794_394_800L

    @Test
    fun anEditionCountsForTheOccurrenceOfItsCalendarDay() {
        // A manual print at 05:00 today is this morning's paper: the 06:00 run skips.
        assertTrue(ObserverSite.printedFor(sixToday - hours, null, sixToday, newYork))
        // A catch-up at 10:31 yesterday is yesterday's, however near 20 hours it is.
        assertFalse(ObserverSite.printedFor(sixToday - 24 * hours + 4 * hours + 31 * 60, null, sixToday, newYork))
        // 21:00 last night is yesterday's too.
        assertFalse(ObserverSite.printedFor(sixToday - 9 * hours, null, sixToday, newYork))
        // Later today (a clock running ahead, or a second print) is still today's.
        assertTrue(ObserverSite.printedFor(sixToday + 17 * hours + 59 * 60, null, sixToday, newYork))
        assertFalse(ObserverSite.printedFor(sixToday + 18 * hours, null, sixToday, newYork))
        // No print time: the entry's UTC day against the occurrence's UTC date.
        assertTrue(ObserverSite.printedFor(null, "2026-11-11", sixToday, newYork))
        assertFalse(ObserverSite.printedFor(null, "2026-11-10", sixToday, newYork))
        assertFalse(ObserverSite.printedFor(null, null, sixToday, newYork))
    }

    @Test
    fun aWeeklyCatchUpOnSundayEveningIsNotMondays() {
        // `0 6 * * 1`: Monday 2026-11-16 06:00 in New York. Caught up on Sunday at 19:00 there.
        val monday = sixToday + 5 * 24 * hours
        val sundayEvening = monday - 11 * hours
        assertFalse(ObserverSite.printedFor(sundayEvening, "2026-11-16", monday, newYork))
        // An entry without a print time dated Monday in UTC counts.
        assertTrue(ObserverSite.printedFor(null, "2026-11-16", monday, newYork))
    }

    @Test
    fun alreadyPrintedLooksAtEveryEdition() {
        val yesterday = ObserverEdition("2026-11-10", "/2026-11-10.html", "a".repeat(64), printedAt = sixToday - 19 * hours)
        val todayNoTime = ObserverEdition("2026-11-11", "/2026-11-11.html", "b".repeat(64))
        assertFalse(ObserverSite.alreadyPrinted(listOf(yesterday), sixToday, newYork))
        assertTrue(ObserverSite.alreadyPrinted(listOf(yesterday, todayNoTime), sixToday, newYork))
        assertFalse(ObserverSite.alreadyPrinted(emptyList(), sixToday, newYork))
    }

    @Test
    fun aRewriteDropsAPublicClientTag() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val withClient = ScheduledTask(arrayOf(arrayOf("d", "observer"), arrayOf("client", "Amethyst")), arrayOf(arrayOf("task", "observer"), arrayOf("client", "Private")))
            val event: ScheduledTaskEvent = signer.sign(ObserverSchedule.build(withClient, signer, previous = null, now = 100))
            assertTrue(event.tags.none { it[0] == "client" })
            assertEquals("Private", event.task(signer)!!.privateTags.first { it[0] == "client" }[1])
        }
}
