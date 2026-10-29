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
package com.vitorpamplona.quartz.drss.readWriteLists

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.drss.readWriteLists.tags.ListRoleTag
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * drss.io's read and write lists, a NIP-78 preference (kind 30078, `d` = [D_TAG]): the list or
 * folder the reader's river shows ([readList]) and the one its follows and saves go into
 * ([writeList]). Each is an `a` ([ListRoleTag]); drss writes one with no role when both are the
 * same folder.
 *
 * Not searchable: a preference, with no text of its own.
 *
 * **Kind 30078 is every app's preferences.** `EventFactory` builds this class for [isReadWriteLists]
 * tags and the generic `AppSpecificDataEvent` for every other `d`.
 */
@Immutable
class DrssReadWriteListsEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressHintProvider {
    /** Every designated list (`a`), with its role. */
    fun lists(): List<ListRoleTag> = tags.lists()

    /** The list or folder the river shows (`a` marked `read`, or unmarked). */
    fun readList(): ATag? = tags.readList()

    /** The list or folder follows and saves go into (`a` marked `write`, or unmarked). */
    fun writeList(): ATag? = tags.writeList()

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    /** `CURATED`: the designated lists (`a`). */
    override fun linkedAddressIds(): List<String> = tags.listAddressIds()

    companion object {
        const val KIND = 30078
        const val D_TAG = "read-write-lists"

        fun createAddress(pubKey: HexKey) = Address(KIND, pubKey, D_TAG)

        /** True for drss's preference: `d` = [D_TAG] and at least one `a`. */
        fun isReadWriteLists(tags: TagArray): Boolean = tags.fastAny { it.size > 1 && it[0] == DTag.TAG_NAME && it[1] == D_TAG } && tags.fastAny(ATag::isTagged)

        /** [read] is what the river shows, [write] where follows and saves go; often the same folder. */
        fun build(
            read: ATag,
            write: ATag = read,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssReadWriteListsEvent>.() -> Unit = {},
        ) = eventTemplate<DrssReadWriteListsEvent>(KIND, "", createdAt) {
            dTag(D_TAG)
            lists(read, write)
            initializer()
        }
    }
}
