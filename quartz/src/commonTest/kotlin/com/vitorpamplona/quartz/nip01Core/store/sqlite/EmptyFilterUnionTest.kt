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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * STORE-F07 + STORE-F10: a REQ's filters are a union, and an empty filter matches everything, so a
 * union holding `{}` matches everything too, on every multi-filter path (query, count, negentropy).
 * Only a delete reads `{}` as nothing.
 */
class EmptyFilterUnionTest : BaseDBTest() {
    val signer = NostrSignerSync()

    @Test
    fun anEmptyFilterInAUnionMatchesEverything() =
        forEachDB { db ->
            db.insert(signer.sign(TextNoteEvent.build("one", createdAt = 1)))
            db.insert(signer.sign(TextNoteEvent.build("two", createdAt = 2)))
            db.insert(signer.sign(LongFormContentEvent.build("body", "title", dTag = "blog", createdAt = 3)))

            val union = listOf(Filter(), Filter(kinds = listOf(LongFormContentEvent.KIND)))
            assertEquals(3, db.count(Filter()))
            assertEquals(3, db.count(union))
            assertEquals(3, db.query<Event>(union).size)
            assertEquals(3, db.snapshotIdsForNegentropy(union).size)

            db.delete(union)
            assertEquals(2, db.count(Filter()))
        }
}
