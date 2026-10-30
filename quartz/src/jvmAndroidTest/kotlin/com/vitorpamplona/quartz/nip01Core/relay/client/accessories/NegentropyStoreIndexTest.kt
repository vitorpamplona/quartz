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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [NegentropyLocalIndex.of] over an [IEventStore]: the windowed sync's per-window reads answered
 * from the store. A count the store cannot give is null (the engine then stops pre-splitting that
 * window), never a failed sync; a cancellation is never mistaken for one.
 */
class NegentropyStoreIndexTest {
    private val signer = NostrSignerSync(KeyPair())

    private fun window(
        since: Long?,
        until: Long?,
    ) = Filter(kinds = listOf(TextNoteEvent.KIND), since = since, until = until)

    private fun store(): IEventStore =
        EventStore(dbName = null).also { store ->
            runBlocking { (1000L..1009L).forEach { store.insert(signer.sign(TextNoteEvent.build("n$it", createdAt = it))) } }
        }

    @Test
    fun windowsAreAnsweredFromTheStore() =
        runBlocking {
            store().use { store ->
                val index = NegentropyLocalIndex.of(store)
                assertEquals(3, index.count(window(1002, 1004)))
                assertEquals(listOf(1002L, 1003L, 1004L), index.entriesFor(window(1002, 1004)).map { it.createdAt }.sorted())
                assertEquals(10, index.count(window(null, null)))
            }
        }

    @Test
    fun aCountTheStoreCannotGiveIsNull() =
        runBlocking {
            store().use { inner ->
                val failing =
                    object : IEventStore by inner {
                        override suspend fun count(filter: Filter): Int = throw IllegalStateException("no count here")
                    }
                assertNull(NegentropyLocalIndex.of(failing).count(window(1002, 1004)))
            }
        }

    @Test
    fun aCancelledCountIsACancellation() {
        store().use { inner ->
            val cancelled =
                object : IEventStore by inner {
                    override suspend fun count(filter: Filter): Int = throw CancellationException("caller went away")
                }
            assertFailsWith<CancellationException> { runBlocking { NegentropyLocalIndex.of(cancelled).count(window(1002, 1004)) } }
        }
    }
}
