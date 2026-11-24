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
package com.vitorpamplona.quartz.nip01Core.store.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live negentropy index survives the writes that used to drop it for nothing (a vanish
 * that deleted nothing, a kind 5 deleting an author's own events), the vanish trigger has an
 * index to walk, and the default id snapshot stops at its cap.
 */
class LiveIndexAuditFixesTest {
    private val author = NostrSignerSync()
    private val stranger = NostrSignerSync()

    private fun liveStore() = EventStore(dbName = null, indexStrategy = DefaultIndexingStrategy(maintainLiveNegentropyIndex = true))

    private fun ids(store: EventStore): Set<String> =
        runBlocking {
            store.store
                .snapshotIdsForNegentropy(listOf(Filter()))
                .map { it.id }
                .toSet()
        }

    @Test
    fun aVanishThatDeletesNothingKeepsTheLiveIndex() =
        runBlocking {
            val store = liveStore()
            val notes = (1..3).map { author.sign(TextNoteEvent.build("note $it", createdAt = 1_000L + it)) }
            notes.forEach { store.insert(it) }
            assertEquals(3, store.liveNegentropySnapshot(1_000)!!.size())

            val vanish = stranger.sign(RequestToVanishEvent.build(store.relay!!, createdAt = 2_000))
            store.insert(vanish)

            assertTrue(store.store.liveNegentropyIndex!!.isPopulated(), "nothing was deleted, so nothing to rebuild")
            assertEquals(4, store.liveNegentropySnapshot(1_000)!!.size())
            store.close()
        }

    @Test
    fun aDeletionOfOwnEventsKeepsTheLiveIndexExact() =
        runBlocking {
            val store = liveStore()
            val notes = (1..3).map { author.sign(TextNoteEvent.build("note $it", createdAt = 1_000L + it)) }
            notes.forEach { store.insert(it) }
            store.liveNegentropySnapshot(1_000)

            val deletion = author.sign(DeletionRequestEvent.build(listOf(notes[0]), createdAt = 2_000))
            store.insert(deletion)

            assertTrue(store.store.liveNegentropyIndex!!.isPopulated(), "the deleted rows are itemized, not rebuilt from a scan")
            assertEquals(ids(store), setOf(notes[1].id, notes[2].id, deletion.id))
            assertEquals(3, store.liveNegentropySnapshot(1_000)!!.size())
            store.close()
        }

    @Test
    fun aVanishThatDeletesKeepsTheLiveIndexExact() =
        runBlocking {
            val store = liveStore()
            val notes = (1..3).map { author.sign(TextNoteEvent.build("note $it", createdAt = 1_000L + it)) }
            val others = (1..2).map { stranger.sign(TextNoteEvent.build("other $it", createdAt = 1_000L + it)) }
            (notes + others).forEach { store.insert(it) }
            store.liveNegentropySnapshot(1_000)

            val vanish = author.sign(RequestToVanishEvent.build(store.relay!!, createdAt = 2_000))
            store.insert(vanish)

            assertEquals(setOf(others[0].id, others[1].id, vanish.id), ids(store))
            assertEquals(3, store.liveNegentropySnapshot(1_000)!!.size(), "the live snapshot drops what the vanish deleted")
            store.close()
        }

    @Test
    fun anOverCapCorpusIsNotRescannedUntilSomethingIsDeleted() =
        runBlocking {
            val store = liveStore()
            (1..20).forEach { store.insert(author.sign(TextNoteEvent.build("note $it", createdAt = 1_000L + it))) }
            assertNull(store.liveNegentropySnapshot(10))
            val scans = store.store.liveIndexScans
            assertNull(store.liveNegentropySnapshot(10))
            store.insert(author.sign(TextNoteEvent.build("more", createdAt = 5_000)))
            assertNull(store.liveNegentropySnapshot(10))
            assertEquals(scans, store.store.liveIndexScans, "inserts only grow an over-cap corpus: no rescan")
            assertNull(store.sealedNegentropyStorage(listOf(Filter()), 10), "the scan path answers over-cap from the memo too")
            store.close()
        }

    @Test
    fun aNewDatabaseAndAnUpgradedOneHaveTheVanishIndex() =
        runBlocking {
            val dbFile = Files.createTempFile("vanish-index-", ".db")
            Files.deleteIfExists(dbFile)
            val path = dbFile.toAbsolutePath().toString()
            try {
                EventStore(dbName = path).also {
                    it.insert(author.sign(TextNoteEvent.build("x", createdAt = 10)))
                    it.close()
                }
                BundledSQLiteDriver().open(path).use { db ->
                    assertTrue(db.hasIndex(VANISH_INDEX), "created with the schema")
                    db.exec("DROP INDEX $VANISH_INDEX")
                    db.exec("PRAGMA user_version = 6")
                }
                EventStore(dbName = path).also {
                    it.count(Filter())
                    it.close()
                }
                BundledSQLiteDriver().open(path).use { db ->
                    assertTrue(db.hasIndex(VANISH_INDEX), "an upgraded database gets it too")
                    val plan =
                        db.prepare("EXPLAIN QUERY PLAN DELETE FROM event_headers WHERE created_at < 5 AND pubkey_owner_hash = 7").use { st ->
                            buildString { while (st.step()) append(st.getText(3)).append('\n') }
                        }
                    assertFalse(plan.contains("SCAN event_headers"), "the vanish delete walks the index: $plan")
                }
            } finally {
                listOf("", "-wal", "-shm", "-journal").forEach { Path.of(dbFile.toString() + it).deleteIfExists() }
            }
        }

    /** A store on the interface's default snapshot, counting the events it decodes. */
    private class CountingDefaultStore(
        private val inner: IEventStore,
    ) : IEventStore by inner {
        var decoded = 0

        override suspend fun <T : Event> query(
            filters: List<Filter>,
            onEach: (T) -> Unit,
        ) = inner.query<T>(filters) {
            decoded++
            onEach(it)
        }

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
            onProgress: ((collected: Int) -> Unit)?,
        ): List<IdAndTime> = super<IEventStore>.snapshotIdsForNegentropy(filters, maxEntries, onProgress)
    }

    @Test
    fun theDefaultSnapshotStopsAtItsSentinel() =
        runBlocking {
            val sqlite = EventStore(dbName = null)
            (1..100).forEach { sqlite.insert(author.sign(TextNoteEvent.build("n $it", createdAt = 1_000L + it))) }
            val store = CountingDefaultStore(sqlite)
            assertEquals(6, store.snapshotIdsForNegentropy(listOf(Filter(kinds = listOf(1))), 5).size)
            assertTrue(store.decoded <= 6, "decoded ${store.decoded} events for a 5-entry cap")
            sqlite.close()
        }

    @Test
    fun theSqliteSealedStorageMatchesTheIdSnapshot() =
        runBlocking {
            val store = EventStore(dbName = null)
            (1..50).forEach { store.insert(author.sign(TextNoteEvent.build("n $it", createdAt = 1_000L + it))) }
            val filters = listOf(Filter(kinds = listOf(1), limit = 30), Filter(authors = listOf(author.pubKey), limit = 10))
            val ids = store.snapshotIdsForNegentropy(filters, 100)
            val sealed = store.sealedNegentropyStorage(filters, 100)!!
            assertEquals(ids.size, sealed.size())
            assertNull(store.sealedNegentropyStorage(filters, ids.size - 1))
            store.close()
        }

    /** Runs [body] on a file-backed store, where readers are their own connections beside the writer. */
    private fun withFileStore(
        indexStrategy: IndexingStrategy = DefaultIndexingStrategy(),
        body: suspend (EventStore) -> Unit,
    ) = runBlocking {
        val dbFile = Files.createTempFile("live-index-", ".db")
        Files.deleteIfExists(dbFile)
        val store = EventStore(dbName = dbFile.toAbsolutePath().toString(), indexStrategy = indexStrategy)
        try {
            body(store)
        } finally {
            store.close()
            listOf("", "-wal", "-shm", "-journal").forEach { Path.of(dbFile.toString() + it).deleteIfExists() }
        }
    }

    /**
     * Audit 3, finding 4: a kind 5 bumped the removal generation inside its transaction. A reader
     * that read the bumped generation, then scanned the snapshot from before the commit, recorded
     * "over the cap" under the generation that stays current after the commit, so the full set
     * kept being refused although the deletion brought it under the cap.
     */
    @Test
    fun aScanDuringADeletionsTransactionLeavesNoStaleOverCap() =
        withFileStore { store ->
            val notes = (1..5).map { author.sign(TextNoteEvent.build("note $it", createdAt = 1_000L + it)) }
            notes.forEach { store.insert(it) }
            val deletion = author.sign(DeletionRequestEvent.build(notes.take(2), createdAt = 2_000))

            store.store.transaction {
                insert(deletion)
                // Not committed yet: a reader still sees the five notes, over a cap of four.
                runBlocking { assertNull(store.store.sealedNegentropyStorage(listOf(Filter()), 4)) }
            }

            assertEquals(4, store.store.sealedNegentropyStorage(listOf(Filter()), 4)?.size(), "three notes and the kind 5 fit under four")
        }

    /**
     * Audit 3, finding 9: the in-memory store reads on the writer's connection, so the live index's
     * reader-side rebuild never ran beside a commit there. On a file, inserts and kind 5s land while
     * the rebuild scans, and the index replays them onto the scan.
     */
    @Test
    fun theLiveIndexRebuiltBesideCommitsMatchesTheStore() =
        withFileStore(DefaultIndexingStrategy(maintainLiveNegentropyIndex = true)) { store ->
            val writer = NostrSignerSync()
            val seeds = (1..20_000).map { writer.sign(TextNoteEvent.build("seed $it", createdAt = 1_000L + it)) }
            store.batchInsert(seeds)

            coroutineScope {
                val writing =
                    launch(Dispatchers.IO) {
                        var n = 0
                        while (isActive) {
                            val notes = (1..5).map { writer.sign(TextNoteEvent.build("live $n $it", createdAt = 100_000L + n * 10 + it)) }
                            store.batchInsert(notes)
                            store.insert(writer.sign(DeletionRequestEvent.build(notes.take(2), createdAt = 100_000L + n * 10 + 9)))
                            n++
                        }
                    }
                repeat(20) {
                    // A delete by filter drops the index, so the next NEG-OPEN rebuilds it from a reader's scan.
                    store.delete(Filter(ids = listOf(seeds[it].id)))
                    assertTrue(store.liveNegentropySnapshot(1_000_000) != null)
                }
                writing.cancelAndJoin()
            }

            assertTrue(store.store.liveIndexReplayed > 0, "commits landed while the index was being rebuilt")
            val live = store.liveNegentropySnapshot(1_000_000)!!
            assertEquals(ids(store), live.map { it.id.toHexString() }.toSet())
        }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step() }

    private fun SQLiteConnection.hasIndex(name: String) =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?").use {
            it.bindText(1, name)
            it.step()
        }

    private companion object {
        const val VANISH_INDEX = "query_by_owner_created"
    }
}
