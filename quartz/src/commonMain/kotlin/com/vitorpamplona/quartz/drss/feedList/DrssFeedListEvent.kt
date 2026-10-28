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
import com.vitorpamplona.quartz.drss.onlyTagsNamed
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag

/**
 * A drss.io feed list (kind 30100), the format drss wrote before it moved its lists to NIP-51
 * follow sets (30000): one `p` per bridged feed, as `["p", <feed bot>, <relay>, <feed title>]`, `d`
 * like `drss/<list name>`, no content.
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
    PubKeyHintProvider {
    fun title() = tags.firstNotNullOfOrNull(TitleTag::parse)

    /** The feed bots in the list, in tag order. */
    fun feeds(): List<HexKey> = tags.mapNotNull(PTag::parseKey)

    /** A feed's title, as the list names it (the `p` tag's fourth value). */
    fun feedTitle(feed: HexKey): String? = tags.firstNotNullOfOrNull { if (it.size > 3 && it[0] == PTag.TAG_NAME && it[1] == feed) it[3].ifBlank { null } else null }

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys(): List<HexKey> = feeds()

    companion object {
        const val KIND = 30100

        private val TAG_NAMES = setOf("d", PTag.TAG_NAME, TitleTag.TAG_NAME)

        /** True for drss's shape: one or more `p`, and no tag but `d`, `p` and `title`, with no content. */
        fun isFeedList(
            tags: TagArray,
            content: String,
        ): Boolean = content.isBlank() && tags.onlyTagsNamed(TAG_NAMES, PTag.TAG_NAME)
    }
}
