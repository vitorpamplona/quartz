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
import com.vitorpamplona.quartz.drss.eventList.tags.ListItemTag
import com.vitorpamplona.quartz.drss.onlyTagsNamed
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.update
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A drss.io event list (kind 30102): a named collection of regular events, such as the audio
 * samples a generated show draws on or a reading list of papers. Each item is a [ListItemTag],
 * `["e", <id>, <relay or "">, <added at>]`; `d` names the list, `title` is optional, no content.
 *
 * Searchable by its title.
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
    EventHintProvider,
    SearchableEvent {
    fun title() = tags.title()

    /** The listed events (`e`), in tag order. */
    fun items(): List<ListItemTag> = tags.items()

    /** The ids of [items] (`e`), in tag order. */
    fun itemIds(): List<HexKey> = tags.itemIds()

    override fun indexableContent() = title() ?: ""

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(title())
    }

    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ListItemTag::parseAsHint)

    /** `CURATED`: the listed events (`e`). */
    override fun linkedEventIds(): List<HexKey> = itemIds()

    companion object {
        const val KIND = 30102

        private val TAG_NAMES = setOf("d", ListItemTag.TAG_NAME, TitleTag.TAG_NAME)

        /** True for drss's shape: one or more `e`, and no tag but `d`, `e` and `title`. */
        fun isEventList(tags: TagArray): Boolean = tags.onlyTagsNamed(TAG_NAMES, ListItemTag.TAG_NAME)

        /** A list of [items]. Give at least one, or it parses back as an [UnrecognizedKind30102Event]. */
        fun build(
            dTag: String,
            items: List<ListItemTag>,
            title: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssEventListEvent>.() -> Unit = {},
        ) = eventTemplate<DrssEventListEvent>(KIND, "", createdAt) {
            dTag(dTag)
            title?.let { title(it) }
            items(items)
            initializer()
        }

        /** [earlier] with [item] at the end; unchanged when it already holds that event. */
        fun addItem(
            earlier: DrssEventListEvent,
            item: ListItemTag,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssEventListEvent> = earlier.update(createdAt) { addUniqueValueIfNew(item.toTagArray()) }

        /**
         * [earlier] without the event [eventId]. A list cannot lose its last item: it would parse
         * back as an [UnrecognizedKind30102Event], so delete the list instead.
         */
        fun removeItem(
            earlier: DrssEventListEvent,
            eventId: HexKey,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssEventListEvent> = earlier.update(createdAt) { remove(ListItemTag.TAG_NAME, eventId) }
    }
}
