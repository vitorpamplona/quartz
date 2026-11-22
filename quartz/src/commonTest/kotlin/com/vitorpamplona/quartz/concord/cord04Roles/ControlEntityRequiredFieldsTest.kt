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
package com.vitorpamplona.quartz.concord.cord04Roles

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A field the spec requires is written even when it holds its default. A reader that follows the
 * spec (Armada does) treats an edition missing one as malformed and applies nothing from it, so a
 * public channel or a no-permission role written without them is invisible there.
 */
class ControlEntityRequiredFieldsTest {
    private fun encode(channel: ChannelEntity): JsonObject = ConcordJson.instance.parseToJsonElement(ConcordJson.encodePreserving(ChannelEntity.serializer(), channel, null)).jsonObject

    private fun encode(metadata: MetadataEntity): JsonObject = ConcordJson.instance.parseToJsonElement(ConcordJson.encodePreserving(MetadataEntity.serializer(), metadata, null)).jsonObject

    private fun encode(role: RoleEntity): JsonObject = ConcordJson.instance.parseToJsonElement(ConcordJson.encodePreserving(RoleEntity.serializer(), role, null)).jsonObject

    /** CORD-03 §2: `name` (a string) and `private` (a boolean) are required. */
    @Test
    fun aPublicChannelWritesPrivateFalse() {
        val json = encode(ChannelEntity(name = "general"))
        assertEquals("general", json["name"]?.jsonPrimitive?.content)
        assertEquals(false, json["private"]?.jsonPrimitive?.boolean)
    }

    /**
     * CORD-02 §6: `name` is required (Armada folds no edition without a string one), so metadata
     * written over an empty fold, a timer alone, still carries it.
     */
    @Test
    fun metadataWritesItsNameEvenWhenEmpty() {
        val json = encode(MetadataEntity().withMessageExpiration(3600))
        assertEquals(JsonPrimitive(""), json["name"])
        assertEquals(JsonPrimitive(3600), json["message_expiration"])
        assertEquals("Vector", encode(MetadataEntity(name = "Vector"))["name"]?.jsonPrimitive?.content)
    }

    /** CORD-04 §2: a legacy Role requires `role_id`, `name`, `position`, `permissions` and `scope`. */
    @Test
    fun aLegacyRoleWritesEveryRequiredField() {
        val id = "a".repeat(64)
        val json = encode(RoleEntity(roleId = id, name = "Ops", position = 2))
        assertEquals(id, json["role_id"]?.jsonPrimitive?.content)
        assertEquals("Ops", json["name"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(2), json["position"])
        assertEquals("0", json["permissions"]?.jsonPrimitive?.content)
        val scope = json["scope"]?.jsonObject
        assertEquals("server", scope?.get("kind")?.jsonPrimitive?.content)
    }

    @Test
    fun aChannelScopedRoleKeepsItsChannel() {
        val channel = "b".repeat(64)
        val json = encode(RoleEntity(roleId = "a".repeat(64), name = "Ops", position = 2, scope = RoleScope(kind = "channel", channelId = channel)))
        val scope = json["scope"]?.jsonObject
        assertEquals("channel", scope?.get("kind")?.jsonPrimitive?.content)
        assertEquals(channel, scope?.get("channel_id")?.jsonPrimitive?.content)
    }

    /** What older editions wrote (no `scope`) still reads as server-wide. */
    @Test
    fun aRoleWithoutScopeStillReadsAsServer() {
        val role = ConcordJson.decodeOrNull<RoleEntity>("""{"role_id":"${"a".repeat(64)}","name":"Ops","position":2,"permissions":"0"}""")
        assertTrue(role != null && role.scope?.kind != "channel")
    }
}
