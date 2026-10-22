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

import com.fasterxml.jackson.core.JsonParseException
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasherSerializer
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequest
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponse
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponsePublicKey
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [JsonEngine] switches every JSON entry point between Jackson and Quartz's hand-written
 * reader and writer, and the two agree on real traffic.
 */
class JsonEngineTest {
    @AfterTest
    fun reset() {
        JsonEngine.current = JsonEngine.DEFAULT
    }

    private fun <T> with(
        engine: JsonEngine,
        block: () -> T,
    ): T {
        val before = JsonEngine.current
        JsonEngine.current = engine
        try {
            return block()
        } finally {
            JsonEngine.current = before
        }
    }

    private val corpus: String by lazy {
        val stream =
            javaClass.classLoader?.getResourceAsStream("nostr_vitor_startup_data.json.gz")
                ?: throw IllegalStateException("corpus missing from the test classpath")
        GZIPInputStream(stream).bufferedReader().use { it.readText() }
    }

    @Test
    fun `jackson is the default`() {
        assertEquals(JsonEngine.DEFAULT, JsonEngine.current)
    }

    @Test
    fun `each engine is reached when selected`() {
        // Jackson's errors arrive wrapped, with Jackson's exception as the cause; the hand-written
        // reader throws its own IllegalArgumentException.
        val jackson = assertFailsWith<IllegalArgumentException> { OptimizedJsonMapper.fromJson("""{"id":""") }
        assertIs<JsonParseException>(jackson.cause)

        with(JsonEngine.HAND_WRITTEN) {
            val handWritten = assertFailsWith<IllegalArgumentException> { OptimizedJsonMapper.fromJson("""{"id":""") }
            assertTrue(handWritten.cause !is JsonParseException)
        }
    }

    @Test
    fun `both engines read and write the startup capture the same way`() {
        val jacksonEvents = OptimizedJsonMapper.fromJsonToEventList(corpus)
        val handEvents = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonToEventList(corpus) }
        assertTrue(jacksonEvents.size > 1000, "capture should hold real traffic, had ${jacksonEvents.size}")
        assertEquals(jacksonEvents.size, handEvents.size)

        for (i in jacksonEvents.indices) {
            val j = jacksonEvents[i]
            val h = handEvents[i]
            assertEquals(j::class, h::class)
            assertTrue(j.tags.contentDeepEquals(h.tags), "tags of ${j.id}")

            val json = OptimizedJsonMapper.toJson(j)
            assertEquals(json, with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(h) }, "event ${j.id}")

            val frame = """["EVENT","sub",$json]"""
            val jMsg = OptimizedJsonMapper.fromJsonToMessage(frame)
            val hMsg = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonToMessage(frame) }
            assertEquals((jMsg as EventMessage).event.id, (hMsg as EventMessage).event.id)

            val tags = OptimizedJsonMapper.toJson(j.tags)
            assertEquals(tags, with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(h.tags) })

            val preimage = EventHasherSerializer.makeJsonForId(j.pubKey, j.createdAt, j.kind, j.tags, j.content)
            assertEquals(preimage, with(JsonEngine.HAND_WRITTEN) { EventHasherSerializer.makeJsonForId(h.pubKey, h.createdAt, h.kind, h.tags, h.content) })

            val hash = EventHasher.hashId(j.pubKey, j.createdAt, j.kind, j.tags, j.content)
            assertEquals(hash, with(JsonEngine.HAND_WRITTEN) { EventHasher.hashId(h.pubKey, h.createdAt, h.kind, h.tags, h.content) })
            assertEquals(
                EventHasher.hashIdCheck(j.id, j.pubKey, j.createdAt, j.kind, j.tags, j.content),
                with(JsonEngine.HAND_WRITTEN) { EventHasher.hashIdCheck(h.id, h.pubKey, h.createdAt, h.kind, h.tags, h.content) },
            )
        }
    }

    @Test
    fun `both engines agree on commands, filters and nip-46`() {
        val req = """["REQ","s1",{"kinds":[1,6],"authors":["${"a".repeat(64)}"],"#e":["${"b".repeat(64)}"],"since":10,"limit":5}]"""
        val jCmd = OptimizedJsonMapper.fromJsonToCommand(req)
        val hCmd = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonToCommand(req) }
        assertIs<ReqCmd>(hCmd)
        assertEquals(OptimizedJsonMapper.toJson(jCmd), with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(hCmd) })

        val filter = """{"kinds":[1],"limit":3}"""
        val jFilter = OptimizedJsonMapper.fromJsonTo<Filter>(filter)
        val hFilter = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonTo<Filter>(filter) }
        assertEquals(OptimizedJsonMapper.toJson(jFilter), with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(hFilter) })

        val request = BunkerRequest("id-1", "sign_event", arrayOf("{}"))
        assertEquals(OptimizedJsonMapper.toJson(request), with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(request) })

        val response = """{"id":"id-1","result":"ack"}"""
        val jResp = OptimizedJsonMapper.fromJsonTo<BunkerResponse>(response)
        val hResp = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonTo<BunkerResponse>(response) }
        assertEquals(jResp.id, hResp.id)
        assertEquals(jResp.result, hResp.result)
        assertNotNull(hResp)

        // Types neither engine has a dedicated reader for still go somewhere.
        val ok = """["OK","${"c".repeat(64)}",true,""]"""
        assertEquals(
            OptimizedJsonMapper.toJson(OptimizedJsonMapper.fromJsonTo<Message>(ok)),
            with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(OptimizedJsonMapper.fromJsonTo<Message>(ok)) },
        )
        val close = """["CLOSE","s1"]"""
        assertEquals(
            OptimizedJsonMapper.toJson(OptimizedJsonMapper.fromJsonTo<Command>(close)),
            with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.toJson(OptimizedJsonMapper.fromJsonTo<Command>(close)) },
        )
    }

    @Test
    fun `both engines read a null nip-46 error as no error`() {
        val pubkey = "a".repeat(64)
        for (json in listOf("""{"id":"x","result":"$pubkey","error":null}""", """{"id":"x","result":null,"error":"denied"}""", """{"id":"x","result":""}""")) {
            val jackson = OptimizedJsonMapper.fromJsonTo<BunkerResponse>(json)
            val handWritten = with(JsonEngine.HAND_WRITTEN) { OptimizedJsonMapper.fromJsonTo<BunkerResponse>(json) }
            assertEquals(jackson::class, handWritten::class, json)
            assertEquals(jackson.result, handWritten.result, json)
            assertEquals(jackson.error, handWritten.error, json)
        }
        assertIs<BunkerResponsePublicKey>(OptimizedJsonMapper.fromJsonTo<BunkerResponse>("""{"id":"x","result":"$pubkey","error":null}"""))
    }

    @Test
    fun `both engines reject malformed tags`() {
        val bad = """{"id":"${"a".repeat(64)}","pubkey":"${"b".repeat(64)}","created_at":1,"kind":1,"tags":[["a"],{}],"content":"","sig":"${"c".repeat(128)}"}"""
        assertFailsWith<Exception> { OptimizedJsonMapper.fromJson(bad) }
        with(JsonEngine.HAND_WRITTEN) { assertFailsWith<IllegalArgumentException> { OptimizedJsonMapper.fromJson(bad) } }
    }
}
