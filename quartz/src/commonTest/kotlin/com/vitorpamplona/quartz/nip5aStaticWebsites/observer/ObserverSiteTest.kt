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
import com.vitorpamplona.quartz.nip5aStaticWebsites.SiteAggregateHash
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObserverSiteTest {
    private fun hash(n: Int) = n.toString(16).padStart(64, '0')

    private fun edition(
        day: String,
        n: Int,
        headline: String? = null,
    ) = ObserverEdition(day, ObserverSite.dayPath(day), hash(n), headline = headline)

    private fun days(count: Int): List<ObserverEdition> =
        (0 until count).map { i ->
            // 2026-01-01 plus i days, through the same UTC conversion the producers use.
            val day = ObserverSite.utcDay(1_767_225_600L + i * 86_400L)
            edition(day, i + 1)
        }

    @Test
    fun utcDayIsTheCivilDate() {
        assertEquals("1970-01-01", ObserverSite.utcDay(0))
        assertEquals("2026-11-11", ObserverSite.utcDay(1_794_355_200L))
        assertEquals("2026-11-10", ObserverSite.utcDay(1_794_355_199L))
        assertEquals("2024-02-29", ObserverSite.utcDay(1_709_164_800L))
        assertEquals("1969-12-31", ObserverSite.utcDay(-1))
    }

    @Test
    fun codecRoundTripsAndWritesNewestFirst() {
        val index =
            ObserverEditions(
                title = "Vitor's Observer",
                editions =
                    listOf(
                        edition("2026-11-10", 1),
                        ObserverEdition("2026-11-11", "/2026-11-11.html", hash(2), "Twelve relays", "D35280", 1_794_355_200L, 412, 3, "anthropic/claude-sonnet-4.5"),
                    ),
            )
        val text = ObserverEditionsCodec.encode(index)
        assertTrue(text.indexOf("2026-11-11") < text.indexOf("2026-11-10"))
        assertFalse(text.contains("null"))
        val back = ObserverEditionsCodec.decode(text)!!
        assertEquals("Vitor's Observer", back.title)
        assertEquals(listOf("2026-11-11", "2026-11-10"), back.editions.map { it.day })
        assertEquals(412, back.editions.first().posts)
    }

    @Test
    fun decodeIgnoresUnknownFieldsAndDropsBadEntries() {
        val text =
            """{"v":1,"future":true,"editions":[
              {"day":"2026-11-11","path":"/2026-11-11.html","sha256":"${hash(2)}","mood":"good"},
              {"day":"2026-11-10","path":"/../etc.html","sha256":"${hash(1)}"},
              {"day":"2026-11-09","path":"/2026-11-09.html","sha256":"nope"},
              {"path":"/2026-11-08.html","sha256":"${hash(3)}"}
            ]}"""
        val index = ObserverEditionsCodec.decode(text)!!
        assertEquals(listOf("2026-11-11"), index.editions.map { it.day })
        assertNull(ObserverEditionsCodec.decode("""{"v":2,"editions":[]}"""))
        assertNull(ObserverEditionsCodec.decode("not json"))
    }

    @Test
    fun rebuildsTheListFromDatedPathTags() {
        val paths =
            listOf(
                PathTag("/index.html", hash(2)),
                PathTag("/2026-11-10.html", hash(1)),
                PathTag("/2026-11-11.html", hash(2)),
                PathTag("/archive.html", hash(8)),
                PathTag("/editions.json", hash(9)),
            )
        val rebuilt = ObserverSite.editionsFromPaths(paths)
        assertEquals(listOf("2026-11-11", "2026-11-10"), rebuilt.map { it.day })
        assertNull(rebuilt.first().headline)
    }

    @Test
    fun aSecondPrintTheSameDayReplacesTheFirst() {
        val list = ObserverSite.upsert(listOf(edition("2026-11-10", 1), edition("2026-11-11", 2)), edition("2026-11-11", 3, "Reprint"))
        assertEquals(listOf("2026-11-11", "2026-11-10"), list.map { it.day })
        assertEquals(hash(3), list.first().sha256)
    }

    @Test
    fun keepsTheNewestNinety() {
        val kept = ObserverSite.rollOff(days(120)) { true }
        assertEquals(90, kept.size)
        assertEquals(days(120).maxOf { it.day }, kept.first().day)
        assertEquals(days(120).sortedByDescending { it.day }[89].day, kept.last().day)
    }

    @Test
    fun theByteBudgetDropsTheOldestFirst() {
        val author = "a".repeat(64)
        val all = days(90)
        val everything = ObserverSite.build(all, hash(98), hash(99), "Vitor", listOf("https://blossom.example"))
        val fullSize = ObserverSite.signedSize(everything, author)
        val budget = fullSize - 1_000
        val kept = ObserverSite.keepWithinBudget(all, author, "Vitor", listOf("https://blossom.example"), budget)
        assertTrue(kept.size < 90)
        assertEquals(all.maxOf { it.day }, kept.first().day)
        assertTrue(ObserverSite.signedSize(ObserverSite.build(kept, hash(98), hash(99), "Vitor", listOf("https://blossom.example")), author) <= budget)
        // The newest edition always survives, however small the budget.
        assertEquals(1, ObserverSite.keepWithinBudget(all, author, "Vitor", emptyList(), budgetBytes = 10).size)
    }

    @Test
    fun budgetComesFromTheSmallestRelayLimit() {
        assertEquals(ObserverSite.DEFAULT_BUDGET_BYTES, ObserverSite.budget(listOf(null, null)))
        assertEquals(64_000 - ObserverSite.ENVELOPE_BYTES, ObserverSite.budget(listOf(null, 128_000, 64_000)))
    }

    @Test
    fun theManifestServesTheNewestAtIndexAndCarriesTheAggregate() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val editions = listOf(edition("2026-11-10", 1), edition("2026-11-11", 2))
            val template = ObserverSite.build(editions, hash(8), hash(9), "Vitor", listOf("https://blossom.example"))
            val site = assertIs<NamedSiteEvent>(Event.fromJson(signer.sign(template).toJson()))
            assertEquals(ObserverSite.D_TAG, site.identifier())
            assertEquals("Vitor's Observer", site.title())
            val paths = site.paths()
            assertEquals(hash(2), paths.first { it.path == "/index.html" }.hash)
            assertEquals(setOf("/index.html", "/2026-11-11.html", "/2026-11-10.html", "/archive.html", "/editions.json"), paths.map { it.path }.toSet())
            assertEquals(SiteAggregateHash.compute(paths), site.tags.first { it[0] == "x" }[1])
            assertEquals(listOf("https://blossom.example"), site.servers())
            assertEquals(ObserverSite.address(signer.pubKey), site.address())
        }

    @Test
    fun nextCreatedAtNeverLosesToThePreviousManifest() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val previous = signer.sign(ObserverSite.build(listOf(edition("2026-11-10", 1)), hash(8), hash(9), null, emptyList(), createdAt = 2_000))
            assertEquals(2_001, ObserverSite.nextCreatedAt(previous, now = 1_500))
            assertEquals(3_000, ObserverSite.nextCreatedAt(previous, now = 3_000))
            assertEquals(1_500, ObserverSite.nextCreatedAt(null, now = 1_500))
        }

    @Test
    fun theArchiveEscapesEverythingAndLinksRelatively() {
        val html = ObserverArchive.html("<b>Mallory</b>'s Observer", listOf(edition("2026-11-11", 2, "<script>alert(1)</script> & \"more\""), edition("2026-11-10", 1)))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<b>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;more&quot;"))
        assertTrue(html.contains("href=\"2026-11-11.html\""))
        assertTrue(html.indexOf("2026-11-11") < html.indexOf("2026-11-10"))
        assertFalse(html.contains("http"))
    }

    @Test
    fun titlesFallBackWithoutAName() {
        assertEquals(ObserverSite.DEFAULT_TITLE, ObserverSite.title("  "))
        assertEquals("Vitor's Observer", ObserverSite.title("Vitor"))
    }

    @Test
    fun onlyRealCalendarDatesAreDays() {
        assertTrue(ObserverSite.isDay("2026-11-11"))
        assertTrue(ObserverSite.isDay("2024-02-29"))
        assertTrue(ObserverSite.isDay("2000-02-29"))
        assertFalse(ObserverSite.isDay("2026-13-45"))
        assertFalse(ObserverSite.isDay("2026-02-29"))
        assertFalse(ObserverSite.isDay("1900-02-29"))
        assertFalse(ObserverSite.isDay("2026-04-31"))
        assertFalse(ObserverSite.isDay("2026-00-10"))
        assertFalse(ObserverSite.isDay("2026-11-00"))
        assertNull(ObserverSite.dayOfPath("/2026-13-45.html"))
        assertEquals(emptyList(), ObserverSite.editionsFromPaths(listOf(PathTag("/2026-02-30.html", hash(1)))))
        assertNull(ObserverEditionsCodec.decode("""{"v":1,"editions":[{"day":"2026-02-30","path":"/2026-02-30.html","sha256":"${hash(1)}"}]}""")!!.editions.firstOrNull())
    }

    @Test
    fun aFutureDayNeverBecomesTheIndex() {
        val now = 1_794_355_200L // 2026-11-11T00:00Z
        val editions = listOf(edition("2026-11-11", 1), edition("2027-01-01", 2))
        val template = ObserverSite.build(editions, hash(8), hash(9), null, emptyList(), createdAt = now)
        val index = template.tags.first { it[0] == "path" && it[1] == ObserverSite.INDEX_PATH }
        assertEquals(hash(1), index[2])
        // Tomorrow is allowed, for a producer whose clock runs ahead.
        assertEquals("2026-11-12", ObserverSite.latestDay(now))
    }

    @Test
    fun todaysEditionSurvivesRollOffEvenBehindAFutureDay() {
        val all = days(120) + edition("2099-01-01", 999)
        val today = days(120).first().day
        val kept = ObserverSite.rollOff(all, keep = 10, protect = today) { it.size <= 3 }
        assertTrue(kept.any { it.day == today })
        assertEquals(3, kept.size)
    }

    @Test
    fun theTagBudgetDropsTheOldestFirst() {
        val author = "a".repeat(64)
        val servers = (1..5).map { "https://blossom$it.example" }
        val kept = ObserverSite.keepWithinBudget(days(90), author, "Vitor", servers, budgetBytes = 1_000_000, maxTags = 40)
        val template = ObserverSite.build(kept, hash(98), hash(99), "Vitor", servers)
        assertTrue(template.tags.size <= 40)
        assertEquals(days(90).maxOf { it.day }, kept.first().day)
        assertEquals(40, ObserverSite.tagBudget(listOf(null, 50, 40)))
        assertEquals(ObserverSite.DEFAULT_MAX_TAGS, ObserverSite.tagBudget(listOf(null, 0)))
    }

    @Test
    fun indexEntriesDescribeOnlyTheBytesThePathsServe() {
        val paths =
            listOf(
                PathTag("/2026-11-11.html", hash(2).uppercase()),
                PathTag("/2026-11-10.html", hash(1)),
            )
        val indexed =
            listOf(
                edition("2026-11-11", 2, "Matches"),
                edition("2026-11-10", 7, "Other bytes"),
                edition("2026-11-09", 3, "Rolled off by a racing producer"),
            )
        val merged = ObserverSite.mergeEditions(indexed, ObserverSite.editionsFromPaths(paths))
        assertEquals(listOf("2026-11-11", "2026-11-10"), merged.map { it.day })
        assertEquals("Matches", merged[0].headline)
        assertEquals(hash(2), merged[0].sha256)
        assertNull(merged[1].headline)
        assertEquals(hash(1), merged[1].sha256)
    }

    @Test
    fun decodeIsLenientPerFieldAndAcceptsAFloatVersion() {
        val text =
            """{"v":1.0,"title":7,"editions":[
              {"day":"2026-11-11","path":"/2026-11-11.html","sha256":"${hash(2).uppercase()}","posts":"412","headline":{"x":1},"quotesVerified":3},
              {"day":"2026-11-11","path":"/2026-11-11.html","sha256":"${hash(5)}"}
            ]}"""
        val index = ObserverEditionsCodec.decode(text)!!
        assertNull(index.title)
        assertEquals(1, index.editions.size)
        val only = index.editions.single()
        assertEquals(hash(2), only.sha256)
        assertNull(only.posts)
        assertNull(only.headline)
        assertEquals(3, only.quotesVerified)
        assertNull(ObserverEditionsCodec.decode("""{"v":"1","editions":[]}"""))
    }

    @Test
    fun theArchiveLinksFromTheDayAndKeepsMultilineHeadlines() {
        val odd = ObserverEdition("2026-11-11", "/elsewhere.html", hash(2), headline = "Line one\n|Line two")
        val html = ObserverArchive.html("T", listOf(odd))
        assertTrue(html.contains("href=\"2026-11-11.html\""))
        assertFalse(html.contains("elsewhere"))
        assertTrue(html.contains("Line one\n|Line two"))
    }
}
