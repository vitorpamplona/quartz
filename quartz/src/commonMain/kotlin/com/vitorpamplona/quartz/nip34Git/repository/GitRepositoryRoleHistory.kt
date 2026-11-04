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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip34Git.repository.tags.MaintainersTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleTag
import com.vitorpamplona.quartz.utils.Hex

/** Whether a role was held at a moment: `UNKNOWN` when its history defers that answer. */
enum class RoleState { ACTIVE, INACTIVE, UNKNOWN }

/**
 * Who held a repository role when: the role records of the announcements that matter to one view
 * of a repository, used to judge an immutable event (a status, a PR update, a label, a cover note)
 * by the roles at its own `created_at`, not today's.
 *
 * Each `role:subject` takes the one record from the announcement closest to the selected
 * maintainer (the selected one first, then by graph distance, then by pubkey), as ngit and
 * gitworkshop do. Malformed or duplicated records are disputed and grant nothing, and a history
 * that defers its end is unknown, so a past action under it fails closed.
 */
@Immutable
class GitRepositoryRoleHistory(
    val selectedMaintainer: HexKey,
    /** Today's confirmed maintainers: extra roots for older views of the graph. */
    val currentConfirmedMaintainers: List<HexKey>,
    private val authors: Map<HexKey, AuthorHistory>,
    private val resolvedRecords: List<RepositoryRoleRecord>,
) {
    /** One announcement's role records, plus the `role:subject` keys it disputes. */
    class AuthorHistory(
        val author: HexKey,
        val records: List<RepositoryRoleRecord>,
        val disputedKeys: Set<String>,
    )

    /** The confirmed maintainers at [createdAt]; empty when the history is ambiguous then. */
    fun maintainersAt(createdAt: Long): Set<HexKey> {
        val components = LinkedHashMap<String, Set<HexKey>>()
        for (root in (listOf(selectedMaintainer) + currentConfirmedMaintainers).distinct()) {
            val component = maintainersFromRoot(createdAt, root)
            if (component.isNotEmpty()) components[component.sorted().joinToString(":")] = component
        }
        // Two different historical repositories assembled into today's one are ambiguous.
        return if (components.size == 1) components.values.first() else emptySet()
    }

    /** The confirmed members (maintainers and moderators) at [createdAt]. */
    fun membersAt(createdAt: Long): Set<HexKey> {
        val maintainers = maintainersAt(createdAt)
        val members = maintainers.toMutableSet()
        val moderatorCandidates = resolvedRecords.filter { it.role == RepositoryRole.MODERATOR && stateAt(it, createdAt) == RoleState.ACTIVE }.map { it.subject }.toSet()
        for (candidate in moderatorCandidates) {
            val assigned = maintainers.any { activeTargets(authors[it], createdAt, MODERATOR_ROLE).contains(candidate) }
            if (!assigned) continue
            val history = authors[candidate]
            val accepts = hasSelfRoleAt(history, createdAt, MODERATOR_ROLE)
            val acknowledges = activeTargets(history, createdAt, ALL_ROLES).any { it != candidate && it in members }
            if (accepts && acknowledges) members.add(candidate)
        }
        return members
    }

    private fun maintainersFromRoot(
        createdAt: Long,
        root: HexKey,
    ): Set<HexKey> {
        val confirmed = LinkedHashSet<HexKey>()
        val visited = HashSet<HexKey>()
        var current = root
        var followedLead = false
        while (true) {
            if (!visited.add(current)) return emptySet()
            val history = authors[current]
            val leads = activeTargets(history, createdAt, LEAD_ROLE)
            if (leads.isEmpty()) {
                if (!followedLead && hasSelfRoleAt(history, createdAt, MAINTAINER_ROLES)) confirmed.add(current)
                break
            }
            if (leads.size != 1) return emptySet()
            followedLead = true
            val target = leads[0]
            if (target == current) {
                if (hasSelfRoleAt(history, createdAt, LEAD_ROLE)) confirmed.add(current)
                break
            }
            current = target
        }

        val candidates = resolvedRecords.filter { it.role.isMaintainer && stateAt(it, createdAt) == RoleState.ACTIVE }.map { it.subject }.toSet()
        var changed = true
        while (changed) {
            changed = false
            for (candidate in candidates) {
                if (candidate in confirmed) continue
                val assigned = confirmed.any { activeTargets(authors[it], createdAt, MAINTAINER_ROLES).contains(candidate) }
                if (!assigned) continue
                val history = authors[candidate]
                val accepts = hasSelfRoleAt(history, createdAt, MAINTAINER_ROLES)
                val acknowledges = activeTargets(history, createdAt, MAINTAINER_ROLES).any { it != candidate && it in confirmed }
                if (accepts && acknowledges) {
                    confirmed.add(candidate)
                    changed = true
                }
            }
        }
        return confirmed
    }

    private fun activeTargets(
        history: AuthorHistory?,
        createdAt: Long,
        roles: Set<RepositoryRole>,
    ): List<HexKey> =
        history
            ?.records
            ?.filter { it.role in roles && key(it) !in history.disputedKeys && stateAt(it, createdAt) == RoleState.ACTIVE }
            ?.map { it.subject }
            ?.distinct()
            .orEmpty()

    private fun hasSelfRoleAt(
        history: AuthorHistory?,
        createdAt: Long,
        roles: Set<RepositoryRole>,
    ): Boolean = history != null && history.records.any { it.subject == history.author && it.role in roles && key(it) !in history.disputedKeys && stateAt(it, createdAt) == RoleState.ACTIVE }

    companion object {
        private val MAINTAINER_ROLES = setOf(RepositoryRole.LEAD, RepositoryRole.MAINTAINER)
        private val LEAD_ROLE = setOf(RepositoryRole.LEAD)
        private val MODERATOR_ROLE = setOf(RepositoryRole.MODERATOR)
        private val ALL_ROLES = RepositoryRole.entries.toSet()

        private fun key(record: RepositoryRoleRecord) = record.role.code + ":" + record.subject

        /** Whether [record] was active at [createdAt]: its boundaries alternate start and end, `null` being `defer`. */
        fun stateAt(
            record: RepositoryRoleRecord,
            createdAt: Long,
        ): RoleState {
            val boundaries = record.boundaries
            if (boundaries.isEmpty()) return RoleState.ACTIVE
            var index = 0
            while (index < boundaries.size) {
                val start = boundaries[index] ?: return RoleState.UNKNOWN
                if (createdAt < start) return RoleState.INACTIVE
                if (index + 1 >= boundaries.size) return RoleState.ACTIVE
                val end = boundaries[index + 1] ?: return RoleState.UNKNOWN
                if (createdAt < end) return RoleState.ACTIVE
                index += 2
            }
            return RoleState.INACTIVE
        }

        /**
         * One announcement's role history. Without indexed roles, its author and the legacy
         * `maintainers` list are maintainers for all of time; with them, each well-formed record
         * counts, a malformed or repeated `role:subject` is disputed, and an author with no
         * record about themselves is an implicit maintainer.
         */
        fun authorHistory(event: GitRepositoryEvent): AuthorHistory {
            val roleTags = event.tags.filter(RepositoryRoleTag::isTagged)
            if (roleTags.isEmpty()) {
                val subjects = LinkedHashSet<HexKey>()
                subjects.add(event.pubKey)
                event.tags.forEach { tag ->
                    if (tag.isNotEmpty() && tag[0] == MaintainersTag.TAG_NAME) tag.drop(1).forEach { if (Hex.isHex64(it)) subjects.add(it) }
                }
                return AuthorHistory(event.pubKey, subjects.map { RepositoryRoleRecord(RepositoryRole.MAINTAINER, it, emptyList()) }, emptySet())
            }

            val records = ArrayList<RepositoryRoleRecord>()
            val counts = HashMap<String, Int>()
            val disputed = HashSet<String>()
            for (tag in roleTags) {
                val subject = tag.getOrNull(1) ?: continue
                if (!Hex.isHex64(subject)) continue
                val key = tag[0] + ":" + subject
                counts[key] = (counts[key] ?: 0) + 1
                val record = RepositoryRoleTag.parse(tag)?.takeUnless { it.subject == event.pubKey && it.endsInDefer }
                if (record != null) records.add(record) else disputed.add(key)
            }
            counts.forEach { (key, count) -> if (count > 1) disputed.add(key) }
            if (roleTags.none { it.getOrNull(1) == event.pubKey }) {
                records.add(RepositoryRoleRecord(RepositoryRole.MAINTAINER, event.pubKey, emptyList()))
            }
            return AuthorHistory(event.pubKey, records, disputed)
        }

        /**
         * The history of one view: the announcements of the selected maintainer, today's confirmed
         * members and everyone their roles name ([latestByAuthor]), each `role:subject` resolved to
         * the record of the closest announcement ([distances]: graph distance from the selected one).
         */
        fun resolve(
            selectedMaintainer: HexKey,
            confirmedMaintainers: List<HexKey>,
            confirmedMembers: List<HexKey>,
            latestByAuthor: Map<HexKey, GitRepositoryEvent>,
            distances: Map<HexKey, Int>,
        ): GitRepositoryRoleHistory {
            val sourceAuthors = (listOf(selectedMaintainer) + confirmedMembers).distinct()
            val historyPubKeys =
                sourceAuthors
                    .flatMap { pubKey -> listOf(pubKey) + latestByAuthor[pubKey]?.roleRecords()?.map { it.subject }.orEmpty() }
                    .distinct()
            val authors = historyPubKeys.mapNotNull { latestByAuthor[it] }.associate { it.pubKey to authorHistory(it) }

            val candidateKeys = sortedSetOf<String>()
            sourceAuthors.forEach { author ->
                authors[author]?.let { history ->
                    history.records.forEach { candidateKeys.add(key(it)) }
                    candidateKeys.addAll(history.disputedKeys)
                }
            }

            val resolved = ArrayList<RepositoryRoleRecord>()
            for (roleKey in candidateKeys) {
                val preferred =
                    sourceAuthors
                        .mapNotNull { author ->
                            val history = authors[author] ?: return@mapNotNull null
                            val records = history.records.filter { key(it) == roleKey }
                            if (records.isEmpty() && roleKey !in history.disputedKeys) return@mapNotNull null
                            Triple(author, history, records)
                        }.minWithOrNull(
                            compareBy<Triple<HexKey, AuthorHistory, List<RepositoryRoleRecord>>> { (author, _, _) ->
                                if (author == selectedMaintainer) -1 else distances[author] ?: Int.MAX_VALUE
                            }.thenBy { it.first },
                        ) ?: continue
                val (_, history, records) = preferred
                if (roleKey in history.disputedKeys || records.size != 1) continue
                resolved.add(records[0])
            }

            return GitRepositoryRoleHistory(selectedMaintainer, confirmedMaintainers, authors, resolved)
        }
    }
}
