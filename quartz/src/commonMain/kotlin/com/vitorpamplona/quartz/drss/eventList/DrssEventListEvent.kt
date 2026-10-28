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
package com.vitorpamplona.quartz.drss.eventList

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.drss.onlyTagsNamed
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag

/**
 * A drss.io event list (kind 30102): a named collection of regular events, such as the audio
 * samples a generated show draws on or a reading list of papers. Each item is
 * `["e", <id>, <relay>, <added at>]`; `d` names the list, `title` is optional, no content.
 *
 * **Kind 30102 is shared** with a showcase app's votes, a model-sharing app's file announcements and
 * vouch counters. `EventFactory` builds this class for [isEventList] tags and
 * [UnrecognizedKind30102Event] for the rest.
 */
@Immutable
class DrssEventListEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider {
    fun title() = tags.firstNotNullOfOrNull(TitleTag::parse)

    /** The listed events, in tag order. */
    fun items(): List<HexKey> = tags.mapNotNull(ETag::parseId)

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint)

    override fun linkedEventIds(): List<HexKey> = items()

    companion object {
        const val KIND = 30102

        private val TAG_NAMES = setOf("d", ETag.TAG_NAME, TitleTag.TAG_NAME)

        /** True for drss's shape: one or more `e`, and no tag but `d`, `e` and `title`. */
        fun isEventList(tags: TagArray): Boolean = tags.onlyTagsNamed(TAG_NAMES, ETag.TAG_NAME)
    }
}
