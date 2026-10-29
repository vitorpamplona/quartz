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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.concord.cord03Channels.tags.MsTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A Concord Chat Plane **message edit** (CORD-02 Appendix B, `kind:3302`).
 *
 * A dedicated edit rumor — NOT a kind-1010 modification and NOT a delete +
 * republish — matching the Concord v2 reference client (Soapbox Armada's
 * `KIND_EDIT`, `src/concord-v2/lib/kinds.ts`). It names the target message with a
 * single `["e", <id>]` tag and carries the replacement text as its content; the
 * usual channel/epoch binding tags scope it to its plane. Receivers overlay the
 * newest edit **authored by the original message's author** onto that message
 * (latest wins), non-destructively — the original keeps its id, so reactions,
 * replies, and quotes stay attached (see `LocalCache.findLatestConcordEditForNote`).
 */
@Immutable
class ConcordChatEditEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    SearchableEvent {
    override fun indexableContent() = content

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(content)
    }

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint)

    // CORD-02 Appendix B names the target with a single `e` tag, so the edited message is the only link.
    override fun linkedEventIds(): List<HexKey> = listOfNotNull(editedMessageId())

    /** The id of the message this edit replaces (its first valid `e` tag), or null if malformed. */
    fun editedMessageId(): HexKey? = tags.firstNotNullOfOrNull(ETag::parseId)

    /**
     * The full-precision send time in epoch-milliseconds: `createdAt * 1000` plus the `["ms", <0..999>]`
     * remainder tag (CORD-02 §4). Used to order competing edits at sub-second precision, matching the
     * reference client (an absent/malformed `ms` tag reads as 0). "Latest edit wins" compares this.
     */
    fun orderingMs(): Long = MsTag.orderingMs(createdAt, tags) ?: (createdAt * 1000)

    companion object {
        const val KIND = 3302

        /**
         * The unsigned edit rumor: [newText] replaces [targetId], bound to [channelId]/[epoch].
         * [ChannelChat.edit] assembles the same tags into a rumor, plus any extra tags.
         */
        fun build(
            channelId: HexKey,
            epoch: Long,
            targetId: HexKey,
            newText: String,
            createdAt: Long = TimeUtils.now(),
            ms: Int = MsTag.remainderFor(createdAt),
            initializer: TagArrayBuilder<ConcordChatEditEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, newText, createdAt) {
            channelBinding(channelId, epoch, ms)
            editedMessage(targetId)
            initializer()
        }
    }
}
