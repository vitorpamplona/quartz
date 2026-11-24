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

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Audit 3, finding 3: a NEG-OPEN's snapshot scan stops once its session is gone. SQLite never
 * suspends on that path, so a cancelled build kept reading up to `maxSyncEvents + 1` rows; the scan
 * now checks its coroutine as it goes.
 */
class NegentropyScanCancellationTest {
    private val signer = NostrSignerSync()

    private suspend fun scanThrowsOnceCancelled(
        indexStrategy: IndexingStrategy,
        scan: suspend (EventStore) -> Unit,
    ): Boolean {
        val store = EventStore(dbName = null, indexStrategy = indexStrategy)
        (1..10).forEach { store.insert(signer.sign(TextNoteEvent.build("n $it", createdAt = 1_000L + it))) }
        var stopped = false
        coroutineScope {
            launch {
                coroutineContext.job.cancel()
                try {
                    scan(store)
                } catch (_: CancellationException) {
                    stopped = true
                }
            }
        }
        store.close()
        return stopped
    }

    @Test
    fun aCancelledSealedSnapshotScanStops() =
        runTest {
            assertTrue(scanThrowsOnceCancelled(DefaultIndexingStrategy()) { it.store.sealedNegentropyStorage(listOf(Filter(kinds = listOf(1))), 1_000) })
        }

    @Test
    fun aCancelledLiveIndexRebuildStops() =
        runTest {
            val strategy = DefaultIndexingStrategy(maintainLiveNegentropyIndex = true)
            assertTrue(scanThrowsOnceCancelled(strategy) { it.store.liveNegentropySnapshot(1_000) })
        }
}
