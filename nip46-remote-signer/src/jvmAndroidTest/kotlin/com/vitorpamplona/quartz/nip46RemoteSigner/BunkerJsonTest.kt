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
package com.vitorpamplona.quartz.nip46RemoteSigner

import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import com.vitorpamplona.quartz.nip46RemoteSigner.kotlinSerialization.BunkerRequestKSerializer
import com.vitorpamplona.quartz.nip46RemoteSigner.kotlinSerialization.BunkerResponseKSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The Jackson path ([BunkerJson] on JVM) and the kotlinx serializers write and read the same JSON. */
class BunkerJsonTest {
    // =========================================================================
    // BunkerRequest Tests
    // =========================================================================

    @Test
    fun serializeBunkerRequest_matchesJackson() {
        val req = BunkerRequest("id1", "connect", arrayOf("pubkey", "secret"))
        val jacksonJson = BunkerJson.toJson(req)
        val kotlinJson = KotlinSerializationMapper.json.encodeToString(BunkerRequestKSerializer, req)
        assertEquals(jacksonJson, kotlinJson)
    }

    @Test
    fun deserializeBunkerRequest() {
        val json = """{"id":"id1","method":"ping","params":[]}"""
        val deserialized = BunkerJson.fromJsonToRequest(json)
        assertEquals("id1", deserialized.id)
        assertEquals("ping", deserialized.method)
    }

    @Test
    fun crossDeserializationBunkerRequest() {
        val req = BunkerRequest("id1", "sign_event", arrayOf("{\"created_at\":1234,\"kind\":1,\"tags\":[],\"content\":\"This is an unsigned event.\"}"))
        val jacksonJson = BunkerJson.toJson(req)
        val kotlinDeserialized = BunkerJson.fromJsonToRequest(jacksonJson)
        assertEquals(req.id, kotlinDeserialized.id)
        assertEquals(req.method, kotlinDeserialized.method)
        assertContentEquals(req.params, kotlinDeserialized.params)

        val kotlinJson = KotlinSerializationMapper.json.encodeToString(BunkerRequestKSerializer, req)
        val jacksonDeserialized = BunkerJson.fromJsonToRequest(kotlinJson)
        assertEquals(req.id, jacksonDeserialized.id)
        assertEquals(req.method, jacksonDeserialized.method)
        assertContentEquals(req.params, jacksonDeserialized.params)
    }

    // =========================================================================
    // BunkerResponse Tests
    // =========================================================================

    @Test
    fun serializeBunkerResponse_matchesJackson() {
        val resp = BunkerResponse("id1", "ok", null)
        val jacksonJson = BunkerJson.toJson(resp)
        val kotlinJson = KotlinSerializationMapper.json.encodeToString(BunkerResponseKSerializer, resp)
        assertEquals(jacksonJson, kotlinJson)
    }

    @Test
    fun serializeBunkerResponseWithError_matchesJackson() {
        val resp = BunkerResponse("id1", null, "something went wrong")
        val jacksonJson = BunkerJson.toJson(resp)
        val kotlinJson = KotlinSerializationMapper.json.encodeToString(BunkerResponseKSerializer, resp)
        assertEquals(jacksonJson, kotlinJson)
    }

    @Test
    fun deserializeBunkerResponse() {
        val json = """{"id":"id1","result":"pong"}"""
        val deserialized = BunkerJson.fromJsonToResponse(json)
        assertEquals("id1", deserialized.id)
        assertNotNull(deserialized.result)
    }
}
