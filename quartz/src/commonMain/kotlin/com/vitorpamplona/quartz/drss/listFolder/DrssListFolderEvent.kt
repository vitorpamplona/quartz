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
package com.vitorpamplona.quartz.drss.listFolder

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.drss.eventList.DrssEventListEvent
import com.vitorpamplona.quartz.drss.feedList.DrssFeedListEvent
import com.vitorpamplona.quartz.drss.onlyAddressesOfKinds
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceFolderEvent
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.update
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip51Lists.articleCurationSet.ArticleCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkSet.BookmarkSetEvent
import com.vitorpamplona.quartz.nip51Lists.followSet.FollowSetEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A drss.io list folder (kind 30101): a titled node that groups the reader's lists into a tree.
 * Each `a` is a child: a NIP-51 follow set of feeds (30000), a bookmark set (30003), an article
 * curation set (30004), an older drss feed list (30100), event list (30102), provenance folder
 * (30104) or another folder. The root has `d` = `drss`; drss also writes `language`, and an OPML
 * import adds `import-source`, `import-date` and a one-line summary as `content`.
 *
 * Searchable by its title, like the NIP-51 sets it holds. The import summary is generated
 * ("Root list with 0 feeds and 2 categories"), so it is not indexed.
 *
 * **Kind 30101 is shared** with a messaging app's room memberships, a game's match handshakes, a
 * showcase app's voting rounds and several encrypted formats. `EventFactory` builds this class for
 * [isListFolder] tags and [UnrecognizedKind30101Event] for the rest.
 */
@Immutable
class DrssListFolderEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressHintProvider,
    SearchableEvent {
    fun title() = tags.title()

    fun language() = tags.language()

    /** What an imported folder came from, such as `opml`. */
    fun importSource() = tags.importSource()

    /** When an import made the folder, as drss writes it (RFC 3339). */
    fun importDate() = tags.importDate()

    /** The lists and folders this folder holds (`a`), in tag order. */
    fun children(): List<ATag> = tags.children()

    /** The addresses of [children], as `kind:pubkey:d`. */
    fun childAddressIds(): List<String> = tags.childAddressIds()

    override fun indexableContent() = title() ?: ""

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(title())
    }

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    /** `CURATED`: the lists and folders in this folder (`a`). */
    override fun linkedAddressIds(): List<String> = childAddressIds()

    companion object {
        const val KIND = 30101

        /** What a drss folder may hold: NIP-51 sets and drss's own list kinds. */
        private val CHILD_KINDS =
            intArrayOf(
                FollowSetEvent.KIND,
                BookmarkSetEvent.KIND,
                ArticleCurationSetEvent.KIND,
                DrssFeedListEvent.KIND,
                KIND,
                DrssEventListEvent.KIND,
                DrssProvenanceFolderEvent.KIND,
            )

        /** True for drss's shape: one or more `a`, each naming a list or folder kind. */
        fun isListFolder(tags: TagArray): Boolean = tags.onlyAddressesOfKinds(CHILD_KINDS)

        /**
         * A folder of [children], which must be lists or folders of the kinds drss nests. Give at
         * least one, or it parses back as an [UnrecognizedKind30101Event].
         */
        fun build(
            dTag: String,
            title: String,
            children: List<ATag>,
            language: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssListFolderEvent>.() -> Unit = {},
        ) = eventTemplate<DrssListFolderEvent>(KIND, "", createdAt) {
            dTag(dTag)
            title(title)
            language?.let { language(it) }
            children(children)
            initializer()
        }

        /** [earlier] with [child] at the end; unchanged when it already holds that list. */
        fun addChild(
            earlier: DrssListFolderEvent,
            child: ATag,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssListFolderEvent> = earlier.update(createdAt) { addUniqueValueIfNew(child.toATagArray()) }

        /**
         * [earlier] without [child]. Taking out the last child leaves no folder drss can read: it
         * parses back as an [UnrecognizedKind30101Event], so delete the folder instead.
         */
        fun removeChild(
            earlier: DrssListFolderEvent,
            child: Address,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssListFolderEvent> = earlier.update(createdAt) { remove(ATag.TAG_NAME, child.toValue()) }

        /** [earlier] under a new [title]. */
        fun retitle(
            earlier: DrssListFolderEvent,
            title: String,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssListFolderEvent> = earlier.update(createdAt) { title(title) }
    }
}
