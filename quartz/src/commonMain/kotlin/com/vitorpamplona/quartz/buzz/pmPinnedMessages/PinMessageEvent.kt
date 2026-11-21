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
package com.vitorpamplona.quartz.buzz.pmPinnedMessages

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.buzz.pmPinnedMessages.tags.ClientIdTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A Buzz NIP-PM pin command (`kind:40010`): a member asks the relay to put one message of a
 * channel at the front of that channel's pin list.
 *
 * The shape is NIP-PM's (`docs/nips/NIP-PM.md` in `block/buzz`, relay side
 * `buzz-relay/src/handlers/pins.rs`): exactly one `h` (the channel), exactly one `e` (a
 * 64-hex message id), empty content, and a fresh `client-id` so two commands in the same
 * second stay two events. The relay accepts it from anyone who may post in the channel (not a
 * guest, not in an archived channel), answers a resend with `duplicate:`, and publishes the
 * new [ChannelPinsEvent]. A client that gets no answer resends the same signed event; it never
 * signs a new one for the same action.
 *
 * Not searchable: it carries no text, only a reference.
 */
@Immutable
class PinMessageEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider {
    override fun eventHints(): List<EventIdHint> = tags.pinTargetHints()

    /** `PIN`: the message this command pins (`e`). */
    override fun linkedEventIds(): List<HexKey> = listOfNotNull(target())

    /** The channel id (`h`). */
    fun channel() = tags.pinChannel()

    /** The message this command pins (`e`). */
    fun target() = tags.pinTarget()

    /** The random `client-id` value, never read by the relay. */
    fun clientId() = tags.pinClientId()

    /** Whether it has the one-`h`, one-hex-`e`, empty-content shape the relay requires. */
    fun isWellFormed() = content.isEmpty() && tags.isWellFormedPinCommand()

    companion object {
        const val KIND = 40010

        fun build(
            channelId: String,
            targetEventId: HexKey,
            clientId: String = ClientIdTag.newValue(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<PinMessageEvent>.() -> Unit = {},
        ) = eventTemplate<PinMessageEvent>(KIND, "", createdAt) {
            pinChannel(channelId)
            pinTarget(targetEventId)
            pinClientId(clientId)
            initializer()
        }
    }
}
