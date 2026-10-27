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
package com.vitorpamplona.quartz.nip09Deletions

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.deletionsCovering
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** NIP-09: only the author of an event can delete it, so only their kind-5 covers it. */
class DeletionsCoveringAuthorTest {
    private val me = NostrSignerSync()
    private val stranger = NostrSignerSync()
    private val relay = NormalizedRelayUrl("wss://relay.test/")

    private val note = me.sign(TextNoteEvent.build("hi", createdAt = 100))
    private val article = me.sign<Event>(100, 30023, arrayOf(arrayOf("d", "post")), "body")

    private suspend fun covering(
        events: List<Event>,
        deletions: List<Event>,
    ) = deletionsCovering(events, relay) { filter -> deletions.filter(filter::match) }.map { it.id }.toSet()

    @Test
    fun byIdOnlyTheAuthorsDeletionCovers() =
        runTest {
            val mine = me.sign(DeletionRequestEvent.build(listOf(note), createdAt = 200))
            val theirs = stranger.sign(DeletionRequestEvent.build(listOf(note), createdAt = 200))
            assertEquals(setOf(mine.id), covering(listOf(note), listOf(mine, theirs)))
        }

    @Test
    fun byAddressOnlyTheAuthorsDeletionCovers() =
        runTest {
            val mine = me.sign(DeletionRequestEvent.buildAddressOnly(listOf(article), createdAt = 200))
            val theirs = stranger.sign(DeletionRequestEvent.buildAddressOnly(listOf(article), createdAt = 200))
            assertEquals(setOf(mine.id), covering(listOf(article), listOf(mine, theirs)))
        }

    @Test
    fun aStrangersDeletionOfBothIdAndAddressCoversNothing() =
        runTest {
            val theirs = stranger.sign(DeletionRequestEvent.build(listOf(note, article), createdAt = 200))
            assertEquals(emptySet(), covering(listOf(note, article), listOf(theirs)))
        }
}
