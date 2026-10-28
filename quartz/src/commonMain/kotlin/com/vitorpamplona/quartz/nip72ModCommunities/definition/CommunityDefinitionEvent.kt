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
package com.vitorpamplona.quartz.nip72ModCommunities.definition

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.DescriptionTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.ImageTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.ModeratorTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.NameTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.RelayTag
import com.vitorpamplona.quartz.nip72ModCommunities.definition.tags.RulesTag
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
class CommunityDefinitionEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    AddressHintProvider,
    PubKeyHintProvider,
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(name(), description(), rules(), content).joinToString("\n")

    // The read path: hands over the same fields indexableContent() joins, without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(name())) return
        if (!visitor.visit(description())) return
        if (!visitor.visit(rules())) return
        visitor.visit(content)
    }

    override fun eventHints() = tags.mapNotNull(ETag::parseAsHint) + tags.mapNotNull(QTag::parseEventAsHint)

    /** `MENTION`: events tagged with `e` or quoted with `q` (NIP-72 defines neither on a community). */
    override fun linkedEventIds() = mentionedEventIds() + quotedEventIds()

    override fun addressHints() =
        tags.mapNotNull(ATag::parseAsHint) +
            tags.mapNotNull(QTag::parseAddressAsHint) +
            tags.mapNotNull(RelayTag::parse).map { AddressHint(addressTag(), it.url) }

    /** `MENTION`: addresses tagged with `a` or quoted with `q` (NIP-72 defines neither on a community). */
    override fun linkedAddressIds() = mentionedAddressIds() + quotedAddressIds()

    override fun pubKeyHints() = tags.mapNotNull(ModeratorTag::parseAsHint)

    /** `MODERATOR`: the community's moderators (`p`), without the owner [moderatorKeys] adds. */
    override fun linkedPubKeys() = tags.mapNotNull(ModeratorTag::parseKey)

    /** The ids of events tagged with `e`, in tag order. NIP-72 defines no `e` here; clients may add them. */
    fun mentionedEventIds(): List<HexKey> = tags.mapNotNull(ETag::parseId)

    /** The ids of events quoted with `q` (NIP-18), in tag order. */
    fun quotedEventIds(): List<HexKey> = tags.mapNotNull(QTag::parseEventId)

    /** The ids of addresses tagged with `a`, in tag order. NIP-72 defines no `a` here; clients may add them. */
    fun mentionedAddressIds(): List<String> = tags.mapNotNull(ATag::parseAddressId)

    /** The ids of addresses quoted with `q` (NIP-18), in tag order. */
    fun quotedAddressIds(): List<String> = tags.mapNotNull(QTag::parseAddressId)

    fun name() = tags.firstNotNullOfOrNull(NameTag::parse)

    fun description() = tags.firstNotNullOfOrNull(DescriptionTag::parse)

    fun image() = tags.firstNotNullOfOrNull(ImageTag::parse)

    fun rules() = tags.firstNotNullOfOrNull(RulesTag::parse)

    fun moderators() = tags.mapNotNull(ModeratorTag::parse)

    fun moderatorKeys() = tags.mapNotNull(ModeratorTag::parseKey).plus(pubKey)

    fun relays() = tags.mapNotNull(RelayTag::parse)

    fun relayUrls() = tags.mapNotNull(RelayTag::parseUrls)

    companion object {
        const val KIND = 34550
        const val KIND_STR = "34550"

        @OptIn(ExperimentalUuidApi::class)
        fun build(
            name: String,
            description: String,
            moderators: List<ModeratorTag>,
            image: String? = null,
            rules: String? = null,
            relays: List<RelayTag>? = null,
            dTag: String = Uuid.random().toString(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<CommunityDefinitionEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            dTag(dTag)
            name(name)
            description(description)
            moderators(moderators)

            relays?.let { relays(it) }
            rules?.let { rules(it) }
            image?.let { image(image) }

            initializer()
        }
    }
}
