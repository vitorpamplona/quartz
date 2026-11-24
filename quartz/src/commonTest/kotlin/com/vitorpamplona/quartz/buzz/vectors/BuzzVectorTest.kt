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
package com.vitorpamplona.quartz.buzz.vectors

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.buzz.cwChannelWindow.ThreadSummaryEvent
import com.vitorpamplona.quartz.buzz.dm.BuzzDmRules
import com.vitorpamplona.quartz.buzz.pmPinnedMessages.ChannelPinsEvent
import com.vitorpamplona.quartz.buzz.workspace.BuzzChannelLabels
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupAdminsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMembersEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Buzz interop, both ways, from golden files in `commonTest/resources/buzz/vectors/` that
 * `quartz/tools/buzz-vector-gen` regenerates against a real buzz-relay.
 *
 *  - `buzz.json` (THEIRS → US): relay-signed events Buzz emitted while the scenario ran (channel
 *    state 39000/39001/39002, NIP-PM pin lists 30623, NIP-CW thread summaries 39005), each with
 *    `expect`, what the scenario set up. Quartz must read each that way.
 *  - `ours-read-by-buzz.json` (OURS → THEM): the commands Quartz's builders write
 *    ([BuzzVectorFixture.ourCases]), each with Buzz's OK. Our builders must still write those
 *    templates, Buzz must have taken the ones it should, and Quartz must read the `response:{…}` a DM
 *    command's OK carries as a JSON parser does.
 */
class BuzzVectorTest {
    private val fx = BuzzVectorFixture

    private fun load(name: String) = Json.parseToJsonElement(TestResourceLoader().loadString("buzz/vectors/$name")).jsonObject

    private fun name(case: JsonObject) = case["name"]!!.jsonPrimitive.content

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    /** What Quartz reads in a relay-signed Buzz event, in the vocabulary of the generator's `expect`. */
    private fun read(event: Event): JsonObject =
        when (event) {
            is GroupMetadataEvent ->
                JsonObject(
                    mapOf(
                        "group_id" to JsonPrimitive(event.groupId()),
                        "name" to JsonPrimitive(event.name()),
                        "about" to JsonPrimitive(event.about()),
                        "private" to JsonPrimitive(event.isPrivate()),
                        "channel_type" to JsonPrimitive(event.buzzChannelType()),
                        "listed" to JsonPrimitive(BuzzChannelLabels.isListed(event)),
                        "dm" to JsonPrimitive(event.isBuzzDmChannel()),
                    ),
                )
            is GroupAdminsEvent ->
                JsonObject(
                    mapOf(
                        "group_id" to JsonPrimitive(event.groupId()),
                        "admins" to JsonObject(event.admins().associate { it.pubKey to strings(it.roles) }),
                    ),
                )
            is GroupMembersEvent -> JsonObject(mapOf("group_id" to JsonPrimitive(event.groupId()), "members" to strings(event.members().sorted())))
            is ChannelPinsEvent -> JsonObject(mapOf("channel" to JsonPrimitive(event.channel()), "pinned" to strings(event.pinnedMessageIds())))
            is ThreadSummaryEvent ->
                JsonObject(
                    mapOf(
                        "root" to JsonPrimitive(event.rootId()),
                        "channel" to JsonPrimitive(event.channelId()),
                        "reply_count" to JsonPrimitive(event.summaryOrNull()?.replyCount),
                        "participants" to strings(event.participantKeys().sorted()),
                    ),
                )
            else -> JsonObject(mapOf("unexpected_kind" to JsonPrimitive(event.kind)))
        }

    @Test
    fun quartzReadsBuzzStateAsTheScenarioSetItUp() {
        val file = load("buzz.json")
        val relay = file["relay"]!!.jsonPrimitive.content
        assertEquals(fx.PK.values.toList(), fx.PK.keys.map { file["users"]!!.jsonObject[it]!!.jsonPrimitive.content })
        val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val event = Event.fromJson(case["event"]!!.toString())
            assertTrue(event.verify(), "${name(case)}: Buzz's signature")
            assertEquals(relay, event.pubKey, "${name(case)}: signed by the relay key")
            val ours = read(event)
            for ((key, expected) in case["expect"]!!.jsonObject) {
                if (ours[key] != expected) mismatches.add("${name(case)}.$key: quartz=${ours[key]} buzz=$expected")
            }
        }
        assertEquals(emptyList(), mismatches)
        assertTrue(cases.size >= 10, "every case was read: ${cases.map(::name)}")
    }

    @Test
    fun ourBuildersStillWriteTheTemplatesBuzzRead() {
        val file = load("ours-read-by-buzz.json")
        assertEquals(fx.PLACEHOLDERS.toList(), file["placeholders"]!!.jsonObject.map { it.key to it.value.jsonPrimitive.content })
        val written = fx.ourCases()
        val recorded = file["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(written.map { it.name }, recorded.map(::name), "regenerate with quartz/tools/buzz-vector-gen/gen.sh")
        for ((mine, case) in written.zip(recorded)) {
            val template = case["template"]!!.jsonObject
            assertEquals(mine.who, case["who"]!!.jsonPrimitive.content)
            assertEquals(mine.template.kind, template["kind"]!!.jsonPrimitive.content.toInt(), mine.name)
            assertEquals(mine.template.content, template["content"]!!.jsonPrimitive.content, mine.name)
            assertEquals(
                mine.template.tags.map { it.toList() },
                template["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
                "${mine.name}: our builder changed; regenerate with quartz/tools/buzz-vector-gen/gen.sh",
            )
        }
    }

    /** Which of our commands Buzz takes; the rest it refuses on purpose (the reason is in the file). */
    private val accepted =
        mapOf(
            "create_duplicate_listed_name" to false,
        )

    @Test
    fun buzzTookOurCommandsAndQuartzReadsItsAnswers() {
        val cases = load("ours-read-by-buzz.json")["cases"]!!.jsonArray.map { it.jsonObject }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val name = name(case)
            val buzz = case["buzz"]!!.jsonObject
            val ok = buzz["accepted"]!!.jsonPrimitive.boolean
            val message = buzz["message"]?.jsonPrimitive?.content
            if (ok != (accepted[name] ?: true)) mismatches.add("$name: accepted=$ok ($message)")
            val response = buzz["response"] as? JsonObject ?: continue
            val channelId = response["channel_id"]?.jsonPrimitive?.content
            if (BuzzDmRules.channelIdFromOk(message) != channelId) mismatches.add("$name: channel_id quartz=${BuzzDmRules.channelIdFromOk(message)} json=$channelId")
            val created = response["created"]?.jsonPrimitive?.booleanOrNull
            if (BuzzDmRules.createdFromOk(message) != created) mismatches.add("$name: created quartz=${BuzzDmRules.createdFromOk(message)} json=$created")
        }
        assertEquals(emptyList(), mismatches)
    }
}
