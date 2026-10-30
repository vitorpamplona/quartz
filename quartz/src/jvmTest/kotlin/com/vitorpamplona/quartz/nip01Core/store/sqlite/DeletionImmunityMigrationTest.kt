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
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The v5 → v6 upgrade: a database created before v6 carries a `reject_deleted_events` trigger
 * with no kind exemption, so a kind 5 that names another kind 5 blocks it from arriving. On open
 * the trigger is replaced and the blocked deletion is accepted (STORE-D05).
 */
class DeletionImmunityMigrationTest {
    private val signer = NostrSignerSync()
    private lateinit var dbFile: Path

    private fun path() = dbFile.toAbsolutePath().toString()

    @BeforeTest
    fun setup() {
        dbFile = Files.createTempFile("deletion-immunity-migration-", ".db")
        Files.deleteIfExists(dbFile)
    }

    @AfterTest
    fun tearDown() {
        listOf("", "-wal", "-shm", "-journal").forEach { Path.of(dbFile.toString() + it).deleteIfExists() }
    }

    @Test
    fun upgradeExemptsDeletionRequestsFromTheBlockingTrigger() =
        runBlocking {
            val target = signer.sign(TextNoteEvent.build("target", createdAt = 50))
            val firstDeletion = signer.sign(DeletionRequestEvent.build(listOf(target), createdAt = 100))
            val secondDeletion = signer.sign(DeletionRequestEvent.build(listOf(firstDeletion), createdAt = 300))

            EventStore(dbName = path(), relay = null).also {
                it.insert(secondDeletion)
                it.close()
            }

            // The pre-v6 trigger: same body, no WHEN clause.
            BundledSQLiteDriver().open(path()).use { db ->
                db.exec("DROP TRIGGER reject_deleted_events")
                db.exec(
                    """
                    CREATE TRIGGER reject_deleted_events BEFORE INSERT ON event_headers FOR EACH ROW
                    BEGIN
                        SELECT RAISE(ABORT, 'blocked: a deletion event exists')
                        WHERE EXISTS (
                            SELECT 1 FROM event_tags
                            WHERE event_tags.tag_hash IN (NEW.etag_hash, NEW.atag_hash) AND
                                event_tags.kind = 5 AND
                                event_tags.created_at >= NEW.created_at AND
                                event_tags.pubkey_hash = NEW.pubkey_owner_hash
                        );
                    END
                    """.trimIndent(),
                )
                db.exec("PRAGMA user_version = 5")
            }

            val store = EventStore(dbName = path(), relay = null)
            try {
                store.insert(target)
                store.insert(firstDeletion)
                assertEquals(firstDeletion.id, store.query<Event>(Filter(ids = listOf(firstDeletion.id))).single().id)
                assertEquals(emptyList(), store.query<Event>(Filter(ids = listOf(target.id))).map { it.id })
            } finally {
                store.close()
            }
        }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step() }
}
