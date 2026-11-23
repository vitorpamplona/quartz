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
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelAccess.AccessOpening
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelAccess.AccessVerdict
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a reader does with an `access` delivery it holds (CORD-03 §5), against Armada's
 * `fixtures/cord25/members.json`: settle, wait, or open; and an open that tells a signer that could
 * not answer apart from a blob that isn't ours. Ported from Amethyst's ConcordAccessDrain.
 */
class AccessDeliveryVerdictTest {
    private val json = Json.parseToJsonElement(TestResourceLoader().loadString("concord/cord25/members.json")).jsonObject
    private val actors = Json.parseToJsonElement(TestResourceLoader().loadString("concord/cord25/members.actors.json")).jsonObject
    private val community = json["community"]!!.jsonObject
    private val id = community["id"]!!.jsonPrimitive.content.hexToByteArray()
    private val owner = community["owner"]!!.jsonPrimitive.content
    private val root = community["root"]!!.jsonPrimitive.content.hexToByteArray()
    private val rootEpoch = community["root_epoch"]!!.jsonPrimitive.long
    private val controlPk = community["control_pk"]!!.jsonPrimitive.content
    private val events: List<Event> = json["events"]!!.jsonArray.map { Event.fromJson(it.toString()) }

    private fun actor(key: String) = actors[key]!!.jsonPrimitive.content

    private val state: ConcordCommunityState by lazy {
        val read = ConcordKeyDerivation.controlPlaneKey(root, id, rootEpoch)
        val editions =
            events
                .filter { it.pubKey == controlPk }
                .mapNotNull { ConcordStreamEnvelope.openOrNull(it, controlPk, read.conversationKey) }
                .mapNotNull { ControlEdition.fromOpened(it) }
        ConcordCommunityState.fold(editions, id, owner)
    }

    private fun accessDeliveries(channel: String): List<ChannelDelivery> {
        val plane = ConcordChannelAccess.plane(root, channel.hexToByteArray(), rootEpoch)
        return events
            .filter { it.pubKey == plane.publicKeyHex }
            .mapNotNull { ConcordStreamEnvelope.openOrNull(it, plane) }
            .mapNotNull { ConcordChannelAccess.parse(it) }
            .filter { it.form == ConcordChannelAccess.FORM_ACCESS }
    }

    @Test
    fun aDeliveryIsSettledKeptOrOpenedBeforeAnySignerCall() {
        val lounge = actor("lounge")
        val member = actor("member")
        val d = accessDeliveries(lounge).first { ConcordChannelAccess.holdsAccessBlobFor(it, member, lounge) }
        val trusted = { ConcordChannelAccess.deliveryTrusted(state, lounge, member, d.deliverer) }

        assertEquals(AccessVerdict.OPEN, ConcordChannelAccess.accessVerdict(d, lounge, member, heldEpoch = null, trusted))
        // Someone else's view: no blob at their locator, so nothing to wait for.
        assertEquals(AccessVerdict.NOT_FOR_ME, ConcordChannelAccess.accessVerdict(d, lounge, KeyPair().pubKey.toHexKey(), null) { true })
        // Another Channel's delivery is never this one's.
        assertEquals(AccessVerdict.NOT_FOR_ME, ConcordChannelAccess.accessVerdict(d, "ab".repeat(32), member, null) { true })
        // An epoch already held is stale.
        assertEquals(AccessVerdict.STALE, ConcordChannelAccess.accessVerdict(d, lounge, member, d.newEpoch) { true })
        // Too far ahead of the held epoch, or untrusted for now: kept for a later fold.
        assertEquals(AccessVerdict.WAIT, ConcordChannelAccess.accessVerdict(d, lounge, member, d.newEpoch - ConcordChannelAccess.MAX_EPOCH_JUMP - 1) { true })
        assertEquals(AccessVerdict.WAIT, ConcordChannelAccess.accessVerdict(d, lounge, member, null) { false })
    }

    @Test
    fun openingTellsASignerThatCouldNotAnswerFromABlobThatIsNotOurs() =
        runTest {
            val lounge = actor("lounge")
            val member = actor("member")
            val d = accessDeliveries(lounge).first { ConcordChannelAccess.holdsAccessBlobFor(it, member, lounge) }
            val signer = FlakySigner(NostrSignerInternal(KeyPair(actor("member_secret").hexToByteArray())))

            signer.failNext = true
            assertEquals(AccessOpening.SignerUnavailable, ConcordChannelAccess.openAccessFor(d, signer, lounge))

            val key = assertIs<AccessOpening.Key>(ConcordChannelAccess.openAccessFor(d, signer, lounge)).key
            assertTrue(key.contentEquals(ConcordChannelAccess.openAccess(d, signer, lounge.hexToByteArray())!!))

            assertEquals(AccessOpening.Refused, ConcordChannelAccess.openAccessFor(d, NostrSignerInternal(KeyPair()), lounge))
        }

    private class FlakySigner(
        private val inner: NostrSignerInternal,
    ) : NostrSigner(inner.pubKey) {
        var failNext = false

        override fun isWriteable() = inner.isWriteable()

        override suspend fun <T : Event> sign(
            createdAt: Long,
            kind: Int,
            tags: Array<Array<String>>,
            content: String,
        ): T = inner.sign(createdAt, kind, tags, content)

        override suspend fun nip04Encrypt(
            plaintext: String,
            toPublicKey: HexKey,
        ) = inner.nip04Encrypt(plaintext, toPublicKey)

        override suspend fun nip04Decrypt(
            ciphertext: String,
            fromPublicKey: HexKey,
        ) = inner.nip04Decrypt(ciphertext, fromPublicKey)

        override suspend fun nip44Encrypt(
            plaintext: String,
            toPublicKey: HexKey,
        ) = inner.nip44Encrypt(plaintext, toPublicKey)

        override suspend fun nip44Decrypt(
            ciphertext: String,
            fromPublicKey: HexKey,
        ): String {
            if (failNext) {
                failNext = false
                throw SignerExceptions.TimedOutException("signer busy")
            }
            return inner.nip44Decrypt(ciphertext, fromPublicKey)
        }

        override suspend fun decryptZapEvent(event: ZapRequestEvent): PrivateZapEvent = inner.decryptZapEvent(event)

        override suspend fun deriveKey(nonce: HexKey) = inner.deriveKey(nonce)

        override suspend fun signPsbt(psbtHex: String) = inner.signPsbt(psbtHex)

        override fun hasForegroundSupport() = inner.hasForegroundSupport()
    }
}
