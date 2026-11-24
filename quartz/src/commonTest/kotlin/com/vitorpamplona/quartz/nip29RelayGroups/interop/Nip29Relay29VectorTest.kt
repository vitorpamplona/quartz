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
package com.vitorpamplona.quartz.nip29RelayGroups.interop

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NIP-29 interop with fiatjaf/relay29 (the NIP author's relay library, through go-nostr's nip29
 * package), both ways, from golden files in `commonTest/resources/nip29/` that
 * `quartz/tools/nip29-vector-gen` regenerates.
 *
 *  - `relay29.json` (THEIRS → US): the relay-signed events relay29 emitted while a group was created,
 *    named, joined, moderated, left and made private and closed: the 39000-39003 snapshots and the
 *    9000/9001 it issues for join and leave requests, each with `expect`, what go-nostr reads in it.
 *    Quartz must read each the same way ([Nip29Relay29Reading.state]).
 *  - `ours-read-by-relay29.json` (OURS → THEM): the events Quartz's builders write
 *    ([Nip29Relay29Fixture.ourCases]), each with `relay29`, what relay29's moderation parser made of
 *    it. Our builders must still write exactly those events, and relay29 must read them as Quartz does.
 *
 * Where relay29 and Quartz disagree on purpose, [deviations] names the keys and why.
 */
class Nip29Relay29VectorTest {
    private val fx = Nip29Relay29Fixture

    private fun load(name: String) = Json.parseToJsonElement(TestResourceLoader().loadString("nip29/$name")).jsonObject

    private fun event(element: JsonElement): Event = Event.fromJson(element.toString())

    private fun name(case: JsonObject) = case["name"]!!.jsonPrimitive.content

    /**
     * Keys where relay29 reads one of our events differently from Quartz on purpose.
     */
    private val deviations: Map<String, Set<String>> =
        mapOf(
            // relay29 (2025-06) predates kind 9009 create-invite (NIP-29 "Group state -- or moderation"):
            // PrepareModerationAction refuses it, so on relay29 a closed group admits nobody by code.
            "create_invite" to setOf("action", "code"),
        )

    /** Every key [ours] holds, compared with [theirs] at the same path. */
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
        if (ours != theirs) out.add("$path: quartz=$ours relay29=$theirs")
    }

    @Test
    fun quartzReadsRelay29StateAsGoNostrDoes() {
        val file = load("relay29.json")
        assertEquals(fx.relay, file["relay"]!!.jsonPrimitive.content)
        assertEquals(fx.GROUP, file["group_id"]!!.jsonPrimitive.content)
        val cases = file["cases"]!!.jsonArray.map { it.jsonObject }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val event = event(case["event"]!!)
            assertTrue(event.verify(), "${name(case)}: relay29's signature")
            assertEquals(fx.relay, event.pubKey, "${name(case)}: signed by the relay key")
            diff(name(case), Nip29Relay29Reading.state(event), case["expect"], emptySet(), mismatches)
        }
        assertEquals(emptyList(), mismatches)
        assertTrue(cases.size >= 16, "every case was read: ${cases.map(::name)}")
    }

    @Test
    fun ourBuildersStillWriteTheEventsRelay29Read() {
        val written = fx.ourCases().associateBy { it.name }
        val recorded = load("ours-read-by-relay29.json")["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(written.keys.toList(), recorded.map(::name), "regenerate with quartz/tools/nip29-vector-gen/gen.sh")
        for (case in recorded) {
            assertEquals(written.getValue(name(case)).event.id, event(case["event"]!!).id, "${name(case)}: our builder changed; regenerate with quartz/tools/nip29-vector-gen/gen.sh")
        }
    }

    @Test
    fun relay29ReadsOurEventsAsQuartzDoes() {
        val cases = load("ours-read-by-relay29.json")["cases"]!!.jsonArray.map { it.jsonObject }
        val mismatches = ArrayList<String>()
        for (case in cases) {
            val name = name(case)
            diff(name, Nip29Relay29Reading.signedAction(event(case["event"]!!)), case["relay29"], deviations[name] ?: emptySet(), mismatches)
        }
        assertEquals(emptyList(), mismatches)
    }
}
