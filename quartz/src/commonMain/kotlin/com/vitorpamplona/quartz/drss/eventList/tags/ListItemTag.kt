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
package com.vitorpamplona.quartz.drss.eventList.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.utils.ensure

/**
 * An item of a drss event list: the event, the relay that holds it and when it was added,
 * `["e", <id>, <relay or "">, <unix seconds>]`. The fourth value is a time, not NIP-10's marker or
 * author.
 */
@Immutable
data class ListItemTag(
    val eventId: HexKey,
    val relayHint: NormalizedRelayUrl? = null,
    val addedAt: Long? = null,
) {
    fun toTagArray() = assemble(eventId, relayHint, addedAt)

    companion object {
        const val TAG_NAME = ETag.TAG_NAME

        fun parse(tag: Array<String>): ListItemTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            val relay = tag.getOrNull(2)?.let { RelayUrlNormalizer.normalizeHintOrNull(it) }
            return ListItemTag(tag[1], relay, tag.getOrNull(3)?.toLongOrNull())
        }

        fun parseId(tag: Array<String>): HexKey? = ETag.parseId(tag)

        fun parseAsHint(tag: Array<String>): EventIdHint? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            val relay = RelayUrlNormalizer.normalizeHintOrNull(tag[2]) ?: return null
            return EventIdHint(tag[1], relay)
        }

        fun assemble(
            eventId: HexKey,
            relayHint: NormalizedRelayUrl?,
            addedAt: Long?,
        ) = if (addedAt == null) {
            if (relayHint == null) arrayOf(TAG_NAME, eventId) else arrayOf(TAG_NAME, eventId, relayHint.url)
        } else {
            arrayOf(TAG_NAME, eventId, relayHint?.url ?: "", addedAt.toString())
        }

        fun assemble(items: List<ListItemTag>) = items.map { it.toTagArray() }
    }
}
