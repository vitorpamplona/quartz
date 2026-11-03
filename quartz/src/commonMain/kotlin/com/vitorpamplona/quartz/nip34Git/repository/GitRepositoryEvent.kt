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
package com.vitorpamplona.quartz.nip34Git.repository

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip34Git.repository.tags.CloneTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.DescriptionTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.EucTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.MaintainersTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.NameTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.RelaysTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.WebTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
class GitRepositoryEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent,
    PubKeyHintProvider {
    // The `t` hashtags are appended after the body, as CommentEvent does.
    override fun indexableContent() = (listOfNotNull(name(), description(), content) + hashtags()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(name())) return
        if (!visitor.visit(description())) return
        if (!visitor.visit(content)) return
        // Inline over the tags rather than hashtags(): this runs per event per search keystroke.
        for (tag in tags) {
            val hashtag = HashtagTag.parse(tag) ?: continue
            if (!visitor.visit(hashtag)) return
        }
    }

    // The `maintainers` tag lists bare pubkeys, without relay hints.
    override fun pubKeyHints(): List<PubKeyHint> = emptyList()

    // Hex.isHex64 only reads the first 64 chars, so the length is checked too.
    override fun linkedPubKeys(): List<HexKey> = maintainers().filter { it.length == 64 && Hex.isHex64(it) }

    fun name() = tags.firstNotNullOfOrNull(NameTag::parse)

    fun description() = tags.firstNotNullOfOrNull(DescriptionTag::parse)

    /** First web URL, for backwards compatibility. Prefer [webs]. */
    fun web() = webs().firstOrNull()

    /**
     * All browse URLs. Tolerant of both the NIP-34 spec form (one multi-value
     * `["web", url1, url2]` tag, which ngit emits) and the legacy repeated
     * `["web", url1]` / `["web", url2]` form, so a repo announced by any client
     * round-trips without dropping URLs.
     */
    fun webs(): List<String> = tags.flatMap(WebTag::parseAll).distinct()

    /** First clone URL, for backwards compatibility. Prefer [clones]. */
    fun clone() = clones().firstOrNull()

    /** All clone URLs — tolerant of both the multi-value and repeated forms (see [webs]); deduped. */
    fun clones(): List<String> = tags.flatMap(CloneTag::parseAll).distinct()

    /**
     * Relays the repository author monitors for patches and issues. NIP-34
     * encodes this as one multi-value tag; repeated `relays` tags are read too,
     * as [webs] does, so an edit republishing the list does not drop any.
     */
    fun relays(): List<String> = tags.flatMap { RelaysTag.parse(it) ?: emptyList() }.distinct()

    /** [relays], normalized; entries that are not relay URLs are dropped. */
    fun relaysNorm(): List<NormalizedRelayUrl> = tags.flatMap { RelaysTag.parseNorm(it) ?: emptyList() }.distinct()

    /**
     * Accepted maintainer pubkeys. The event author is an implicit maintainer
     * and is not required to appear in this list.
     */
    fun maintainers(): List<HexKey> = tags.flatMap { MaintainersTag.parse(it) ?: emptyList() }.distinct()

    fun hashtags(): List<String> = tags.hashtags()

    /**
     * The well-formed `M`/`m`/`o` role records. A malformed record grants nothing and is left out,
     * and so is a self-authored record that ends in `defer` (an author cannot defer their own end).
     */
    fun roleRecords(): List<RepositoryRoleRecord> =
        tags.mapNotNull { tag ->
            RepositoryRoleTag.parse(tag)?.takeUnless { it.subject == pubKey && it.endsInDefer }
        }

    /** True when any `M`/`m`/`o` tag is present: then those roles, not `maintainers`, are authoritative. */
    fun hasIndexedRoles(): Boolean = tags.any(RepositoryRoleTag::isTagged)

    /** True when an `M` or `m` tag is present. */
    fun hasIndexedMaintainerRoles(): Boolean = tags.any { RepositoryRoleTag.isTagged(it) && it[0] != RepositoryRole.MODERATOR.code }

    /** True when the deprecated `maintainers` tag is present. */
    fun hasLegacyMaintainersTag(): Boolean = tags.any { it.isNotEmpty() && it[0] == MaintainersTag.TAG_NAME }

    /** The subjects of active `M` records: who this announcement forwards the lead to. */
    fun activeLeads(): List<HexKey> = roleRecords().filter { it.isActive && it.role == RepositoryRole.LEAD }.map { it.subject }.distinct()

    /**
     * The maintainers this announcement assigns or acknowledges, author included when they count.
     *
     * With indexed roles: every active `M`/`m` subject, plus the author when no role tag names them
     * (an author with no self-record is an implicit maintainer). Without them: the author and the
     * legacy `maintainers` list.
     */
    fun activeMaintainerAssignments(): List<HexKey> {
        val result = LinkedHashSet<HexKey>()
        if (hasIndexedRoles()) {
            if (tags.none { RepositoryRoleTag.isTagged(it) && it.getOrNull(1) == pubKey }) result.add(pubKey)
            roleRecords().forEach { if (it.isActive && it.role.isMaintainer) result.add(it.subject) }
        } else {
            result.add(pubKey)
            maintainers().forEach { if (Hex.isHex64(it)) result.add(it) }
        }
        return result.toList()
    }

    /** The active `o` subjects: moderators this announcement assigns or acknowledges. */
    fun activeModeratorAssignments(): List<HexKey> = roleRecords().filter { it.isActive && it.role == RepositoryRole.MODERATOR }.map { it.subject }.distinct()

    /**
     * True when the author has role records about themselves but none of them is an active `M`/`m`,
     * or one of them is an unrepaired self-`defer`: they declined or left maintainership.
     */
    fun authorCannotMaintain(): Boolean {
        val authorHasRoleEntry = tags.any { RepositoryRoleTag.isTagged(it) && it.getOrNull(1) == pubKey }
        if (!authorHasRoleEntry) return false
        return roleRecords().none { it.subject == pubKey && it.isActive && it.role.isMaintainer } || hasUnrepairedSelfDefer()
    }

    /** Like [authorCannotMaintain], for the moderator role. */
    fun authorCannotModerate(): Boolean {
        val authorHasModeratorEntry = tags.any { it.size > 1 && it[0] == RepositoryRole.MODERATOR.code && it[1] == pubKey }
        if (!authorHasModeratorEntry) return false
        return roleRecords().none { it.subject == pubKey && it.isActive && it.role == RepositoryRole.MODERATOR } || hasUnrepairedSelfDefer()
    }

    /** True when the author has self maintainer records and none is active: they left maintainership. */
    fun authorDeclinesMaintainership(): Boolean {
        val selfRecords = roleRecords().filter { it.subject == pubKey }
        if (selfRecords.any { it.isActive && it.role.isMaintainer }) return false
        val hasSelfMaintainerRecord = selfRecords.any { it.role.isMaintainer }
        val hasInvalidSelfMaintainerDefer =
            tags.any { tag ->
                RepositoryRoleTag.parse(tag)?.let { it.subject == pubKey && it.endsInDefer && it.role.isMaintainer } == true
            }
        return hasSelfMaintainerRecord || (selfRecords.isNotEmpty() && !hasInvalidSelfMaintainerDefer)
    }

    /** True when the author has self moderator records and none is active: they left moderation. */
    fun authorDeclinesModeratorship(): Boolean {
        val selfModerator = roleRecords().filter { it.subject == pubKey && it.role == RepositoryRole.MODERATOR }
        return selfModerator.isNotEmpty() && selfModerator.none { it.isActive }
    }

    /**
     * A self-authored record ending in `defer` is invalid. It stays a problem until a later active
     * self-record (one that starts after the deferred interval did) supersedes it.
     */
    private fun hasUnrepairedSelfDefer(): Boolean {
        val valid = roleRecords()
        return tags.any { tag ->
            val record = RepositoryRoleTag.parse(tag) ?: return@any false
            if (record.subject != pubKey || !record.endsInDefer) return@any false
            val lastStart = record.boundaries.getOrNull(record.boundaries.size - 2) ?: return@any true
            valid.none { it.subject == pubKey && it.isActive && (it.activeSince ?: 0) > lastStart }
        }
    }

    /** Earliest-unique-commit ID (NIP-34 `["r", <commit>, "euc"]`). */
    fun earliestUniqueCommit(): String? = tags.firstNotNullOfOrNull(EucTag::parse)

    /**
     * `true` if the event carries `["t", "personal-fork"]`, signaling the
     * author does not actively seek patches/feedback for this repository.
     */
    fun isPersonalFork(): Boolean = tags.any { HashtagTag.isTagged(it, PERSONAL_FORK) }

    /**
     * Tags [build] does not write: ngit's `M`/`m`/`o` role tags, `u` (subordinate fork),
     * `blossoms`, `private`, plain `r` references and anything newer. An edit passes these
     * back through `build`'s initializer so republishing does not drop them, the way ngit
     * carries unknown tags over verbatim.
     */
    fun unmanagedTags(): List<Array<String>> = tags.filter { it.isNotEmpty() && it[0] !in BUILT_TAG_NAMES && EucTag.parse(it) == null }

    companion object {
        const val KIND = 30617
        const val PERSONAL_FORK = "personal-fork"

        /** NIP-31 description, in the same words ngit uses. */
        fun altDescription(name: String) = "git repository: $name"

        /**
         * A `d` identifier for a repository called [name]: lowercase kebab-case,
         * every run of non-alphanumerics collapsing to one hyphen
         * (`"My Cool Repo!"` → `"my-cool-repo"`). A name with no ASCII
         * letters or digits is used as is.
         */
        fun suggestIdentifier(name: String): String =
            name
                .lowercase()
                .replace(NON_ALPHANUMERIC, "-")
                .trim('-')
                .ifEmpty { name.trim() }

        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

        // The tag names build() rewrites from its parameters; the euc `r` tag is matched separately.
        private val BUILT_TAG_NAMES = setOf("d", "name", "description", "web", "clone", "relays", "maintainers", "t", "alt")

        @OptIn(ExperimentalUuidApi::class)
        fun build(
            name: String,
            description: String? = null,
            webUrl: String? = null,
            cloneUrl: String? = null,
            dTag: String = Uuid.random().toString(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<GitRepositoryEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            dTag(dTag)
            name(name)
            alt(altDescription(name))
            description?.let { description(it) }
            webUrl?.let { webUrl(it) }
            cloneUrl?.let { cloneUrl(it) }
            initializer()
        }

        @OptIn(ExperimentalUuidApi::class)
        fun build(
            name: String,
            description: String?,
            webUrls: List<String>,
            cloneUrls: List<String>,
            relays: List<String>,
            maintainers: List<HexKey>,
            hashtags: List<String>,
            earliestUniqueCommit: String?,
            personalFork: Boolean = false,
            dTag: String = Uuid.random().toString(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<GitRepositoryEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            dTag(dTag)
            name(name)
            alt(altDescription(name))
            description?.let { description(it) }
            if (webUrls.isNotEmpty()) webUrls(webUrls)
            if (cloneUrls.isNotEmpty()) cloneUrls(cloneUrls)
            if (relays.isNotEmpty()) relays(relays)
            if (maintainers.isNotEmpty()) maintainers(maintainers)
            if (hashtags.isNotEmpty()) hashtags(hashtags)
            earliestUniqueCommit?.let { euc(it) }
            if (personalFork) addUniqueValueIfNew(HashtagTag.assemble(PERSONAL_FORK))
            initializer()
        }
    }
}
