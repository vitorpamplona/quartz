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

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordRootAuthentication
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 * Conformance against Vector, through the fixtures Armada ships (`src/concord/lib/fixtures/cord25`):
 * each is a Community Vector built with its own service code on an in-memory relay (every event it
 * published, the keys a member holds) and the Roster and permissions Vector folded from it. Quartz
 * must fold the same events to the same answer.
 */
class RankedRosterFixtureTest {
    class Fixture(
        val name: String,
        val json: JsonObject,
    ) {
        val community = json["community"]!!.jsonObject
        val vector = json["vector"]!!.jsonObject
        val id = community["id"]!!.jsonPrimitive.content
        val owner = community["owner"]!!.jsonPrimitive.content
        val root = community["root"]!!.jsonPrimitive.content
        val rootEpoch = community["root_epoch"]!!.jsonPrimitive.long
        val controlPk = (community["control_pk"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val events: List<Event> = json["events"]!!.jsonArray.map { Event.fromJson(it.toString()) }
        val roles = vector["roles"]!!.jsonObject
        val channels = vector["channels"]!!.jsonArray.map { it.jsonObject }
    }

    private fun load(name: String) = Fixture(name, Json.parseToJsonElement(TestResourceLoader().loadString("concord/cord25/$name.json")).jsonObject)

    private val fixtures = listOf("guild", "switched", "members", "history", "writers", "refounded").map(::load)

    private fun editionsOf(f: Fixture): List<ControlEdition> {
        val read = ConcordKeyDerivation.controlPlaneKey(f.root.hexToByteArray(), f.id.hexToByteArray(), f.rootEpoch)
        val address = f.controlPk ?: read.publicKeyHex
        return f.events
            .filter { it.kind == 1059 && it.pubKey == address }
            .mapNotNull { ConcordStreamEnvelope.openOrNull(it, address, read.conversationKey) }
            .mapNotNull { ControlEdition.fromOpened(it) }
    }

    private fun fold(f: Fixture): ConcordCommunityState {
        val keys = f.channels.filter { it["key"] !is JsonNull }.associate { it["id"]!!.jsonPrimitive.content to (it["key"]!!.jsonPrimitive.content to it["epoch"]!!.jsonPrimitive.long) }
        return ConcordCommunityState.fold(editionsOf(f), f.id.hexToByteArray(), f.owner, emptyMap()) { channel, epoch ->
            val (key, held) = keys[channel] ?: return@fold null
            if (held != epoch) null else ConcordChannelKeys.privateChannel(key.hexToByteArray(), channel.hexToByteArray(), epoch).conversationKey
        }
    }

    private fun bits(e: Any?): String = (e as JsonPrimitive).content

    @Test
    fun opensTheControlPlane() {
        for (f in fixtures) assertTrue(editionsOf(f).isNotEmpty(), f.name)
    }

    @Test
    fun agreesWhetherTheCommunityIsOnRolesV2() {
        for (f in fixtures) {
            val activated =
                f.vector["activated"]!!
                    .jsonPrimitive.content
                    .toBoolean()
            assertEquals(activated, fold(f).isRolesV2, f.name)
        }
    }

    @Test
    fun foldsTheSameRolesEveryoneAndGrants() {
        for (f in fixtures) {
            val roster = fold(f).authority.rankedRoster() ?: continue
            assertEquals((f.roles["everyone"] as? JsonPrimitive)?.content, roster.everyone?.toWire(), "${f.name} @everyone")
            val mine = roster.roles.map { listOf(it.roleId, it.name, it.rank, it.permissions.toWire()) }.sortedBy { it[0] }
            val theirs =
                f.roles["roles"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .map {
                        listOf(it["role_id"]!!.jsonPrimitive.content, it["name"]!!.jsonPrimitive.content, it["rank"]?.jsonPrimitive?.content, bits(it["permissions"]))
                    }.sortedBy { it[0] }
            assertEquals(theirs, mine, "${f.name} roles")
            val myGrants = roster.grants.map { it.member + ":" + it.roleIds.sorted().joinToString(",") }.sorted()
            val theirGrants =
                f.roles["grants"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .map { g ->
                        g["member"]!!.jsonPrimitive.content + ":" +
                            g["role_ids"]!!
                                .jsonArray
                                .map { it.jsonPrimitive.content }
                                .sorted()
                                .joinToString(",")
                    }.sorted()
            assertEquals(theirGrants, myGrants, "${f.name} grants")
            val legacy = (f.roles["legacy_channels"] as? JsonArray)?.map { it.jsonPrimitive.content }
            assertEquals(legacy, roster.legacyChannels, "${f.name} legacy channels")
        }
    }

    @Test
    fun givesEveryMemberTheSameServerPermissions() {
        for (f in fixtures) {
            val state = fold(f)
            if (!state.isRolesV2) continue
            for ((label, m) in f.vector["members"]!!.jsonObject) {
                val pubkey = m.jsonObject["pubkey"]!!.jsonPrimitive.content
                if (pubkey == f.owner) continue
                assertEquals(m.jsonObject["server"]!!.jsonPrimitive.content, state.authority.effectivePermissions(pubkey).toWire(), "${f.name} $label")
            }
        }
    }

    @Test
    fun givesEveryMemberTheSamePermissionsInEveryChannel() {
        for (f in fixtures) {
            val state = fold(f)
            if (!state.isRolesV2) continue
            for ((label, m) in f.vector["members"]!!.jsonObject) {
                val pubkey = m.jsonObject["pubkey"]!!.jsonPrimitive.content
                for ((channel, expected) in m.jsonObject["channels"]!!.jsonObject) {
                    assertEquals(expected.jsonPrimitive.content, state.channelPermissions(pubkey, channel).toWire(), "${f.name} $label in $channel")
                }
            }
        }
    }

    @Test
    fun foldsAndOpensEveryChannel() {
        for (f in fixtures) {
            val state = fold(f)
            for (ch in f.channels) {
                val id = ch["id"]!!.jsonPrimitive.content
                val mine = state.channels[id]
                assertTrue(mine != null, "${f.name} folds #${ch["name"]}")
                assertEquals(ch["private"]!!.jsonPrimitive.content.toBoolean(), mine.definition.private, "${f.name} #${ch["name"]} private")
                assertEquals(ch["name"]!!.jsonPrimitive.content, mine.definition.name, "${f.name} #${ch["name"]} name")
            }
        }
    }

    @Test
    fun honorsAModeratorsHideOnlyOverMembersTheyOutrank() {
        val f = fixtures.first { it.name == "guild" }
        val state = fold(f)
        val members =
            f.vector["members"]!!.jsonObject.mapValues {
                it.value.jsonObject["pubkey"]!!
                    .jsonPrimitive.content
            }
        val general =
            f.channels
                .first { it["name"]!!.jsonPrimitive.content == "general" }["id"]!!
                .jsonPrimitive.content
        val mod = members.getValue("bob")
        val cite = state.authority.citationFor(mod)
        assertTrue(state.honorsModerationHide(mod, members.getValue("carol"), general, cite), "a Mod hides a VIP's post")
        assertTrue(!state.honorsModerationHide(mod, members.getValue("alice"), general, cite), "never an Admin's")
        assertTrue(!state.honorsModerationHide(mod, members.getValue("carol"), general, null), "a hide cites its Grant")
        assertTrue(!state.honorsModerationHide(members.getValue("carol"), members.getValue("stranger"), general, state.authority.citationFor(members.getValue("carol"))), "MANAGE_MESSAGES is required")
    }

    @Test
    fun authenticatesTheDeliveredRootByTheGenesisAnchor() {
        for (f in fixtures) {
            val verdict = ConcordRootAuthentication.verify(editionsOf(f), f.id.hexToByteArray(), f.owner, f.rootEpoch)
            assertEquals(ConcordRootAuthentication.Verdict.AUTHENTIC, verdict, f.name)
        }
        val guild = fixtures.first { it.name == "guild" }
        val other = fixtures.first { it.name == "switched" }
        // Another Community's plane carries the wrong `eid`: refused, never waved through.
        assertEquals(ConcordRootAuthentication.Verdict.REFUSED, ConcordRootAuthentication.verify(editionsOf(other), guild.id.hexToByteArray(), guild.owner, 0))
        assertEquals(ConcordRootAuthentication.Verdict.DEFER, ConcordRootAuthentication.verify(emptyList(), guild.id.hexToByteArray(), guild.owner, 0))
    }
}
