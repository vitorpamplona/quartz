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
import com.vitorpamplona.quartz.buzz.forum.ForumCommentEvent
import com.vitorpamplona.quartz.buzz.forum.ForumPostEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageDiffEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageV2Event
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A Buzz channel's pin list (NIP-PM, `kind:30623`): relay-signed, addressable, one per channel,
 * keyed by `d` = the channel id and carrying the same id in `h` (clients read it with
 * `{"kinds":[30623],"#h":[channel]}`; a `#d` filter gets no live updates). One `e` per pinned
 * message, newest pin first, which is the display order. No `e` tags, or no list at all, means no
 * pins. Members never write it: they send [PinMessageEvent] / [UnpinMessageEvent], and the relay
 * refuses this kind from clients.
 *
 * Clients must ignore a list not signed by the relay's NIP-11 `self` key, keep the newest
 * `created_at`, and skip an `e` whose message they cannot load (bulk erasure leaves them).
 *
 * Buzz uses this instead of NIP-29's `kind:39005`, which on Buzz is a thread summary
 * (`buzz-db/src/store/pins.rs` writes `d`, `h`, then the `e` tags).
 *
 * Not searchable: it carries no text, only references.
 */
@Immutable
class ChannelPinsEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider {
    override fun eventHints(): List<EventIdHint> = tags.pinnedMessageHints()

    /** `PIN`: the pinned messages (`e`). */
    override fun linkedEventIds(): List<HexKey> = pinnedMessageIds()

    /** The channel id: the `h` tag, else the `d` tag (both carry it). */
    fun channel(): String = tags.pinChannel() ?: dTag()

    /** The pinned message ids, newest pin first. */
    fun pinnedMessageIds(): List<HexKey> = tags.pinnedMessageIds()

    companion object {
        const val KIND = 30623

        /**
         * The kinds a channel can pin (`PINNABLE_KINDS` in `buzz-core/src/kind.rs`): stream messages
         * (9, 40002, 40008), forum posts (45001) and forum comments (45003). The relay refuses a pin of
         * anything else as `invalid: target is not a message in this channel`.
         */
        val PINNABLE_KINDS: Set<Int> = setOf(ChatEvent.KIND, StreamMessageV2Event.KIND, StreamMessageDiffEvent.KIND, ForumPostEvent.KIND, ForumCommentEvent.KIND)

        /** Whether a message of [kind] can be pinned on a Buzz channel. */
        fun isPinnable(kind: Int) = kind in PINNABLE_KINDS

        /** A channel holds at most this many pins; a pin past it is refused `invalid: pin limit reached`. */
        const val MAX_PINS = 250

        /** Builds a pin list for [channelId]; only a relay signs one. */
        fun build(
            channelId: String,
            pinnedMessageIds: List<HexKey>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChannelPinsEvent>.() -> Unit = {},
        ) = eventTemplate<ChannelPinsEvent>(KIND, "", createdAt) {
            dTag(channelId)
            pinChannel(channelId)
            pinnedMessages(pinnedMessageIds)
            initializer()
        }
    }
}
