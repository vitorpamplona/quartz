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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip77Negentropy.NegentropyServerSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [NegentropySnapshotCache]'s bounds and TTL, and [SnapshotScope]'s rule per write type. */
class NegentropySnapshotCacheTest {
    private val signer = NostrSignerSync()
    private val other = NostrSignerSync()

    private var now = 1_000L

    private fun cache(
        maxSlots: Int = 8,
        maxIds: Long = 1_000,
    ) = NegentropySnapshotCache(maxSlots, maxIds, ttlSeconds = 30, clock = { now })

    private fun storage(ids: Int): IStorage = NegentropyServerSession.sealVector(List(ids) { IdAndTime(1_000L + it, it.toString(16).padStart(64, '0')) })

    private val kind1 = listOf(Filter(kinds = listOf(1)))

    private suspend fun NegentropySnapshotCache.open(
        key: String,
        ids: Int = 1,
    ) = getOrBuild(key, kind1) { storage(ids) }

    @Test
    fun theLeastRecentlyUsedSlotGoesFirst() =
        runTest {
            val cache = cache(maxSlots = 2)
            val a = cache.open("a")
            cache.open("b")
            assertSame(a, cache.open("a"), "a hit moves a to the end")
            cache.open("c")
            assertEquals(2, cache.size)
            assertSame(a, cache.open("a"))
            assertEquals(2, cache.size)
        }

    @Test
    fun theIdsHeldTogetherAreBounded() =
        runTest {
            val cache = cache(maxIds = 5)
            val a = cache.open("a", ids = 3)
            cache.open("b", ids = 3)
            assertEquals(1, cache.size, "two snapshots of 3 ids do not fit under 5")
            assertEquals(3L, cache.idsHeld)
            assertNotSame(a, cache.open("a", ids = 3), "a was evicted")
        }

    @Test
    fun aSnapshotLargerThanTheBoundStillStaysAlone() =
        runTest {
            val cache = cache(maxIds = 5)
            cache.open("a", ids = 1)
            val big = cache.open("big", ids = 10)
            assertEquals(1, cache.size)
            assertSame(big, cache.open("big"))
        }

    @Test
    fun aSnapshotServesForTheTtlOnly() =
        runTest {
            val cache = cache()
            val a = cache.open("a")
            now += 30
            assertSame(a, cache.open("a"))
            now += 1
            assertNotSame(a, cache.open("a"))
        }

    @Test
    fun anAcceptedWriteDropsOnlyTheSnapshotsItMayChange() =
        runTest {
            val cache = cache()
            val notes = cache.getOrBuild("notes", kind1) { storage(1) }
            val reactions = cache.getOrBuild("reactions", listOf(Filter(kinds = listOf(7)))) { storage(1) }
            cache.onAccepted(note(), filterLocal = true)
            assertEquals(1, cache.size)
            assertSame(reactions, cache.getOrBuild("reactions", listOf(Filter(kinds = listOf(7)))) { storage(1) })
            assertNotSame(notes, cache.getOrBuild("notes", kind1) { storage(1) })
            cache.onAccepted(event(7), filterLocal = false)
            assertEquals(0, cache.size, "a write that is not filter-local drops everything")
            assertEquals(0L, cache.idsHeld)
        }

    @Test
    fun theCacheJudgesSearchesByTheHookItWasGiven() =
        runTest {
            val spam = listOf(Filter(kinds = listOf(1), search = "include:spam"))
            val local = NegentropySnapshotCache(8, 1_000, ttlSeconds = 30, searchIsFilterLocal = { true }, clock = { now })
            val kept = local.getOrBuild("spam", spam) { storage(1) }
            local.onAccepted(event(7), filterLocal = true)
            assertSame(kept, local.getOrBuild("spam", spam) { storage(1) })

            val plain = cache()
            val dropped = plain.getOrBuild("spam", spam) { storage(1) }
            plain.onAccepted(event(7), filterLocal = true)
            assertNotSame(dropped, plain.getOrBuild("spam", spam) { storage(1) }, "without the hook a search is dropped by every write")
        }

    // ------------------------------------------------------------------
    // SnapshotScope
    // ------------------------------------------------------------------

    private fun event(
        kind: Int,
        at: Long = 1_000,
        tags: Array<Array<String>> = emptyArray(),
        by: NostrSignerSync = signer,
    ): Event = by.sign(at, kind, tags, "")

    private fun note(at: Long = 1_000) = event(1, at)

    private fun scope(vararg filters: Filter) = SnapshotScope(filters.toList())

    @Test
    fun anInsertTouchesTheFiltersItMatches() {
        assertTrue(scope(Filter(kinds = listOf(1))).affectedBy(note()))
        assertFalse(scope(Filter(kinds = listOf(7))).affectedBy(note()))
        assertFalse(scope(Filter(kinds = listOf(1), authors = listOf(other.pubKey))).affectedBy(note()))
        assertFalse(scope(Filter(kinds = listOf(1), since = 1_001)).affectedBy(note()))
        assertFalse(scope(Filter(kinds = listOf(1), until = 999)).affectedBy(note()))
        assertTrue(scope(Filter(kinds = listOf(1), since = 1_000, until = 1_000)).affectedBy(note()), "since and until are inclusive")
        assertTrue(scope(Filter(kinds = listOf(1), limit = 1)).affectedBy(note()), "a newer match moves a limited filter's top N")
        assertFalse(scope(Filter(kinds = listOf(1), tags = mapOf("p" to listOf(other.pubKey)))).affectedBy(note()))
        assertTrue(scope(Filter(kinds = listOf(7)), Filter(kinds = listOf(1))).affectedBy(note()), "any of several filters")
    }

    @Test
    fun whereTheStoresMayMatchMoreTheScopeDoesToo() {
        assertTrue(scope(Filter(kinds = emptyList())).affectedBy(note()), "F11: an empty list may constrain nothing")
        val noDTag = event(30_023)
        assertTrue(scope(Filter(kinds = listOf(30_023), tags = mapOf("d" to listOf("")))).affectedBy(noDTag), "F05: no d tag parses as d = \"\"")
        assertTrue(scope(Filter(kinds = listOf(1), search = "zzz")).affectedBy(event(7)), "a search filter is the text engine's call")
    }

    /** A store whose `include:spam` only picks the trust view and whose text search ranks corpus-wide. */
    private val spamViewIsLocal: (Filter) -> Boolean = { it.search == "include:spam" }

    private fun localScope(vararg filters: Filter) = SnapshotScope(filters.toList(), spamViewIsLocal)

    @Test
    fun aSearchTheStoreCallsLocalIsJudgedByTheFiltersOtherFields() {
        val spam = Filter(kinds = listOf(1), search = "include:spam")
        assertFalse(localScope(spam).affectedBy(event(7)), "a reaction joins no kind-1 set, whatever the view")
        assertFalse(localScope(spam.copy(authors = listOf(other.pubKey))).affectedBy(note()), "nor does a note by an author it cannot hold")
        assertTrue(localScope(spam).affectedBy(note()), "a note may join it")
        assertTrue(localScope(Filter(kinds = listOf(0), until = 1_500, search = "include:spam")).affectedBy(event(0, at = 2_000)), "W01")
        assertTrue(localScope(spam.copy(until = 10)).affectedBy(event(5)), "D01: a kind 5 by an author it may hold")
        assertFalse(localScope(spam.copy(authors = listOf(signer.pubKey))).affectedBy(event(5, by = other)), "a kind 5 by someone it cannot hold")
    }

    @Test
    fun aSearchTheStoreDoesNotCallLocalIsTouchedByEveryWrite() {
        assertTrue(localScope(Filter(kinds = listOf(1), search = "hello")).affectedBy(event(7)))
        assertTrue(SnapshotScope(listOf(Filter(kinds = listOf(1), search = "include:spam"))).affectedBy(event(7)), "no hook: the text engine's call")
    }

    @Test
    fun oneNonLocalSearchAmongTheFiltersMakesEveryWriteTouchTheSnapshot() {
        val filters = arrayOf(Filter(kinds = listOf(1), search = "include:spam"), Filter(kinds = listOf(30_023), search = "hello"))
        assertTrue(localScope(*filters).affectedBy(event(7)))
        assertFalse(localScope(filters[0], Filter(kinds = listOf(30_023))).affectedBy(event(7)), "with a plain second filter, it is judged by fields")
    }

    @Test
    fun aReplaceableTouchesWhereverItsOldVersionMayBe() {
        val newer = event(0, at = 2_000)
        assertTrue(scope(Filter(kinds = listOf(0), until = 1_500)).affectedBy(newer), "the old version may be inside the window")
        assertTrue(scope(Filter(ids = listOf("f".repeat(64)))).affectedBy(newer), "it may be that id")
        assertFalse(scope(Filter(kinds = listOf(0), authors = listOf(other.pubKey))).affectedBy(newer))
        assertFalse(scope(Filter(kinds = listOf(1))).affectedBy(newer))
        assertTrue(scope(Filter(kinds = listOf(30_023), until = 1_500)).affectedBy(event(30_023, at = 2_000, tags = arrayOf(arrayOf("d", "x")))))
        assertFalse(scope(Filter(kinds = listOf(1), until = 1_500)).affectedBy(event(1, at = 2_000)), "a regular event replaces nothing")
    }

    @Test
    fun aDeletionOrVanishTouchesTheAuthorsEventsAndEveryGiftWrap() {
        for (kind in listOf(5, 62)) {
            val request = event(kind)
            assertTrue(scope(Filter(kinds = listOf(1), until = 10)).affectedBy(request), "kind $kind: the author's notes")
            assertTrue(scope(Filter(authors = listOf(signer.pubKey), kinds = listOf(30_023))).affectedBy(request))
            assertTrue(scope(Filter(kinds = listOf(GiftWrapEvent.KIND), tags = mapOf("p" to listOf(other.pubKey)))).affectedBy(request), "kind $kind: a wrap's owner is its recipient")
            assertFalse(scope(Filter(kinds = listOf(1), authors = listOf(other.pubKey))).affectedBy(request), "kind $kind: nobody else's notes")
        }
    }
}
