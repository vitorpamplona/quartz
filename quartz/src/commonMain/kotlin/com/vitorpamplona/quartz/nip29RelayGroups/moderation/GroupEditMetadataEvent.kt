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
package com.vitorpamplona.quartz.nip29RelayGroups.moderation

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.buzz.workspace.BUZZ_VISIBILITY_OPEN
import com.vitorpamplona.quartz.buzz.workspace.BUZZ_VISIBILITY_PRIVATE
import com.vitorpamplona.quartz.buzz.workspace.BuzzChannelLabels
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.geohash.geohashes
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class GroupEditMetadataEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent {
    fun groupId() = tags.groupId()

    fun name() = tags.firstTagValue("name")

    fun about() = tags.firstTagValue("about")

    fun picture() = tags.firstTagValue("picture")

    fun banner() = tags.firstTagValue("banner")

    fun hashtags() = tags.hashtags()

    fun geohashes() = tags.geohashes()

    /** Subgroups: the requested parent group id, or null to (re-)root this group. */
    fun parent() = tags.parentGroupId()

    /**
     * Subgroups: the ordered child ids carried on this edit. Per NIP-29 a metadata
     * edit of a parent group MUST re-list all of its children, so the relay rejects
     * a `kind:9002` that drops any of them.
     */
    fun children() = tags.childGroupIds()

    fun previousEvents() = tags.previousEvents()

    override fun indexableContent() = (listOfNotNull(name(), about()) + hashtags()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, without the join.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(name())) return
        if (!visitor.visit(about())) return
        hashtags().forEach { if (!visitor.visit(it)) return }
    }

    companion object {
        const val KIND = 9002

        fun build(
            groupId: String,
            name: String? = null,
            about: String? = null,
            picture: String? = null,
            banner: String? = null,
            status: Set<GroupMetadataEvent.GroupStatus> = emptySet(),
            hashtags: List<String> = emptyList(),
            geohashes: List<String> = emptyList(),
            parent: String? = null,
            children: List<String> = emptyList(),
            previousEvents: List<String> = emptyList(),
            // Buzz-only channel-settings tags (a Buzz relay reads these on 9002; a plain NIP-29 relay
            // ignores them). [visibility] is "open"/"private" — Buzz's own vocabulary, which it reads
            // instead of the NIP-29 `private` status flag, so editing visibility on a Buzz channel needs
            // this tag to take. [archived] toggles the channel's archived state ("true"/"false").
            visibility: String? = null,
            archived: Boolean? = null,
            // Metadata tags this edit doesn't set itself (e.g. `livekit`, `supported_kinds`, a newer
            // spec field), usually GroupMetadataEvent.unmanagedTags() of the current 39000, so the
            // edit doesn't erase them. Tags whose names this builder manages are skipped.
            extraTags: List<Array<String>> = emptyList(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<GroupEditMetadataEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            groupId(groupId)
            name?.let { add(arrayOf("name", it)) }
            about?.let { add(arrayOf("about", it)) }
            picture?.let { add(arrayOf("picture", it)) }
            banner?.let { add(arrayOf("banner", it)) }
            status.forEach { add(arrayOf(it.code)) }
            visibility?.let { add(arrayOf("visibility", it)) }
            archived?.let { add(arrayOf("archived", if (it) "true" else "false")) }
            addAll(HashtagTag.assemble(hashtags))
            // Mip-map each geohash into every prefix so a coarser followed geohash still matches.
            geohashes.forEach { addAll(GeoHashTag.assemble(it).toList()) }
            parent?.let { parentGroup(it) }
            childGroups(children)
            previous(previousEvents)
            extraTags.forEach { if (it.isNotEmpty() && it[0] !in GroupMetadataEvent.EDIT_MANAGED_TAG_NAMES) add(it) }
            initializer()
        }

        /**
         * A metadata edit of [groupId] for whichever relay hosts it, starting from its [currentMetadata].
         *
         * On a Buzz relay ([isBuzz]) visibility rides a `visibility` ("open"/"private") tag, since the
         * relay does not read NIP-29's `private` flag; [hashtags] are dropped, because there `t` tags
         * are channel labels and the 39000's include the channel type, which the relay refuses as a
         * label; the labels change only through [buzzLabels] (null: unchanged; empty: cleared),
         * validated by [BuzzChannelLabels.editTags], which throws [IllegalArgumentException] on a set
         * the relay would refuse; and the 39000's tags this edit doesn't manage are not echoed, since
         * Buzz stamps relay-internal ones (`join_role`, `auto_join`, `P`, ...) a channel admin may not
         * set. A NIP-29 relay gets [currentMetadata]'s unmanaged tags verbatim
         * ([GroupMetadataEvent.unmanagedTags]), so an edit doesn't erase them.
         */
        fun buildEdit(
            groupId: String,
            currentMetadata: GroupMetadataEvent?,
            isBuzz: Boolean,
            name: String?,
            about: String?,
            picture: String?,
            banner: String?,
            status: Set<GroupMetadataEvent.GroupStatus>,
            hashtags: List<String>,
            geohashes: List<String>,
            parent: String?,
            children: List<String>,
            buzzLabels: List<String>?,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<GroupEditMetadataEvent> {
            val labelTags = if (isBuzz) BuzzChannelLabels.editTags(buzzLabels) else emptyList()
            return build(
                groupId,
                name = name,
                about = about,
                picture = picture,
                banner = banner,
                status = status,
                hashtags = if (isBuzz) emptyList() else hashtags,
                geohashes = geohashes,
                parent = parent,
                children = children,
                visibility = if (isBuzz) (if (GroupMetadataEvent.GroupStatus.PRIVATE in status) BUZZ_VISIBILITY_PRIVATE else BUZZ_VISIBILITY_OPEN) else null,
                extraTags = if (isBuzz) emptyList() else currentMetadata?.unmanagedTags() ?: emptyList(),
                createdAt = createdAt,
            ) {
                labelTags.forEach { add(it) }
            }
        }
    }
}

@Deprecated(
    "Renamed to GroupEditMetadataEvent. NIP-29 group events carry the Group prefix to keep them apart from NIP-43 relay membership events.",
    ReplaceWith("GroupEditMetadataEvent", "com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent"),
)
typealias EditMetadataEvent = GroupEditMetadataEvent
