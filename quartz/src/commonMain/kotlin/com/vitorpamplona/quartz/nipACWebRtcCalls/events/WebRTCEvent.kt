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
package com.vitorpamplona.quartz.nipACWebRtcCalls.events

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUserIds
import com.vitorpamplona.quartz.nipACWebRtcCalls.tags.CallIdTag

/**
 * A NIP-AC call signal. Its `p` tags name the call's members: the addressee in a one-to-one
 * call; in a group call, every member (all kinds but ICE candidates, which name only the peer).
 * A multi-device "answered/rejected elsewhere" signal names its own sender. The signals travel
 * inside ephemeral gift wraps (kind 21059), so these hints and links only apply once a signal
 * is unwrapped.
 */
@Immutable
abstract class WebRTCEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    kind: Kind,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, kind, tags, content, sig),
    PubKeyHintProvider {
    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    /** `RECIPIENT`: the users the signal names in `p`, without the sender (see the class KDoc). */
    override fun linkedPubKeys(): List<HexKey> = recipientPubKeys().filter { it != pubKey }

    fun callId() = tags.firstNotNullOfOrNull(CallIdTag::parse)

    /** All pubkeys referenced by `p` tags in this event, in tag order. */
    fun recipientPubKeys(): Set<HexKey> = tags.taggedUserIds().toSet()

    /**
     * All group members for this call: the `p`-tagged recipients plus the
     * event author. For 1-to-1 calls the set has two elements.
     */
    fun groupMembers(): Set<HexKey> = recipientPubKeys().plus(pubKey)
}
