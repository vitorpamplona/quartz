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
package com.vitorpamplona.quartz.concord.appEvents

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concord app-event interop with Armada, the reference client, both ways, from golden files in
 * `commonTest/resources/concord/app-events/` that `quartz/tools/concord-vector-gen` regenerates.
 *
 *  - `armada.json` (THEIRS → US): rumors Armada's own send paths wrote, each with `expect`, what
 *    Armada's read path decided. Quartz must read each the same way ([ConcordAppEventReading]).
 *  - `ours-read-by-armada.json` (OURS → THEM): the rumors Quartz's builders write
 *    ([ConcordAppEventFixture.ourCases]), each with `armada`, what Armada understood. Our builders
 *    must still write exactly those rumors (a change shows up here and forces a regeneration), and
 *    Armada must have read them the way Quartz does.
 *
 * Where Armada and Quartz disagree on purpose, [deviations] names the keys and why.
 */
class ConcordAppEventVectorTest {
    private val fx = ConcordAppEventFixture

    private fun load(name: String) = Json.parseToJsonElement(TestResourceLoader().loadString("concord/app-events/$name")).jsonObject

    private fun rumor(element: JsonElement): Event {
        val o = element.jsonObject
        return Event.fromJson((if ("sig" in o) o else JsonObject(o + ("sig" to JsonPrimitive("")))).toString())
    }

    private fun fold(editions: List<ControlEdition>) = ConcordCommunityState.fold(editions, fx.communityId, fx.owner)

    private fun stateOf(control: List<Event>) = fold(control.mapNotNull { ControlEdition.fromRumor(it) })

    /**
     * Keys where Quartz reads a case differently from Armada on purpose. Each entry says why.
     */
    private val deviations: Map<String, Set<String>> =
        mapOf(
            // Polls (kind 1068) are an Armada extension (Armada's CORD.md "Polls"), not a Chat Plane
            // kind: CORD-02 Appendix B lists 9, 1111, 7, 5, 1740, 3302 and 3310 (plus the ephemeral
            // 23311/23313/23314), so Quartz's Chat ingest refuses it (ChannelChat.CHAT_KINDS).
            "poll" to setOf("accepted", "inline_reply_to", "thread_root", "thread_parent", "mentions", "visible"),
        )

    /** Every key [ours] holds, compared with [theirs] at the same path; deviations listed by key. */
    private fun diff(
        path: String,
        ours: JsonElement,
        theirs: JsonElement?,
        skip: Set<String>,
        out: MutableList<String>,
    ) {
        if (ours is JsonObject) {
            val other = theirs as? JsonObject
            for ((key, value) in ours) {
                if (key in skip) continue
                diff("$path.$key", value, other?.get(key), emptySet(), out)
            }
            return
        }
        if (ours != theirs) out.add("$path: quartz=$ours armada=$theirs")
    }

    private fun readAll(
        file: JsonObject,
        cases: List<JsonObject>,
        rumorOf: (JsonObject) -> Event?,
    ): Map<String, JsonObject> {
        val control = file["control"]!!.jsonArray.map(::rumor)
        val state = stateOf(control)
        val community = file["community"]!!.jsonObject
        val channel = community["channel"]!!.jsonPrimitive.content
        val epoch = community["epoch"]!!.jsonPrimitive.long
        val nowMs = community["now_ms"]!!.jsonPrimitive.long
        val out = LinkedHashMap<String, JsonObject>()
        val chat = cases.filter { it["plane"]!!.jsonPrimitive.content == "chat" }
        ConcordAppEventReading.chat(chat.map { rumorOf(it)!! }, state, channel, epoch).forEachIndexed { i, r -> out[name(chat[i])] = r }
        val guestbook = cases.filter { it["plane"]!!.jsonPrimitive.content == "guestbook" }
        ConcordAppEventReading.guestbook(guestbook.map { rumorOf(it)!! }, state, nowMs).forEachIndexed { i, r -> out[name(guestbook[i])] = r }
        for (c in cases.filter { it["plane"]!!.jsonPrimitive.content == "control" && "rumor" in it }) {
            out[name(c)] = ConcordAppEventReading.edition(control, rumorOf(c)!!, ::fold)
        }
        return out
    }

    private fun name(case: JsonObject) = case["name"]!!.jsonPrimitive.content

    @Test
    fun quartzReadsArmadasRumorsAsArmadaDoes() {
        val file = load("armada.json")
        assertEquals(
            fx.communityId.toList(),
            file["community"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content
                .hexToByteArray()
                .toList(),
        )
        val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
        val readings = readAll(file, cases) { c -> c["rumor"]?.let(::rumor) }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val name = name(case)
            val ours = readings[name] ?: continue
            diff(name, ours, case["expect"], deviations[name] ?: emptySet(), mismatches)
        }
        assertEquals(emptyList(), mismatches)
        assertTrue(readings.size >= 30, "every case was read: ${readings.keys}")
    }

    @Test
    fun ourBuildersStillWriteTheRumorsArmadaRead() {
        val file = load("ours-read-by-armada.json")
        assertEquals(fx.ourControl().map { it.id }, file["control"]!!.jsonArray.map { rumor(it).id })
        val written = fx.ourCases().associateBy { it.name }
        val recorded = file["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(written.keys.toList(), recorded.map(::name), "regenerate with quartz/tools/concord-vector-gen/gen.sh")
        for (case in recorded) {
            val mine = written.getValue(name(case))
            val ids = mine.rumor?.let { listOf(it.id) } ?: mine.rumors!!.map { it.id }
            val theirs = case["rumor"]?.let { listOf(rumor(it).id) } ?: case["rumors"]!!.jsonArray.map { rumor(it).id }
            assertEquals(ids, theirs, "${name(case)}: our builder changed; regenerate with quartz/tools/concord-vector-gen/gen.sh")
        }
    }

    @Test
    fun armadaReadsOurRumorsAsQuartzDoes() {
        val file = load("ours-read-by-armada.json")
        val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
        val readings = readAll(file, cases) { c -> c["rumor"]?.let(::rumor) }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val name = name(case)
            val ours = readings[name] ?: continue
            diff(name, ours, case["armada"], deviations[name] ?: emptySet(), mismatches)
        }
        assertEquals(emptyList(), mismatches)
    }

    /**
     * Channel order ([com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelOrder]): Armada
     * puts the Channels its legacy `custom["armada.order"]` positions first and the rest by name, and
     * so does the fold's [ConcordCommunityState.orderedChannels]; Channels we write (no position) read
     * by name on both sides.
     */
    @Test
    fun channelOrder() {
        val theirs = load("armada.json")
        val theirCase = theirs["cases"]!!.jsonArray.map { it.jsonObject }.first { name(it) == "channel_order" }
        val theirState = stateOf(theirs["control"]!!.jsonArray.map(::rumor) + theirCase["rumors"]!!.jsonArray.map(::rumor))
        val expected = theirCase["expect"]!!.jsonObject["order"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("c1", "c2", "c3", "9a").map { it.repeat(32) }, expected)
        assertEquals(expected, theirState.orderedChannels.map { it.channelIdHex })

        val ours = load("ours-read-by-armada.json")["cases"]!!.jsonArray.map { it.jsonObject }.first { name(it) == "channel_order" }
        val state = stateOf(fx.ourControl() + ours["rumors"]!!.jsonArray.map(::rumor))
        assertEquals(state.orderedChannels.map { it.channelIdHex }, ours["armada"]!!.jsonObject["order"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
