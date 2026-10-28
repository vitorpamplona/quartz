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
package com.vitorpamplona.quartz.nipACWebRtcCalls

import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallAnswerEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallHangupEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallIceCandidateEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallOfferEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallRejectEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallRenegotiateEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.WebRTCEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.tags.CallType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Every NIP-AC signal links its `p` recipients and hands over their relay hints. */
class CallHintProviderTest {
    private val id = "00".repeat(32)
    private val sig = "00".repeat(64)
    private val caller = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val a = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val b = "99bb5591c9116600f845107d31f9b59e2f7c7e09a1ff802e84f1d43da557ca64"
    private val relay = "wss://relay.damus.io/"

    private val tags =
        arrayOf(
            arrayOf("p", a, relay),
            arrayOf("p", "not-a-key"),
            arrayOf("p", b),
            arrayOf("call-id", "c1"),
        )

    private val signals: List<WebRTCEvent> =
        listOf(
            CallOfferEvent(id, caller, 1, tags, "sdp", sig),
            CallAnswerEvent(id, caller, 1, tags, "sdp", sig),
            CallIceCandidateEvent(id, caller, 1, tags, "{}", sig),
            CallHangupEvent(id, caller, 1, tags, "", sig),
            CallRejectEvent(id, caller, 1, tags, "", sig),
            CallRenegotiateEvent(id, caller, 1, tags, "sdp", sig),
        )

    @Test
    fun everySignalLinksItsRecipients() {
        signals.forEach { signal ->
            val name = signal::class.simpleName
            assertIs<PubKeyHintProvider>(signal, name)
            assertEquals(listOf(a, b), signal.recipientKeys(), name)
            assertEquals(listOf(a, b), signal.recipients().map { it.pubKey }, name)
            assertEquals(listOf(a, b), signal.linkedPubKeys(), name)
            assertEquals(listOf(a), signal.pubKeyHints().map { it.pubkey }, name)
            assertEquals(listOf(relay), signal.pubKeyHints().map { it.relay.url }, name)
        }
    }

    @Test
    fun recipientPubKeysIsTheSetOfRecipientKeys() {
        val offer = CallOfferEvent(id, caller, 1, tags + arrayOf(arrayOf("p", a)), "sdp", sig)
        assertEquals(listOf(a, b, a), offer.recipientKeys())
        assertEquals(setOf(a, b), offer.recipientPubKeys())
        assertEquals(setOf(a, b, caller), offer.groupMembers())
    }

    @Test
    fun aBuiltGroupOfferLinksEveryCallee() {
        val template = CallOfferEvent.build("sdp", setOf(a, b), "c1", CallType.VIDEO)
        val offer = CallOfferEvent(id, caller, template.createdAt, template.tags, template.content, sig)
        assertEquals(setOf(a, b), offer.linkedPubKeys().toSet())
        assertEquals(emptyList(), offer.pubKeyHints())
    }
}
