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
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole

/**
 * Who maintains and moderates a NIP-34 repository, resolved from the latest announcement (kind
 * 30617) each author published under one identifier.
 *
 * Announcing a repository does not make one a maintainer of someone else's: an assignment is only
 * an invitation until the invitee's own announcement acknowledges a maintainer who is already
 * confirmed. Starting from the selected announcement, this follows the single active `M` (lead)
 * path to the lead who names themselves, or stays at the selected author for a legacy or leadless
 * repository, and grows the confirmed set from there to a fixed point. Moderators (`o`) are
 * confirmed the same way, from confirmed maintainers' assignments.
 *
 * Mirrors the ngit maintainer protocol as gitworkshop implements it. [confirmedMembers] is the
 * set as of now; [membersAt] and [maintainersAt] answer for an older event's `created_at` from the
 * role [history], which is how a status, a PR update or a label should be judged.
 */
@Immutable
class GitRepositoryMembership(
    val selectedMaintainer: HexKey,
    val identifier: String,
    /** Confirmed maintainers, the confirmation seed first. */
    val confirmedMaintainers: List<HexKey>,
    val confirmedModerators: List<HexKey>,
    /** Assigned by a confirmed maintainer but not (or no longer) acknowledged by the invitee. */
    val invitedMaintainers: List<HexKey>,
    val invitedModerators: List<HexKey>,
    /** The latest announcement of every confirmed member, in [confirmedMembers] order. */
    val confirmedAnnouncements: List<GitRepositoryEvent>,
    /** Everyone reached while following assignments: whose announcements to fetch, not who to trust. */
    val discoveryPubKeys: List<HexKey>,
    /** Who held which role when, for judging events by the roles at their own time. */
    val history: GitRepositoryRoleHistory,
) {
    val confirmedMembers: List<HexKey> = (confirmedMaintainers + confirmedModerators).distinct()

    private val maintainerSet = confirmedMaintainers.toHashSet()
    private val memberSet = confirmedMembers.toHashSet()

    /** May publish repository state (30618), merge, edit settings and run maintainer-only CI controls. */
    fun isMaintainer(pubKey: HexKey): Boolean = pubKey in maintainerSet

    /** May set status, labels, subject edits and cover notes on any issue, patch or PR. */
    fun isMember(pubKey: HexKey): Boolean = pubKey in memberSet

    /**
     * When the roles as they stand now took effect: the newest of the confirmed members' current
     * announcements. An event signed since then is judged by today's membership.
     */
    val currentSince: Long = confirmedAnnouncements.maxOfOrNull { it.createdAt } ?: Long.MAX_VALUE

    private val currentMembers = confirmedMembers.toSet()
    private val currentMaintainers = confirmedMaintainers.toSet()

    /**
     * The members (maintainers and moderators) when an event created at [createdAt] was signed.
     *
     * Before [currentSince] this is the role [history], as gitworkshop judges it. From then on it
     * is today's membership: the announcements that define it were already in force. History alone
     * can call that moment ambiguous (a co-maintainer's timeless legacy list read as a second
     * repository) and trust no one, which would lock out every current maintainer.
     */
    fun membersAt(createdAt: Long): Set<HexKey> = if (createdAt >= currentSince) currentMembers else history.membersAt(createdAt)

    /** The maintainers when an event created at [createdAt] was signed; see [membersAt]. */
    fun maintainersAt(createdAt: Long): Set<HexKey> = if (createdAt >= currentSince) currentMaintainers else history.maintainersAt(createdAt)

    /** The announcement coordinates of every confirmed member, the selected one first when confirmed. */
    fun confirmedCoordinates(): List<Address> {
        val ordered = if (selectedMaintainer in memberSet) listOf(selectedMaintainer) + confirmedMembers.filter { it != selectedMaintainer } else confirmedMembers
        return ordered.map { Address(GitRepositoryEvent.KIND, it, identifier) }
    }

    companion object {
        /**
         * The latest announcement of each author under [identifier]: greatest `created_at`, then the
         * lexicographically lowest id at equal timestamps.
         */
        fun latestByAuthor(
            events: Iterable<GitRepositoryEvent>,
            identifier: String,
        ): Map<HexKey, GitRepositoryEvent> {
            val latest = HashMap<HexKey, GitRepositoryEvent>()
            for (event in events) {
                if (event.dTag() != identifier) continue
                val existing = latest[event.pubKey]
                if (existing == null || event.createdAt > existing.createdAt || (event.createdAt == existing.createdAt && event.id < existing.id)) {
                    latest[event.pubKey] = event
                }
            }
            return latest
        }

        /**
         * The membership as the repository's lead sees it: resolved from [selectedMaintainer], then
         * again from the lead its `M` path ends at (the first confirmed maintainer), when that is
         * someone else whose announcement is known. A component is anchored at its lead, and role
         * history prefers the lead's records, so a co-maintainer's coordinate and the lead's give
         * the same answers about the past, as gitworkshop does.
         */
        fun resolveFromLead(
            selectedMaintainer: HexKey,
            identifier: String,
            latestByAuthor: Map<HexKey, GitRepositoryEvent>,
        ): GitRepositoryMembership? {
            val selected = resolve(selectedMaintainer, identifier, latestByAuthor) ?: return null
            val lead = selected.confirmedMaintainers.firstOrNull() ?: return selected
            if (lead == selectedMaintainer || lead !in latestByAuthor) return selected
            return resolve(lead, identifier, latestByAuthor) ?: selected
        }

        private class Edge(
            val from: HexKey,
            val to: HexKey,
            val requiresFreshAcceptance: Boolean,
        )

        /**
         * Resolves the membership seen from [selectedMaintainer]'s announcement, given the latest
         * announcement per author ([latestByAuthor]). Null when the selected announcement is unknown.
         */
        fun resolve(
            selectedMaintainer: HexKey,
            identifier: String,
            latestByAuthor: Map<HexKey, GitRepositoryEvent>,
        ): GitRepositoryMembership? {
            if (selectedMaintainer !in latestByAuthor) return null

            val parsed = LinkedHashMap<HexKey, GitRepositoryEvent>()
            val discovery = ArrayList<HexKey>()
            val queue = ArrayDeque(listOf(selectedMaintainer))
            val maintainerCandidates = LinkedHashSet<HexKey>()
            val maintainerEdges = ArrayList<Edge>()
            val moderatorEdges = ArrayList<Edge>()

            while (queue.isNotEmpty()) {
                val pubKey = queue.removeFirst()
                if (pubKey in discovery) continue
                discovery.add(pubKey)
                val event = latestByAuthor[pubKey] ?: continue
                parsed[pubKey] = event

                val records = event.roleRecords()
                for (assigned in event.activeMaintainerAssignments()) {
                    maintainerCandidates.add(assigned)
                    if (assigned !in discovery) queue.add(assigned)
                    if (assigned == pubKey || maintainerEdges.any { it.from == pubKey && it.to == assigned }) continue
                    val record = records.firstOrNull { it.subject == assigned && it.isActive && it.role.isMaintainer }
                    maintainerEdges.add(Edge(pubKey, assigned, record?.requiresFreshAcceptance == true))
                }
                for (moderator in event.activeModeratorAssignments()) {
                    if (moderator !in discovery) queue.add(moderator)
                    if (moderatorEdges.any { it.from == pubKey && it.to == moderator }) continue
                    val record = records.firstOrNull { it.subject == moderator && it.isActive && it.role == RepositoryRole.MODERATOR }
                    moderatorEdges.add(Edge(pubKey, moderator, record?.requiresFreshAcceptance == true))
                }
            }

            val ineligibleMaintainers = parsed.values.filter { it.authorCannotMaintain() }.mapTo(HashSet()) { it.pubKey }
            val departedMaintainers = parsed.values.filter { it.authorDeclinesMaintainership() }.mapTo(HashSet()) { it.pubKey }

            val confirmed = LinkedHashSet<HexKey>()
            confirmationSeed(selectedMaintainer, parsed, ineligibleMaintainers)?.let { confirmed.add(it) }

            var changed = true
            while (changed) {
                changed = false
                for (candidate in maintainerCandidates) {
                    if (candidate in confirmed || candidate in ineligibleMaintainers) continue
                    val announcement = parsed[candidate]
                    val listedByMember = maintainerEdges.any { it.to == candidate && it.from in confirmed && acceptsMaintainership(announcement) }
                    val acknowledgesMember = maintainerEdges.any { it.from == candidate && it.to in confirmed }
                    if (listedByMember && acknowledgesMember) {
                        confirmed.add(candidate)
                        changed = true
                    }
                }
            }
            val confirmedMaintainers = confirmed.toList()

            val assignedModerators = LinkedHashSet<HexKey>()
            confirmedMaintainers.forEach { maintainer -> parsed[maintainer]?.activeModeratorAssignments()?.let { assignedModerators.addAll(it) } }
            val ineligibleModerators = parsed.values.filter { it.authorCannotModerate() }.mapTo(HashSet()) { it.pubKey }
            val departedModerators = parsed.values.filter { it.authorDeclinesModeratorship() }.mapTo(HashSet()) { it.pubKey }

            val confirmedModerators = ArrayList<HexKey>()
            val members = confirmed.toHashSet()
            changed = true
            while (changed) {
                changed = false
                for (candidate in assignedModerators) {
                    if (candidate in confirmedModerators || candidate in ineligibleModerators) continue
                    val announcement = parsed[candidate] ?: continue
                    val records = announcement.roleRecords().filter { it.isActive }
                    val acknowledgesRole = records.any { it.role == RepositoryRole.MODERATOR && it.subject == candidate }
                    val acknowledgesMember = records.any { it.subject != candidate && it.subject in members }
                    val assigned = moderatorEdges.any { it.to == candidate && it.from in confirmed && !announcement.authorCannotModerate() }
                    if (assigned && acknowledgesRole && acknowledgesMember) {
                        confirmedModerators.add(candidate)
                        members.add(candidate)
                        changed = true
                    }
                }
            }

            val invitedMaintainers =
                maintainerCandidates.filter { pubKey ->
                    if (pubKey in confirmed) return@filter false
                    val incoming = maintainerEdges.filter { it.to == pubKey && it.from in confirmed }
                    incoming.isNotEmpty() && (pubKey !in departedMaintainers || incoming.any { it.requiresFreshAcceptance })
                }
            val invitedModerators =
                assignedModerators.filter { pubKey ->
                    if (pubKey in confirmedModerators) return@filter false
                    pubKey !in departedModerators || moderatorEdges.any { it.to == pubKey && it.from in confirmed && it.requiresFreshAcceptance }
                }

            val confirmedMembers = (confirmedMaintainers + confirmedModerators).distinct()

            // Graph distance from the selected announcement over confirmed members' assignments:
            // the closer an announcement, the more its copy of a role record counts.
            val memberSet = confirmedMembers.toHashSet()
            val edges = (maintainerEdges + moderatorEdges).filter { it.from in memberSet && it.to in memberSet }
            val distances = HashMap<HexKey, Int>().apply { put(selectedMaintainer, 0) }
            val walk = ArrayDeque(listOf(selectedMaintainer))
            while (walk.isNotEmpty()) {
                val author = walk.removeFirst()
                val next = (distances[author] ?: 0) + 1
                edges.forEach { edge ->
                    if (edge.from == author && edge.to !in distances) {
                        distances[edge.to] = next
                        walk.add(edge.to)
                    }
                }
            }
            val history = GitRepositoryRoleHistory.resolve(selectedMaintainer, confirmedMaintainers, confirmedMembers, latestByAuthor, distances)

            return GitRepositoryMembership(
                selectedMaintainer = selectedMaintainer,
                identifier = identifier,
                confirmedMaintainers = confirmedMaintainers,
                confirmedModerators = confirmedModerators,
                invitedMaintainers = invitedMaintainers,
                invitedModerators = invitedModerators,
                confirmedAnnouncements = confirmedMembers.mapNotNull { latestByAuthor[it] },
                discoveryPubKeys = discovery,
                history = history,
            )
        }

        /**
         * Where confirmation starts: follow the single active `M` from the selected announcement to
         * the lead who names themselves. With no `M` at all, the selected author seeds (legacy and
         * leadless repositories). Two leads, a cycle, or a lead who left seed nothing.
         */
        private fun confirmationSeed(
            selectedMaintainer: HexKey,
            parsed: Map<HexKey, GitRepositoryEvent>,
            ineligible: Set<HexKey>,
        ): HexKey? {
            var current = selectedMaintainer
            var followedExplicitLead = false
            val visited = HashSet<HexKey>()
            while (true) {
                if (!visited.add(current)) return null
                val announcement = parsed[current]
                val leads = announcement?.activeLeads().orEmpty()
                if (leads.isEmpty()) return current.takeIf { !followedExplicitLead && it !in ineligible }
                if (leads.size != 1) return null
                val target = leads[0]
                followedExplicitLead = true
                if (target == current) return target.takeIf { it !in ineligible }
                current = target
            }
        }

        /** An invitee accepts maintainership unless their own announcement declines it. */
        private fun acceptsMaintainership(announcement: GitRepositoryEvent?): Boolean = announcement != null && !announcement.authorCannotMaintain()
    }
}
