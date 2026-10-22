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
package com.vitorpamplona.quartz.nip01Core.core

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both [JsonEngine]s, on every target: the platform default and Kite agree. */
class JsonEngineCommonTest {
    @AfterTest
    fun reset() {
        JsonEngine.current = JsonEngine.DEFAULT
    }

    private fun <T> both(block: () -> T): Pair<T, T> {
        val platform = block()
        JsonEngine.current = JsonEngine.KITE
        try {
            return platform to block()
        } finally {
            JsonEngine.current = JsonEngine.DEFAULT
        }
    }

    @Test
    fun eventsRoundTripTheSameUnderBothEngines() {
        val note =
            NostrSignerSync().sign(
                TextNoteEvent.build("line\nquote\" tab\t emoji 😀 ctl \u0001") {
                    add(arrayOf("t", "nostr"))
                    add(arrayOf("e", "a".repeat(64), "", "root"))
                },
            )

        val (platformJson, handJson) = both { OptimizedJsonMapper.toJson(note) }
        assertEquals(platformJson, handJson)

        val (platformEvent, handEvent) = both { OptimizedJsonMapper.fromJson(platformJson) }
        assertEquals(note.id, platformEvent.id)
        assertEquals(note.id, handEvent.id)
        assertTrue(handEvent.tags.contentDeepEquals(platformEvent.tags))
        assertEquals(platformEvent.content, handEvent.content)

        val (platformId, handId) = both { EventHasher.hashId(note.pubKey, note.createdAt, note.kind, note.tags, note.content) }
        assertEquals(note.id, platformId)
        assertEquals(note.id, handId)
        assertEquals(true to true, both { note.verify() })

        val (platformTags, handTags) = both { OptimizedJsonMapper.toJson(note.tags) }
        assertEquals(platformTags, handTags)
    }

    @Test
    fun relayFramesReadTheSameUnderBothEngines() {
        val note = NostrSignerSync().sign(TextNoteEvent.build("hi"))
        val frame = """["EVENT","sub",${OptimizedJsonMapper.toJson(note)}]"""
        val (platform, hand) = both { OptimizedJsonMapper.fromJsonToMessage(frame) }
        assertEquals(OptimizedJsonMapper.toJson(platform), OptimizedJsonMapper.toJson(hand))

        val req = """["REQ","s",{"kinds":[1],"limit":2}]"""
        val (platformCmd, handCmd) = both { OptimizedJsonMapper.fromJsonToCommand(req) }
        assertEquals(OptimizedJsonMapper.toJson(platformCmd), OptimizedJsonMapper.toJson(handCmd))
    }
}
