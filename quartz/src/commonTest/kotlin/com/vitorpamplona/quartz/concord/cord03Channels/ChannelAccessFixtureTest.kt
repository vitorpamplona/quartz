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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The access plane (CORD-03 §5) and restricted Channels (CORD-03 §1), against the Vector fixtures
 * Armada ships (`fixtures/cord25/members.json`, `writers.json`) and the spec's certificate vector.
 */
class ChannelAccessFixtureTest {
    private class Fixture(
        name: String,
    ) {
        val json = Json.parseToJsonElement(TestResourceLoader().loadString("concord/cord25/$name.json")).jsonObject
        val actors = Json.parseToJsonElement(TestResourceLoader().loadString("concord/cord25/$name.actors.json")).jsonObject
        val community = json["community"]!!.jsonObject
        val id = community["id"]!!.jsonPrimitive.content.hexToByteArray()
        val owner = community["owner"]!!.jsonPrimitive.content
        val root = community["root"]!!.jsonPrimitive.content.hexToByteArray()
        val rootEpoch = community["root_epoch"]!!.jsonPrimitive.long
        val controlPk = community["control_pk"]!!.jsonPrimitive.content
        val events: List<Event> = json["events"]!!.jsonArray.map { Event.fromJson(it.toString()) }
        val channels: List<JsonObject> = json["vector"]!!.jsonObject["channels"]!!.jsonArray.map { it.jsonObject }

        fun actor(key: String) = actors[key]!!.jsonPrimitive.content

        val state: ConcordCommunityState by lazy {
            val read = ConcordKeyDerivation.controlPlaneKey(root, id, rootEpoch)
            val editions =
                events
                    .filter { it.pubKey == controlPk }
                    .mapNotNull { ConcordStreamEnvelope.openOrNull(it, controlPk, read.conversationKey) }
                    .mapNotNull { ControlEdition.fromOpened(it) }
            ConcordCommunityState.fold(editions, id, owner)
        }

        fun deliveries(channel: String): List<ChannelDelivery> {
            val plane = ConcordChannelAccess.plane(root, channel.hexToByteArray(), rootEpoch)
            return events.filter { it.pubKey == plane.publicKeyHex }.mapNotNull { ConcordStreamEnvelope.openOrNull(it, plane) }.mapNotNull { ConcordChannelAccess.parse(it) }
        }
    }

    @Test
    fun opensTheLoungeKeyVectorDeliveredFromADelivererTheFoldTrusts() =
        runTest {
            val f = Fixture("members")
            val lounge = f.actor("lounge")
            val member = f.actor("member")
            val deliveries = f.deliveries(lounge).filter { it.form == ConcordChannelAccess.FORM_ACCESS }
            assertTrue(deliveries.isNotEmpty())
            val d = deliveries.first()
            assertTrue(ConcordChannelAccess.deliveryTrusted(f.state, lounge, member, d.deliverer))
            assertFalse(ConcordChannelAccess.deliveryTrusted(f.state, lounge, "ab".repeat(32), d.deliverer))

            val signer = NostrSignerInternal(KeyPair(f.actor("member_secret").hexToByteArray()))
            val key = ConcordChannelAccess.openAccess(d, signer, lounge.hexToByteArray())
            val vector = f.channels.first { it["id"]!!.jsonPrimitive.content == lounge }
            assertEquals(vector["key"]!!.jsonPrimitive.content, key?.toHexKey())
            assertEquals(vector["epoch"]!!.jsonPrimitive.long, d.newEpoch)
        }

    @Test
    fun foldsTheWritersLedgerAndCollectsTheWriterKeyAndCertificate() =
        runTest {
            val f = Fixture("writers")
            val general = f.actor("general")
            val member = f.actor("member")
            val writerEpoch = f.actors["writer_epoch"]!!.jsonPrimitive.long
            val ledger = f.state.writers[general]
            assertNotNull(ledger)
            assertTrue(ledger.isRestricted)
            assertFalse(f.state.everyoneSends(general))
            val live = ledger.live!!
            assertEquals(writerEpoch, live.epoch)
            assertEquals(ConcordKeyDerivation.channelWriterKey(f.actor("writer_secret").hexToByteArray(), general.hexToByteArray(), writerEpoch).publicKeyHex, live.writerPubKey)

            val signer = NostrSignerInternal(KeyPair(f.actor("member_secret").hexToByteArray()))
            val writerScope = ConcordKeyDerivation.writerScope(general.hexToByteArray())
            val delivery = f.deliveries(general).first { it.scopeIdHex == writerScope.toHexKey() && member in it.certs }
            assertTrue(ConcordChannelAccess.deliveryTrusted(f.state, general, member, delivery.deliverer) || f.state.authority.isOwner(delivery.deliverer))
            assertEquals(f.actor("writer_secret"), ConcordChannelAccess.openAccess(delivery, signer, writerScope)?.toHexKey())
            val cert = delivery.certs.getValue(member)
            assertEquals(f.actor("writer_cert"), cert)
            assertTrue(ConcordWritersLedger.verifyCertificate(f.id, general.hexToByteArray(), writerEpoch, member, live.certifier!!, cert))
            assertFalse(ConcordWritersLedger.verifyCertificate(f.id, general.hexToByteArray(), writerEpoch, "ab".repeat(32), live.certifier, cert))
        }

    @Test
    fun readsAWritersPostAtTheWriterAddressAndDropsItAtTheReadAddress() {
        val f = Fixture("writers")
        val general = f.actor("general")
        val member = f.actor("member")
        val ledger = f.state.writers.getValue(general)
        val live = ledger.live!!
        val read = ConcordChannelKeys.publicChannel(f.root, general.hexToByteArray(), f.rootEpoch)
        val opened =
            f.events
                .filter { it.pubKey == live.writerPubKey }
                .mapNotNull { ConcordStreamEnvelope.openOrNull(it, live.writerPubKey, read.conversationKey) }
                .first { it.author == member }
        val rumor = ChannelChat.acceptWriterOpened(opened, general, f.rootEpoch, live.epoch)
        assertNotNull(rumor)
        assertEquals("a writer's post", rumor.content)
        assertNull(ChannelChat.acceptWriterOpened(opened, general, f.rootEpoch, live.epoch + 1))
        assertNull(ChannelChat.acceptOpened(opened, general, f.rootEpoch), "a `wepoch` rumor at the read address is dropped")
        val ms = ChannelChat.orderingMs(rumor)!!
        assertTrue(ledger.postStands(f.id, live, member, ms, ConcordWritersLedger.wcertOf(rumor), privileged = false))
        assertFalse(ledger.postStands(f.id, live, member, ms, null, privileged = false), "no certificate, no post")
    }

    @Test
    fun writerCertificateMatchesTheSpecVector() {
        val certifierSecret = ByteArray(32) { 5 }
        val certifier = KeyPair(certifierSecret).pubKey.toHexKey()
        assertEquals("62c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7", certifier)
        val cid = ByteArray(32) { 1 }
        val channel = ByteArray(32) { 2 }
        val writer = ByteArray(32) { 4 }
        val d = ConcordWritersLedger.certificateDigest(cid, channel, 3, writer)
        assertEquals("f49b5c7359b09366b5b96e074d2b7c5a84bd714a04bf0e6270d13c02d453e619", d.toHexKey())
        assertEquals("1b6fcc32de4a485d7b0109b0e5dd91b7c676fcb26f7dc41c8c2bdc7e3f2cbe90", ConcordWritersLedger.certificateEventId(certifier, d).toHexKey())
        val sig = "cd1c3030e1220c539f1efb50d32e5b83432baa20eaa8709aa935050dc11a6ac6e25c3f9555b729cee65829fdbf1ba5e7656865a4fca860e42b05c8341922895a"
        assertTrue(ConcordWritersLedger.verifyCertificate(cid, channel, 3, writer.toHexKey(), certifier, sig))
        val mine = ConcordWritersLedger.signCertificate(cid, channel, 3, writer.toHexKey(), certifierSecret, certifier)
        assertTrue(ConcordWritersLedger.verifyCertificate(cid, channel, 3, writer.toHexKey(), certifier, mine))
    }
}
