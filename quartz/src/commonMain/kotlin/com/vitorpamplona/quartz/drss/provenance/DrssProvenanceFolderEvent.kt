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
package com.vitorpamplona.quartz.drss.provenance

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
 * A drss.io provenance folder (kind 30104): a titled node of the reader's provenance tree. Each `a`
 * is a [DrssProvenanceEvent] (31985) or a sub-folder; the root has `d` = `vault-provenance/_root`
 * and is itself a child of a list folder (30101).
 *
 * **Kind 30104 is shared** with a showcase app's round results. `EventFactory` builds this class for
 * [isProvenanceFolder] tags and [UnrecognizedKind30104Event] for the rest.
 */
@Immutable
class DrssProvenanceFolderEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressHintProvider {
    fun title() = tags.firstNotNullOfOrNull(TitleTag::parse)

    /** The provenance records and sub-folders in this folder, in tag order. */
    fun children(): List<ATag> = tags.mapNotNull(ATag::parse)

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds(): List<String> = tags.mapNotNull(ATag::parseValidAddress)

    companion object {
        const val KIND = 30104

        private val CHILD_KINDS = setOf(DrssProvenanceEvent.KIND.toString(), KIND.toString())

        /** True for drss's shape: one or more `a`, each naming a provenance record or folder. */
        fun isProvenanceFolder(tags: TagArray): Boolean = tags.onlyAddressesOfKinds(CHILD_KINDS)
    }
}
