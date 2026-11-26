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
package com.vitorpamplona.quartz.nip22Comments

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.kinds.ContentFormat
import com.vitorpamplona.quartz.kinds.FormattedContentEvent
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.any
import com.vitorpamplona.quartz.nip01Core.core.appendTo
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNullTo
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.geohash.GeoHashTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip10Notes.BaseThreadedEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip19Bech32.addressHintsTo
import com.vitorpamplona.quartz.nip19Bech32.addressIdsTo
import com.vitorpamplona.quartz.nip19Bech32.eventHintsTo
import com.vitorpamplona.quartz.nip19Bech32.eventIdsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeyHintsTo
import com.vitorpamplona.quartz.nip19Bech32.pubKeysTo
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyEventTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.ReplyKindTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAddressTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootAuthorTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootEventTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootIdentifierTag
import com.vitorpamplona.quartz.nip22Comments.tags.RootKindTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip57Zaps.splits.ZapSplitSetupParser
import com.vitorpamplona.quartz.nip57Zaps.splits.zapSplitPubKeys
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.nip73ExternalIds.ExternalId
import com.vitorpamplona.quartz.nip73ExternalIds.location.GeohashId
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class CommentEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseThreadedEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    RootScope,
    EventHintProvider,
    PubKeyHintProvider,
    AddressHintProvider,
    SearchableEvent,
    FormattedContentEvent {
    override fun contentFormat() = ContentFormat.PLAIN_TEXT

    override fun indexableContent() = (listOf(content) + tags.hashtags()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, without the join.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(content)) return
        // Inline over the tags rather than tags.hashtags(): this runs per event per search keystroke.
        for (tag in tags) {
            val hashtag = HashtagTag.parse(tag) ?: continue
            if (!visitor.visit(hashtag)) return
        }
    }

    // Runs on every relay copy of every comment: one list, filled in the order the old
    // `P + p + zap + nip19` concatenation produced, instead of five lists and three copies.
    override fun pubKeyHints(): List<PubKeyHint> {
        var out: ArrayList<PubKeyHint>? = null
        out = tags.fastMapNotNullTo(out, RootAuthorTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, ReplyAuthorTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, ZapSplitSetupParser::parseAsHint)
        out = citedNIP19().pubKeyHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedPubKeys(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = rootAuthorKeys().appendTo(out)
        out = replyAuthorKeys().appendTo(out)
        out = tags.zapSplitPubKeys().appendTo(out)
        out = citedNIP19().pubKeysTo(out)
        return out ?: emptyList()
    }

    override fun eventHints(): List<EventIdHint> {
        var out: ArrayList<EventIdHint>? = null
        out = tags.fastMapNotNullTo(out, RootEventTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, ReplyEventTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, QTag::parseEventAsHint)
        out = citedNIP19().eventHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedEventIds(): List<HexKey> {
        var out: ArrayList<HexKey>? = null
        out = rootEventIds().appendTo(out)
        out = replyEventIds().appendTo(out)
        out = quotedEventIds().appendTo(out)
        out = citedNIP19().eventIdsTo(out)
        return out ?: emptyList()
    }

    override fun addressHints(): List<AddressHint> {
        var out: ArrayList<AddressHint>? = null
        out = tags.fastMapNotNullTo(out, RootAddressTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, ReplyAddressTag::parseAsHint)
        out = tags.fastMapNotNullTo(out, QTag::parseAddressAsHint)
        out = citedNIP19().addressHintsTo(out)
        return out ?: emptyList()
    }

    override fun linkedAddressIds(): List<String> {
        var out: ArrayList<String>? = null
        out = rootAddressIds().appendTo(out)
        out = replyAddressIds().appendTo(out)
        out = quotedAddressIds().appendTo(out)
        out = citedNIP19().addressIdsTo(out)
        return out ?: emptyList()
    }

    fun rootEventIds() = tags.fastMapNotNull(RootEventTag::parseKey)

    fun replyEventIds() = tags.fastMapNotNull(ReplyEventTag::parseKey)

    fun rootAddressIds() = tags.fastMapNotNull(RootAddressTag::parseAddressId)

    fun hasRootAddress(addressId: String) = tags.any(RootAddressTag::isTagged, addressId)

    fun hasReplyAddress(addressId: String) = tags.any(ReplyAddressTag::isTagged, addressId)

    fun replyAddressIds() = tags.fastMapNotNull(ReplyAddressTag::parseAddressId)

    fun replyAddress() = tags.mapNotNull(ReplyAddressTag::parseAddress)

    fun rootScopes() = tags.filter { RootIdentifierTag.match(it) || RootAddressTag.match(it) || RootEventTag.match(it) }

    fun rootKinds() = tags.filter(RootKindTag::match)

    fun directReplies() = tags.filter { ReplyIdentifierTag.match(it) || ReplyAddressTag.match(it) || ReplyEventTag.match(it) }

    /** Whether a parent is named at all, without materialising [directReplies]. */
    fun hasDirectReplies() = tags.fastAny { ReplyIdentifierTag.match(it) || ReplyAddressTag.match(it) || ReplyEventTag.match(it) }

    fun directKinds() = tags.filter(ReplyKindTag::match)

    /** Whether a parent kind (`k`) is declared at all, without materialising [directKinds]. */
    fun hasDirectKinds() = tags.fastAny(ReplyKindTag::match)

    fun rootAuthor() = tags.firstNotNullOfOrNull(RootAuthorTag::parse)

    fun replyAuthor() = tags.firstNotNullOfOrNull(ReplyAuthorTag::parse)

    fun rootAuthors() = tags.filter(RootAuthorTag::match)

    fun replyAuthors() = tags.filter(ReplyAuthorTag::match)

    fun rootAuthorKeys() = tags.fastMapNotNull(RootAuthorTag::parseKey)

    fun replyAuthorKeys() = tags.fastMapNotNull(ReplyAuthorTag::parseKey)

    fun rootAuthorHints() = tags.mapNotNull(RootAuthorTag::parseAsHint)

    fun replyAuthorHints() = tags.mapNotNull(ReplyAuthorTag::parseAsHint)

    /** root and reply scope search */
    fun isTaggedScope(scopeId: String) = tags.any { RootIdentifierTag.isTagged(it, scopeId) || ReplyIdentifierTag.isTagged(it, scopeId) }

    fun isTaggedScopes(scopeIds: Set<String>) = tags.any { RootIdentifierTag.isTagged(it, scopeIds) || ReplyIdentifierTag.isTagged(it, scopeIds) }

    fun isTaggedScope(
        value: String,
        match: (String, String) -> Boolean,
    ) = tags.any { RootIdentifierTag.isTagged(it, value, match) || ReplyIdentifierTag.isTagged(it, value, match) }

    fun firstTaggedScopeIn(scopeIds: Set<String>) = tags.firstNotNullOfOrNull { RootIdentifierTag.matchOrNull(it, scopeIds) ?: ReplyIdentifierTag.matchOrNull(it, scopeIds) }

    fun isScoped(scopeTest: (String) -> Boolean) = tags.any { RootIdentifierTag.isTagged(it, scopeTest) || ReplyIdentifierTag.isTagged(it, scopeTest) }

    /** True when the comment points at an external identifier (`I` tag), e.g. a hashtag, geohash or url. */
    fun hasRootScopeIdentifier() = tags.any { RootIdentifierTag.match(it) }

    fun hasRootScopeKind(kind: String) = tags.any(RootKindTag::isKind, kind)

    fun hasReplyScopeKind(kind: String) = tags.any(ReplyKindTag::isKind, kind)

    fun hasScopeKind(kind: String) = tags.any { RootKindTag.isKind(it, kind) || ReplyKindTag.isKind(it, kind) }

    fun scopeValues(parser: (String) -> String?) = tags.mapNotNull { RootIdentifierTag.parse(it)?.let { parser(it) } }

    fun firstScopeValue(parser: (String) -> String?) = tags.firstNotNullOfOrNull { RootIdentifierTag.parse(it)?.let { parser(it) } }

    override fun markedReplyTos(): List<HexKey> = replyEventIds() + rootEventIds()

    override fun unmarkedReplyTos() = emptyList<String>()

    /**
     * NIP-22 addresses two distinct recipients: the direct-reply author
     * (lowercase `p` via [ReplyAuthorTag]) and the root-scope author
     * (uppercase `P` via [RootAuthorTag]). A comment several levels deep
     * only tags the root author with uppercase `P`, so the base-class
     * lowercase-only default would miss them.
     */
    override fun notifies(userHex: HexKey): Boolean = super.notifies(userHex) || rootAuthorKeys().contains(userHex)

    override fun replyingTo(): HexKey? = replyEventIds().lastOrNull() ?: rootEventIds().lastOrNull()

    fun rootAddress() = tags.mapNotNull(RootAddressTag::parseAddress)

    fun rootAddressId() = tags.mapNotNull(RootAddressTag::parseAddressId)

    fun replyingToAddressId(): String? = replyAddressIds().lastOrNull() ?: rootAddressIds().lastOrNull()

    /** The parent (lowercase `a`, then `e`), else the root (uppercase `A`, then `E`). */
    override fun replyingToAddressOrEvent(): HexKey? =
        replyAddressIds().lastOrNull()
            ?: replyEventIds().lastOrNull()
            ?: rootAddressIds().lastOrNull()
            ?: rootEventIds().lastOrNull()

    override fun tagsWithoutCitations(): List<String> {
        val rootAddress = rootAddressIds()
        val replyAddress = replyAddressIds()

        if (
            rootAddress.any { Address.isOfKind(it, CommunityDefinitionEvent.KIND_STR) } &&
            replyAddress.any { Address.isOfKind(it, CommunityDefinitionEvent.KIND_STR) }
        ) {
            // this is the root of the community post
            return emptyList()
        }

        return rootAddress + replyAddress + rootEventIds() + replyEventIds()
    }

    companion object {
        const val KIND = 1111

        fun replyBuilder(
            msg: String,
            replyingTo: EventHintBundle<Event>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<CommentEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, msg, createdAt) {
            if (replyingTo.event is CommentEvent) {
                addAll(replyingTo.event.rootScopes())
                addAll(replyingTo.event.rootKinds())
                addAll(replyingTo.event.rootAuthors())
            } else {
                if (replyingTo.event is AddressableEvent) {
                    rootAddress(replyingTo.event.addressTag(), replyingTo.relay)
                    replyAddress(replyingTo.event.addressTag(), replyingTo.relay)
                }

                rootEvent(replyingTo.event.id, replyingTo.relay, replyingTo.event.pubKey)
                rootKind(replyingTo.event.kind)
                rootAuthor(replyingTo.event.pubKey, replyingTo.authorHomeRelay)
            }

            replyEvent(replyingTo.event.id, replyingTo.relay, replyingTo.event.pubKey)
            replyKind(replyingTo.event.kind)
            replyAuthor(replyingTo.event.pubKey, replyingTo.authorHomeRelay)

            initializer()
        }

        /**
         * A comment on [replyingTo] inside the thread of [root]: the scope (`E`/`A`, `K`, `P`)
         * comes from [root] and the parent (`e`, `k`, `p`) from [replyingTo].
         *
         * For an event that is part of another event's thread without being a comment itself,
         * such as a NIP-34 status, PR update or legacy reply (whose thread is the issue or PR),
         * the single-argument [replyBuilder] would root the comment at that event, and clients
         * that read the thread by its root (`#E`) would never see it.
         */
        fun replyBuilder(
            msg: String,
            root: EventHintBundle<out Event>,
            replyingTo: EventHintBundle<out Event>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<CommentEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, msg, createdAt) {
            val rootEvent = root.event
            if (rootEvent is AddressableEvent) {
                rootAddress(rootEvent.addressTag(), root.relay)
            }
            rootEvent(rootEvent.id, root.relay, rootEvent.pubKey)
            rootKind(rootEvent.kind)
            rootAuthor(rootEvent.pubKey, root.authorHomeRelay)

            val parent = replyingTo.event
            if (parent is AddressableEvent) {
                replyAddress(parent.addressTag(), replyingTo.relay)
            }
            replyEvent(parent.id, replyingTo.relay, parent.pubKey)
            replyKind(parent.kind)
            replyAuthor(parent.pubKey, replyingTo.authorHomeRelay)

            initializer()
        }

        fun replyExternalIdentity(
            msg: String,
            extId: ExternalId,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<CommentEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, msg, createdAt) {
            if (extId is GeohashId) {
                GeoHashTag.geoMipMap(extId.geohash).forEach { rootExternalIdentity(GeohashId(it, extId.hint)) }
            } else {
                rootExternalIdentity(extId)
            }
            rootKind(extId)

            if (extId is GeohashId) {
                GeoHashTag.geoMipMap(extId.geohash).forEach { replyExternalIdentity(GeohashId(it, extId.hint)) }
            } else {
                replyExternalIdentity(extId)
            }
            replyKind(extId)

            initializer()
        }
    }
}
