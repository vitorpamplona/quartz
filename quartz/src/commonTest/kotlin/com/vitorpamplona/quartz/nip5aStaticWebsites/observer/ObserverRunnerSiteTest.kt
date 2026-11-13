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
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.CronZone
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObserverRunnerSiteTest {
    private val reader = NostrSignerInternal(KeyPair())
    private val assistant = NostrSignerInternal(KeyPair())
    private val stranger = NostrSignerInternal(KeyPair())

    // New York in November: UTC-5. Wednesday 2026-11-11, 06:00 there is 11:00 UTC.
    private val newYork = CronZone.fixed(-5 * 3_600)
    private val hours = 3_600L
    private val sixToday = 1_794_394_800L

    private val edition = ObserverEdition("2026-11-11", "/2026-11-11.html", "a".repeat(64), printedAt = sixToday + 60)

    private suspend fun site(
        by: NostrSignerInternal,
        forReader: String?,
        createdAt: Long = sixToday,
        d: String = ObserverSite.D_TAG,
    ): NamedSiteEvent {
        val template =
            if (d == ObserverSite.D_TAG) {
                ObserverSite.build(listOf(edition), "b".repeat(64), "c".repeat(64), "Vitor", emptyList(), createdAt, forReader = forReader)
            } else {
                NamedSiteEvent.build(d, emptyList(), createdAt = createdAt)
            }
        return by.sign(template)
    }

    @Test
    fun theRunnersSiteTagsTheReader() =
        runTest {
            val own = site(reader, forReader = null)
            assertTrue(own.tags.none { it[0] == "p" })
            assertNull(ObserverSite.siteReader(own))
            val forReader = site(assistant, forReader = reader.pubKey)
            assertEquals(assistant.pubKey, forReader.pubKey)
            assertEquals(1, forReader.tags.count { it[0] == "p" })
            assertEquals(reader.pubKey, ObserverSite.siteReader(forReader))
            // Same layout as the reader's own.
            assertEquals(own.paths().map { it.path to it.hash }, forReader.paths().map { it.path to it.hash })
            assertEquals(own.title(), forReader.title())
        }

    @Test
    fun theFilterAsksForTheRunnersSiteTaggingTheReader() {
        val filter = ObserverSite.runnerSiteFilter(assistant.pubKey, reader.pubKey)
        assertEquals(listOf(NamedSiteEvent.KIND), filter.kinds)
        assertEquals(listOf(assistant.pubKey), filter.authors)
        assertEquals(listOf(ObserverSite.D_TAG), filter.tags?.get("d"))
        assertEquals(listOf(reader.pubKey), filter.tags?.get("p"))
    }

    @Test
    fun onlyTheNamedRunnersSiteForThisReaderCounts() =
        runTest {
            val good = site(assistant, forReader = reader.pubKey)
            assertTrue(ObserverSite.isRunnerSiteFor(good, assistant.pubKey, reader.pubKey))
            // Any other key tagging the reader is ignored.
            assertFalse(ObserverSite.isRunnerSiteFor(site(stranger, forReader = reader.pubKey), assistant.pubKey, reader.pubKey))
            // The runner's site for someone else, or without a `p`.
            assertFalse(ObserverSite.isRunnerSiteFor(site(assistant, forReader = stranger.pubKey), assistant.pubKey, reader.pubKey))
            assertFalse(ObserverSite.isRunnerSiteFor(site(assistant, forReader = null), assistant.pubKey, reader.pubKey))
            // Another named site of the runner.
            assertFalse(ObserverSite.isRunnerSiteFor(site(assistant, forReader = reader.pubKey, d = "blog"), assistant.pubKey, reader.pubKey))

            val newer = site(assistant, forReader = reader.pubKey, createdAt = sixToday + 10)
            val all: List<Event> = listOf(site(stranger, reader.pubKey, sixToday + 99), good, newer)
            assertEquals(newer, ObserverSite.runnerSite(all, assistant.pubKey, reader.pubKey))
            assertNull(ObserverSite.runnerSite(listOf(site(stranger, reader.pubKey)), assistant.pubKey, reader.pubKey))
        }

    @Test
    fun theRunnersBudgetCountsItsPTag() {
        val many = (1..9).map { ObserverEdition("2026-11-0$it", "/2026-11-0$it.html", "$it".repeat(64), printedAt = sixToday - it * 86_400L) }
        val own = ObserverSite.keepWithinBudget(many, reader.pubKey, "Vitor", emptyList(), budgetBytes = 100_000, maxTags = 14, createdAt = sixToday)
        val forReader = ObserverSite.keepWithinBudget(many, assistant.pubKey, "Vitor", emptyList(), budgetBytes = 100_000, maxTags = 14, createdAt = sixToday, forReader = reader.pubKey)
        assertEquals(own.size - 1, forReader.size)
    }

    @Test
    fun alreadyPrintedReadsBothSites() {
        val yesterday = ObserverEdition("2026-11-10", "/2026-11-10.html", "a".repeat(64), printedAt = sixToday - 19 * hours)
        val manualThisMorning = ObserverEdition("2026-11-11", "/2026-11-11.html", "b".repeat(64), printedAt = sixToday - hours)
        assertFalse(ObserverSite.alreadyPrinted(listOf(yesterday), emptyList(), sixToday, newYork))
        // A manual print on the reader's own site covers the runner's run, and the other way round.
        assertTrue(ObserverSite.alreadyPrinted(listOf(yesterday, manualThisMorning), listOf(yesterday), sixToday, newYork))
        assertTrue(ObserverSite.alreadyPrinted(listOf(yesterday), listOf(manualThisMorning), sixToday, newYork))
    }

    @Test
    fun lastPrintedIsTheNewestPrintedAtAcrossBoth() {
        val own = ObserverEdition("2026-11-10", "/2026-11-10.html", "a".repeat(64), printedAt = sixToday - 19 * hours)
        val runner = ObserverEdition("2026-11-11", "/2026-11-11.html", "b".repeat(64), printedAt = sixToday + 60)
        val undated = ObserverEdition("2026-11-12", "/2026-11-12.html", "c".repeat(64))
        assertEquals(ObserverSite.LastPrint(sixToday + 60, true, runner), ObserverSite.lastPrinted(listOf(own, undated), listOf(runner)))
        assertEquals(ObserverSite.LastPrint(sixToday - 19 * hours, false, own), ObserverSite.lastPrinted(listOf(own), listOf(undated)))
        // A tie goes to the reader's own.
        assertEquals(false, ObserverSite.lastPrinted(listOf(runner), listOf(runner))?.byRunner)
        assertNull(ObserverSite.lastPrinted(listOf(undated), emptyList()))
        assertNull(ObserverSite.lastPrinted(emptyList()))
    }

    @Test
    fun theMissedRunWarningReadsTheSitesWhenARunnerIsNamed() {
        val daily = ScheduledTask.create("observer", "observer", "0 6 * * *", "America/New_York", setAt = 0)
        val run = daily.withRunner(assistant.pubKey, 0)
        val yesterday = ObserverEdition("2026-11-10", "/2026-11-10.html", "a".repeat(64), printedAt = sixToday - 24 * hours + 60)
        val today = ObserverEdition("2026-11-11", "/2026-11-11.html", "b".repeat(64), printedAt = sixToday + 60)

        // Within the grace, up to exactly three hours: no warning yet.
        assertNull(ObserverSite.missedRun(run, emptyList(), listOf(yesterday), newYork, sixToday + 3 * hours))
        // More than three hours on, nothing on either site for this morning: missed, even with nothing ever printed.
        assertEquals(sixToday, ObserverSite.missedRun(run, emptyList(), listOf(yesterday), newYork, sixToday + 3 * hours + 1))
        assertEquals(sixToday, ObserverSite.missedRun(run, emptyList(), emptyList(), newYork, sixToday + 3 * hours + 1))
        // Printed on either site: fine.
        assertNull(ObserverSite.missedRun(run, emptyList(), listOf(today), newYork, sixToday + 3 * hours + 1))
        assertNull(ObserverSite.missedRun(run, listOf(today), emptyList(), newYork, sixToday + 3 * hours + 1))
        // A device's `ran` from before the runner was named still counts.
        assertNull(ObserverSite.missedRun(run.withRan(sixToday + 60), emptyList(), emptyList(), newYork, sixToday + 3 * hours + 1))
        // Paused, or nothing due since the schedule was set: no warning.
        assertNull(ObserverSite.missedRun(run.withEnabled(false, 0), emptyList(), emptyList(), newYork, sixToday + 3 * hours + 1))
        assertNull(ObserverSite.missedRun(run.withCron("0 6 * * *", "America/New_York", sixToday + 1), emptyList(), emptyList(), newYork, sixToday + 3 * hours + 1))

        // Without a runner it is NIP-FB's, from `ran`, whatever the sites say.
        val ranYesterday = daily.withRan(sixToday - 24 * hours + 600)
        assertEquals(sixToday, ObserverSite.missedRun(ranYesterday, listOf(today), listOf(today), newYork, sixToday + 3 * hours + 1))
        assertNull(ObserverSite.missedRun(daily, emptyList(), emptyList(), newYork, sixToday + 3 * hours + 1))
    }
}
