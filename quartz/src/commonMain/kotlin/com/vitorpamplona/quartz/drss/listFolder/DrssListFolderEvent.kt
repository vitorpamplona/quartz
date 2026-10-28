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
import com.vitorpamplona.quartz.drss.onlyAddressesOfKinds
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag

/**
 * A drss.io list folder (kind 30101): a titled node that groups the reader's lists into a tree.
 * Each `a` is a child: a NIP-51 follow set of feeds (30000), a bookmark set (30003), an article
 * curation set (30004), an older drss feed list (30100), event list (30102), provenance folder
 * (30104) or another folder. The root has `d` = `drss`; drss also writes `language`, and an OPML
 * import adds `import-source`, `import-date` and a one-line summary as `content`.
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
    AddressHintProvider {
    fun title() = tags.firstNotNullOfOrNull(TitleTag::parse)

    fun language() = tags.firstNotNullOfOrNull { if (it.size > 1 && it[0] == LANGUAGE) it[1].ifBlank { null } else null }

    /** The lists and folders this folder holds, in tag order. */
    fun children(): List<ATag> = tags.mapNotNull(ATag::parse)

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds(): List<String> = tags.mapNotNull(ATag::parseValidAddress)

    companion object {
        const val KIND = 30101

        const val LANGUAGE = "language"

        /** What a drss folder may hold: NIP-51 sets and drss's own list kinds. */
        private val CHILD_KINDS = setOf("30000", "30003", "30004", "30100", "30101", "30102", "30104")

        /** True for drss's shape: one or more `a`, each naming a list or folder kind. */
        fun isListFolder(tags: TagArray): Boolean = tags.onlyAddressesOfKinds(CHILD_KINDS)
    }
}
