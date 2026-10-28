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
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelNamespaceTag

/**
 * A drss.io provenance record (kind 31985): where the reader found a feed, and why they kept it.
 * Labelled with the NIP-32 namespace [NAMESPACE] (`L`, and `l` = `provenance`):
 *
 * - `d` and `p` = the feed bot the record is about;
 * - `a` = the article the reader found it through, when there is one;
 * - `i` = the source, a URL or other identifier, and `k` = its type (`web`);
 * - `content` = the reader's note.
 *
 * Records are grouped by [DrssProvenanceFolderEvent]s (kind 30104).
 *
 * **Kind 31985 is shared** with Ditto's ratings (`k`, `rating`) and a game log. `EventFactory`
 * builds this class for [isProvenance] tags and [UnrecognizedKind31985Event] for the rest.
 */
@Immutable
class DrssProvenanceEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    PubKeyHintProvider,
    AddressHintProvider {
    /** The feed bot this record is about. */
    fun feed(): HexKey? = tags.firstNotNullOfOrNull(PTag::parseKey)

    /** The article the reader found the feed through. */
    fun foundThrough(): ATag? = tags.firstNotNullOfOrNull(ATag::parse)

    /** Where the feed was found: a URL or other identifier (`i`). */
    fun source(): String? = tags.firstNotNullOfOrNull { if (it.size > 1 && it[0] == SOURCE) it[1].ifBlank { null } else null }

    /** The type of [source] (`k`), such as `web`. */
    fun sourceType(): String? = tags.firstNotNullOfOrNull { if (it.size > 1 && it[0] == SOURCE_TYPE) it[1].ifBlank { null } else null }

    /** The reader's note. */
    fun note(): String = content

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(feed())

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds(): List<String> = tags.mapNotNull(ATag::parseValidAddress)

    companion object {
        const val KIND = 31985

        const val NAMESPACE = "systems.buildtall.provenance"
        const val SOURCE = "i"
        const val SOURCE_TYPE = "k"

        /** True for drss's shape: the [NAMESPACE] label namespace (`L`). */
        fun isProvenance(tags: TagArray): Boolean = tags.fastAny { it.size > 1 && it[0] == LabelNamespaceTag.TAG_NAME && it[1] == NAMESPACE }
    }
}
