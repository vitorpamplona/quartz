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
package com.vitorpamplona.quartz.drss.feedList

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.drss.feedList.tags.FeedTag
import com.vitorpamplona.quartz.drss.onlyTagsNamed
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A drss.io feed list (kind 30100), the format drss wrote before it moved its lists to NIP-51
 * follow sets (30000): one [FeedTag] per bridged feed, `["p", <feed bot>, <relay>, <feed title>]`,
 * `d` like `drss/<list name>`, an optional `title`, no content.
 *
 * Searchable by its title and its feeds' titles, which are public names.
 *
 * **Kind 30100 is shared** with a CI runner's job statuses, room listings, a Brazilian showcase app's
 * projects, a product catalog and encrypted app data. `EventFactory` builds this class for
 * [isFeedList] tags and [UnrecognizedKind30100Event] for the rest.
 */
@Immutable
class DrssFeedListEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    PubKeyHintProvider,
    SearchableEvent {
    fun title() = tags.title()

    /** The feeds in the list (`p`), in tag order. */
    fun feeds(): List<FeedTag> = tags.feeds()

    /** The bot keys of [feeds] (`p`), in tag order. */
    fun feedKeys(): List<HexKey> = tags.feedKeys()

    /** The titles the list gives its feeds (the `p` tags' fourth value), in tag order. */
    fun feedTitles(): List<String> = tags.feedTitles()

    override fun indexableContent() = (listOfNotNull(title()) + feedTitles()).joinToString("\n")

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        for (tag in tags) {
            val feedTitle = FeedTag.parseTitle(tag) ?: continue
            if (!visitor.visit(feedTitle)) return
        }
    }

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(FeedTag::parseAsHint)

    /** `MEMBER`: the feed bots (`p`). */
    override fun linkedPubKeys(): List<HexKey> = feedKeys()

    companion object {
        const val KIND = 30100

        private val TAG_NAMES = setOf("d", FeedTag.TAG_NAME, TitleTag.TAG_NAME)

        /** True for drss's shape: one or more `p`, and no tag but `d`, `p` and `title`, with no content. */
        fun isFeedList(
            tags: TagArray,
            content: String,
        ): Boolean = content.isBlank() && tags.onlyTagsNamed(TAG_NAMES, FeedTag.TAG_NAME)

        /** A list of [feeds]. Give at least one, or it parses back as an [UnrecognizedKind30100Event]. */
        fun build(
            dTag: String,
            feeds: List<FeedTag>,
            title: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssFeedListEvent>.() -> Unit = {},
        ) = eventTemplate<DrssFeedListEvent>(KIND, "", createdAt) {
            dTag(dTag)
            title?.let { title(it) }
            feeds(feeds)
            initializer()
        }
    }
}
