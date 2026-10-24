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
package com.vitorpamplona.quartz.nip01Core.store.fs

import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore.InsertOutcome
import com.vitorpamplona.quartz.nip01Core.store.RejectionReason
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FsEventStore.batchInsert] must say what happened to each event, like
 * SQLite does: a relay turns these outcomes into OK frames and fans only
 * `Accepted` events out to live subscribers.
 */
class FsInsertOutcomeTest {
    private val signer = NostrSignerSync()
    private lateinit var root: Path
    private lateinit var store: FsEventStore

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("fs-outcome-")
        store = FsEventStore(root)
    }

    @AfterTest
    fun tearDown() {
        store.close()
        if (root.exists()) {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }
        }
    }

    private fun note(
        body: String,
        ts: Long,
        tags: Array<Array<String>> = emptyArray(),
    ) = signer.sign<TextNoteEvent>(ts, TextNoteEvent.KIND, tags, body)

    private fun article(
        body: String,
        ts: Long,
    ) = signer.sign<LongFormContentEvent>(ts, LongFormContentEvent.KIND, arrayOf(arrayOf("d", "slug")), body)

    @Test
    fun newEventIsAccepted() =
        runBlocking {
            assertEquals(listOf<InsertOutcome>(InsertOutcome.Accepted), store.batchInsert(listOf(note("a", 10))))
        }

    @Test
    fun duplicateIsRejectedAsDuplicate() =
        runBlocking {
            val n = note("a", 10)
            store.insert(n)
            assertEquals(listOf<InsertOutcome>(InsertOutcome.Rejected(RejectionReason.DUPLICATE)), store.batchInsert(listOf(n)))
        }

    @Test
    fun duplicateWithinOneBatchIsRejectedTheSecondTime() =
        runBlocking {
            val n = note("a", 10)
            assertEquals(
                listOf(InsertOutcome.Accepted, InsertOutcome.Rejected(RejectionReason.DUPLICATE)),
                store.batchInsert(listOf(n, n)),
            )
        }

    @Test
    fun expiredIsRejectedAsExpired() =
        runBlocking {
            val expired = note("a", 10, arrayOf(arrayOf("expiration", (TimeUtils.now() - 60).toString())))
            assertEquals(listOf<InsertOutcome>(InsertOutcome.Rejected(RejectionReason.EXPIRED)), store.batchInsert(listOf(expired)))
        }

    @Test
    fun olderReplaceableIsRejectedAsReplaced() =
        runBlocking {
            store.insert(article("new", 20))
            assertEquals(listOf<InsertOutcome>(InsertOutcome.Rejected(RejectionReason.REPLACED)), store.batchInsert(listOf(article("old", 10))))
        }

    @Test
    fun deletedIsRejectedAsDeleted() =
        runBlocking {
            val n = note("a", 10)
            store.insert(n)
            store.insert(signer.sign<DeletionRequestEvent>(DeletionRequestEvent.build(listOf(n), createdAt = 30)))
            assertEquals(listOf<InsertOutcome>(InsertOutcome.Rejected(RejectionReason.DELETED)), store.batchInsert(listOf(n)))
        }
}
