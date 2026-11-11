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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            assertEquals(newer, ObserverSchedule.newest(listOf<Event?>(older, null, other, newer), signer.pubKey))
            assertNull(ObserverSchedule.newest(listOf(older), "f".repeat(64)))
            assertEquals(ObserverSchedule.address(signer.pubKey), newer.address())
            assertEquals(listOf(ObserverSchedule.D_TAG), ObserverSchedule.filter(signer.pubKey).tags?.get("d"))
        }

    @Test
    fun printedTodayIsTwentyHoursOrTheUtcDay() {
        val now = 1_794_380_400L // 2026-11-11T07:00:00Z
        val hours = 3_600L
        // A manual print at 23:00 the night before (8 hours ago) is today's paper for a 7:00 run.
        assertTrue(ObserverSite.printedToday(now - 8 * hours, "2026-11-10", now))
        assertTrue(ObserverSite.printedToday(now - 20 * hours + 1, "2026-11-10", now))
        assertFalse(ObserverSite.printedToday(now - 20 * hours, "2026-11-10", now))
        // Another device's clock running ahead still counts; nonsense from next week doesn't.
        assertTrue(ObserverSite.printedToday(now + 600, "2026-11-11", now))
        assertFalse(ObserverSite.printedToday(now + 7 * 86_400, "2026-11-18", now))
        // No print time: only the UTC day says.
        assertTrue(ObserverSite.printedToday(null, "2026-11-11", now))
        assertFalse(ObserverSite.printedToday(null, "2026-11-10", now))
        assertFalse(ObserverSite.printedToday(null, null, now))
    }

    @Test
    fun alreadyPrintedLooksAtEveryEdition() {
        val now = 1_794_380_400L
        val yesterday = ObserverEdition("2026-11-10", "/2026-11-10.html", "a".repeat(64), printedAt = now - 30 * 3_600L)
        val todayNoTime = ObserverEdition("2026-11-11", "/2026-11-11.html", "b".repeat(64))
        assertFalse(ObserverSite.alreadyPrintedToday(listOf(yesterday), now))
        assertTrue(ObserverSite.alreadyPrintedToday(listOf(yesterday, todayNoTime), now))
        assertFalse(ObserverSite.alreadyPrintedToday(emptyList(), now))
    }
}
