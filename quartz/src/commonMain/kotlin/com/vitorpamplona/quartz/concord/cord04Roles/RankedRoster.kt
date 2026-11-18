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
package com.vitorpamplona.quartz.concord.cord04Roles

import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey

/**
 * What the Roles v2 Roster fold settled on (CORD-04 §2, §3, §9): the live [roles], each member's
 * [grants] (only Roles that resolve), `@everyone`'s bits, the legacy Channel list, each member's
 * sealed standings, and the head edition of every Roster entity it settled.
 */
class RankedRosterFold(
    val roles: List<RankedRole>,
    val grants: List<RankedGrant>,
    val everyone: ConcordPermissions?,
    val legacyChannels: List<String>?,
    val sealed: Map<String, List<SealedStanding>>,
    val heads: Map<String, ControlEdition>,
    val converged: Boolean,
) {
    private val byId: Map<String, RankedRole> = roles.associateBy { it.roleId }
    private val held: Map<String, List<RankedRole>> =
        grants.associate { g -> g.member to g.roleIds.mapNotNull { byId[it] } }

    fun role(id: String): RankedRole? = byId[id.lowercase()]

    /** The Roles [member] holds that stand. */
    fun rolesOf(member: String): List<RankedRole> = held[member.lowercase()] ?: emptyList()

    /** The bits [member] holds through Roles ranked strictly above [bound] (downward support, CORD-04 §3). */
    fun permissionsAbove(
        member: String,
        bound: Long,
    ): ConcordPermissions {
        var acc = ConcordPermissions.NONE
        for (r in rolesOf(member)) if (r.position < bound) acc = acc union r.permissions
        return acc
    }

    /** [member]'s highest Role position, or null when they hold none. */
    fun highestPosition(member: String): Long? = rolesOf(member).minOfOrNull { it.position }

    /** The bits a Tenure seals for [author] over the edition [hashHex], from every standing ranked above [reach]. */
    fun sealOf(
        author: String,
        hashHex: String,
        reach: Long,
    ): ConcordPermissions {
        var bits = ConcordPermissions.NONE
        for (e in sealed[author.lowercase()] ?: emptyList()) if (e.position < reach && hashHex in e.heads) bits = bits union e.permissions
        return bits
    }

    /** Whether some Tenure lists [hashHex] as [author]'s, at whatever standing. */
    fun isListed(
        author: String,
        hashHex: String,
    ): Boolean = sealed[author.lowercase()]?.any { hashHex in it.heads } == true

    private val headHashes: Map<String, String> by lazy { heads.mapValues { it.value.hashHex } }

    override fun equals(other: Any?): Boolean =
        other is RankedRosterFold &&
            roles == other.roles &&
            grants == other.grants &&
            everyone == other.everyone &&
            legacyChannels == other.legacyChannels &&
            sealed == other.sealed &&
            headHashes == other.headHashes

    override fun hashCode(): Int = roles.hashCode() * 31 + grants.hashCode()
}

/**
 * The Roles v2 Roster fold (CORD-04 §2, §3, §9): Roles ordered by rank keys, `@everyone`, Grants
 * resolved against them, and Tenures, folded by **downward support**, so an edition counts only
 * the bits its signer holds through Roles ranked strictly above everything it touches and the fold
 * resolves top-down from the owner, one answer whatever slice of the plane a reader holds.
 *
 * A port of Armada's `roster.ts` (itself a port of Vector's `fold_authority` / `select_authorized`
 * / `replay`, MIT), for a reader holding no floor: Amethyst refolds the whole buffer every time, so
 * it is the fresh joiner CORD-04 §1 describes. `RankedRosterFixtureTest` checks it against folds
 * Vector produced.
 */
object RankedRoster {
    /** Beneath every Role: `@everyone`'s place, and a roleless member's (u32::MAX, as Vector). */
    const val BENEATH_EVERY_ROLE = 0xFFFFFFFFL

    private const val LINK_RUN_CAP = 64

    private class Judged(
        val rank: Long?,
    )

    private enum class Kind { LIVE, DELETED, EVERYONE, HISTORY }

    private class Cand(
        val kind: Kind,
        val e: ControlEdition,
        val author: String,
        val grantHex: String,
        val role: RankedRole? = null,
        val legacy: List<String>? = null,
        val grant: RankedGrant? = null,
        val tenureMember: String? = null,
        val tenure: List<SealedStanding>? = null,
    ) {
        val version get() = e.version
        val id: String = e.rumorId.lowercase()
        val hashHex: String = e.hashHex
        val prevHex: String? = e.prevHash?.toHexKey()
    }

    private class Cands(
        val roles: Map<String, List<Cand>>,
        val grants: Map<String, List<Cand>>,
        val tenures: Map<String, List<Cand>>,
    )

    private fun stricter(
        a: Long?,
        b: Long?,
    ): Long? =
        when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

    private fun compareKey(
        a: Pair<Long, String>,
        b: Pair<Long, String>,
    ): Int = if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

    private class RoleIndex(
        roles: List<RankedRole>,
        grants: List<RankedGrant>,
    ) {
        private val byId = HashMap<String, RankedRole>()
        private val held = HashMap<String, MutableList<RankedRole>>()

        init {
            for (r in roles) if (r.roleId !in byId) byId[r.roleId] = r
            for (g in grants) {
                val list = held.getOrPut(g.member) { ArrayList() }
                for (rid in g.roleIds) byId[rid]?.let { list.add(it) }
            }
        }

        fun role(id: String): RankedRole? = byId[id]

        fun permissionsAbove(
            member: String,
            bound: Long,
        ): ConcordPermissions {
            var bits = ConcordPermissions.NONE
            for (r in held[member] ?: emptyList<RankedRole>()) if (r.position < bound) bits = bits union r.permissions
            return bits
        }

        fun highestPositionAbove(
            member: String,
            bound: Long,
        ): Long? {
            var best: Long? = null
            for (r in held[member] ?: emptyList<RankedRole>()) if (r.position < bound && (best == null || r.position < best)) best = r.position
            return best
        }
    }

    private fun forkRankAbove(
        index: RoleIndex,
        owner: String,
        author: String,
        reach: Long,
    ): Long = if (author == owner) 0L else index.highestPositionAbove(author, reach) ?: BENEATH_EVERY_ROLE

    private fun citationOk(
        heads: Map<String, ControlEdition>,
        owner: String,
        c: Cand,
    ): Boolean {
        if (c.author == owner) return true
        val cite = c.e.authorityCitation ?: return false
        if (cite.grantId.toHexKey() != c.grantHex) return false
        val head = heads[c.grantHex] ?: return false
        if (head.version > cite.grantVersion) return true
        if (head.version == cite.grantVersion) return head.hashHex == cite.grantHash.toHexKey()
        return false
    }

    private fun isListed(
        sealed: Map<String, List<SealedStanding>>,
        author: String,
        hashHex: String,
    ): Boolean = sealed[author]?.any { hashHex in it.heads } == true

    private fun sealOf(
        sealed: Map<String, List<SealedStanding>>,
        author: String,
        hashHex: String,
        reach: Long,
    ): ConcordPermissions {
        var bits = ConcordPermissions.NONE
        for (e in sealed[author] ?: emptyList()) if (e.position < reach && hashHex in e.heads) bits = bits union e.permissions
        return bits
    }

    /**
     * What naming [start] at version [ver] adds to the rank an edition acts on, and whether that
     * rests on evidence in hand: through refused editions (each perhaps a copy of one that stands,
     * so the strictest rank counts) to an admitted one.
     */
    private fun linkRun(
        cands: List<Cand>,
        start: String,
        ver: Long,
        byHash: Map<String, List<Int>>,
        sure: BooleanArray,
        conf: Array<Judged?>,
        claims: (Cand) -> Long?,
    ): Pair<Long?, Boolean> {
        var acc: Long? = null
        var at = ver
        var h = start
        repeat(LINK_RUN_CAP) {
            val js = byHash[h]
            if (js == null || cands[js[0]].version + 1 != at) return null to false
            val k = js.firstOrNull { conf[it] != null }
            if (k != null) return stricter(acc, conf[k]!!.rank) to js.any { sure[it] }
            acc = stricter(acc, claims(cands[js[0]]))
            at = cands[js[0]].version
            h = cands[js[0]].prevHex ?: return null to false
        }
        return null to false
    }

    /**
     * One entity's chain, replayed ascending (CORD-04 §1, §3), and the index of its head, or null.
     * Each version acts on what the newest admitted version below it left, never on what its own
     * `prev` names alone. Fork siblings are judged at the strictest rank any viable one claims.
     * [cands] arrive version-descending, then by rumor id.
     */
    private fun replay(
        cands: List<Cand>,
        index: RoleIndex,
        owner: String,
        claims: (Cand) -> Long?,
        judge: (Cand, Long) -> Judged?,
        admittedOut: MutableSet<String>,
    ): Int? {
        val n = cands.size
        if (n == 0) return null
        val byHash = HashMap<String, MutableList<Int>>()
        for (j in 0 until n) byHash.getOrPut(cands[j].hashHex) { ArrayList() }.add(j)
        val admitted = BooleanArray(n)
        val sure = BooleanArray(n)
        val conf = arrayOfNulls<Judged>(n)
        val standing = arrayOfNulls<Long>(n)
        val proven = BooleanArray(n)
        val key = Array(n) { BENEATH_EVERY_ROLE to "" }
        val viable = BooleanArray(n)
        val runs = HashMap<String, Pair<Long?, Boolean>>()
        var base: Long? = null

        var i = n
        while (i > 0) {
            val hi = i
            val ver = cands[i - 1].version
            while (i > 0 && cands[i - 1].version == ver) i--
            for (j in i until hi) {
                val prev = cands[j].prevHex
                if (prev == null) {
                    standing[j] = base
                    proven[j] = ver == 1L
                } else {
                    val r = runs.getOrPut("$prev:$ver") { linkRun(cands, prev, ver, byHash, sure, conf, claims) }
                    standing[j] = stricter(base, r.first)
                    proven[j] = r.second
                }
            }

            fun own(j: Int): Long = stricter(claims(cands[j]), standing[j]) ?: BENEATH_EVERY_ROLE
            for (j in i until hi) viable[j] = hi - i == 1 || judge(cands[j], own(j)) != null
            var reach = BENEATH_EVERY_ROLE
            for (j in i until hi) if (viable[j]) reach = minOf(reach, own(j))
            for (j in i until hi) {
                val c = cands[j]
                key[j] = forkRankAbove(index, owner, c.author, reach) to c.id
                conf[j] = if (viable[j]) judge(c, reach) else null
                admitted[j] = conf[j] != null
                sure[j] = admitted[j] && (proven[j] || c.author == owner)
                if (admitted[j]) admittedOut.add(c.id)
            }
            var w: Int? = null
            for (j in i until hi) if (admitted[j] && (w == null || compareKey(key[j], key[w]) < 0)) w = j
            if (w != null) base = conf[w]!!.rank
        }

        var top: Long? = null
        for (j in 0 until n) if (admitted[j] && (top == null || cands[j].version > top)) top = cands[j].version
        if (top == null) return null
        var pick: Int? = null
        for (j in 0 until n) {
            if (admitted[j] && cands[j].version == top && (pick == null || compareKey(key[j], key[pick]) < 0)) pick = j
        }
        return pick
    }

    private fun buildCands(
        editions: Collection<ControlEdition>,
        communityId: ByteArray,
        owner: String,
    ): Cands {
        val everyoneHex = ConcordKeyDerivation.everyoneCoordinate(communityId).toHexKey()
        val groups = HashMap<String, MutableList<ControlEdition>>()
        for (e in editions) {
            if (e.vsk != "1" && e.vsk != "13" && e.vsk != "3" && e.vsk != "16") continue
            groups.getOrPut(e.entityIdHex) { ArrayList() }.add(e)
        }
        // Rank keys map to dense positions; a Tenure's ranks sit in the same order.
        val keys = ArrayList<String>()
        for (e in editions) {
            if (!e.isMarked) continue
            if (e.vsk == "13") {
                val r = RankedRosterContent.parseRankedRole(e.content)
                if (r is RankedRosterContent.ParsedRole.Live) keys.add(r.rank)
            } else if (e.vsk == "16") {
                RankedRosterContent.parseTenure(e.content)?.sealed?.forEach { keys.add(it.rank) }
            }
        }
        val ranks = RankKey.denseRanks(keys)
        val grantHexes = HashMap<String, String>()

        fun grantHexOf(author: String) =
            grantHexes.getOrPut(author) {
                author.hexToByteArray().takeIf { it.size == 32 }?.let { ConcordKeyDerivation.grantCoordinate(communityId, it).toHexKey() } ?: ""
            }

        val roles = LinkedHashMap<String, MutableList<Cand>>()
        val grants = LinkedHashMap<String, MutableList<Cand>>()
        val tenures = LinkedHashMap<String, MutableList<Cand>>()
        for (eid in groups.keys.sorted()) {
            for (e in groups[eid]!!) {
                val author = e.author.lowercase()
                if (!RankedRosterContent.isHex64(author)) continue
                val grantHex = grantHexOf(author)
                when (e.vsk) {
                    "1" -> {
                        if (eid == everyoneHex) continue
                        val r = ConcordJson.decodeOrNull<RoleEntity>(e.content) ?: continue
                        if (r.roleId == null || !r.roleId.equals(eid, ignoreCase = true)) continue
                        roles.getOrPut(eid) { ArrayList() }.add(Cand(Kind.HISTORY, e, author, grantHex))
                    }
                    "13" -> {
                        if (!e.isMarked) continue
                        if (eid == everyoneHex) {
                            val ev = RankedRosterContent.parseEveryone(e.content) ?: continue
                            val role = RankedRole(everyoneHex, "@everyone", "", BENEATH_EVERY_ROLE, ev.permissions, 0, false, false)
                            roles.getOrPut(eid) { ArrayList() }.add(Cand(Kind.EVERYONE, e, author, grantHex, role = role, legacy = ev.legacy))
                            continue
                        }
                        when (val r = RankedRosterContent.parseRankedRole(e.content)) {
                            is RankedRosterContent.ParsedRole.Live -> {
                                if (r.roleId != eid) continue
                                val role = RankedRole(r.roleId, r.name, r.rank, ranks[r.rank] ?: BENEATH_EVERY_ROLE, r.permissions, r.color, r.display, r.mentionable)
                                roles.getOrPut(eid) { ArrayList() }.add(Cand(Kind.LIVE, e, author, grantHex, role = role))
                            }
                            is RankedRosterContent.ParsedRole.Deleted -> {
                                if (r.roleId != eid) continue
                                val role = RankedRole(eid, "", "", BENEATH_EVERY_ROLE, ConcordPermissions.NONE, 0, false, false)
                                roles.getOrPut(eid) { ArrayList() }.add(Cand(Kind.DELETED, e, author, grantHex, role = role))
                            }
                            null -> {}
                        }
                    }
                    "16" -> {
                        if (!e.isMarked) continue
                        val t = RankedRosterContent.parseTenure(e.content) ?: continue
                        if (!RankedRosterContent.isHex64(t.member)) continue
                        val coordinate = ConcordKeyDerivation.tenureCoordinate(communityId, t.member.hexToByteArray(), author.hexToByteArray()).toHexKey()
                        if (coordinate != eid) continue
                        val entries = t.sealed.mapNotNull { s -> ranks[s.rank]?.let { SealedStanding(it, s.permissions, s.heads.toSet()) } }
                        tenures.getOrPut(eid) { ArrayList() }.add(Cand(Kind.LIVE, e, author, grantHex, tenureMember = t.member, tenure = entries))
                    }
                    else -> {
                        val g = RankedRosterContent.parseGrant(e.content) ?: continue
                        if (ConcordKeyDerivation.grantCoordinate(communityId, g.member.hexToByteArray()).toHexKey() != eid) continue
                        grants.getOrPut(eid) { ArrayList() }.add(Cand(Kind.LIVE, e, author, grantHex, grant = g))
                    }
                }
            }
        }
        val order = compareByDescending<Cand> { it.version }.thenBy { it.id }
        for (m in listOf(roles, grants, tenures)) for (list in m.values) list.sortWith(order)
        return Cands(roles, grants, tenures)
    }

    private class Selected(
        val roles: List<RankedRole>,
        val grants: List<RankedGrant>,
        val everyone: ConcordPermissions?,
        val legacy: List<String>?,
        val heads: Map<String, ControlEdition>,
        val sealed: Map<String, List<SealedStanding>>,
        val converged: Boolean,
    )

    /** The owner-seeded delegation fixpoint (CORD-04 §1–§3, §9): per entity the highest edition its author is authorized to write. */
    private fun selectAuthorized(
        cands: Cands,
        communityId: ByteArray,
        owner: String,
        excluded: Set<String>,
    ): Selected {
        val everyoneHex = ConcordKeyDerivation.everyoneCoordinate(communityId).toHexKey()
        var roles: List<RankedRole> = emptyList()
        var grants: List<RankedGrant> = emptyList()
        var everyone: ConcordPermissions? = null
        var legacy: List<String>? = null
        var heads: Map<String, ControlEdition> = emptyMap()
        var sealed: Map<String, List<SealedStanding>> = emptyMap()
        var converged = false
        val bound = 2 * (cands.roles.size + cands.grants.size + cands.tenures.size) + 8

        for (round in 0 until bound) {
            val nextRoles = ArrayList<RankedRole>()
            val nextGrants = ArrayList<RankedGrant>()
            var nextEveryone: ConcordPermissions? = null
            var nextLegacy: List<String>? = null
            val nextHeads = HashMap<String, ControlEdition>()
            val nextAdmitted = HashSet<String>()
            val nextSealed = HashMap<String, MutableList<SealedStanding>>()
            val standing = RoleIndex(roles, grants)
            val citeHeads = heads
            val currentSealed = sealed

            fun supportFor(
                author: String,
                c: Cand,
                reach: Long,
            ): ConcordPermissions = standing.permissionsAbove(author, reach) union sealOf(currentSealed, author, c.hashHex, reach)

            for ((eid, list) in cands.tenures) {
                val claims = { c: Cand -> c.tenure?.minOfOrNull { it.position } }
                val judge = { c: Cand, _: Long ->
                    val ah = c.author
                    val listed = isListed(currentSealed, ah, c.hashHex)
                    val ok =
                        when {
                            c.tenure == null -> false
                            ah in excluded && !listed -> false
                            c.tenureMember == ah || c.tenureMember == owner -> false
                            ah != owner &&
                                !c.tenure.all { entry ->
                                    val support = supportFor(ah, c, entry.position)
                                    support.has(ConcordPermissions.MANAGE_ROLES) && (entry.permissions minus support).isEmpty()
                                } -> false
                            !listed && c.e.authorityCitation != null && !citationOk(citeHeads, owner, c) -> false
                            else -> true
                        }
                    if (ok) Judged(null) else null
                }
                val j = replay(list, standing, owner, claims, judge, nextAdmitted) ?: continue
                nextHeads[eid] = list[j].e
                val member = list[j].tenureMember!!
                nextSealed.getOrPut(member) { ArrayList() }.addAll(list[j].tenure!!)
            }

            for ((eid, list) in cands.roles) {
                // A deletion and a legacy edition claim no rank: they act at the one the Role stood at.
                val claims = { c: Cand -> if (c.kind == Kind.DELETED || c.kind == Kind.HISTORY) null else c.role?.position }
                val judge = judge@{ c: Cand, reach: Long ->
                    val role = c.role ?: return@judge null
                    if (c.kind == Kind.HISTORY) return@judge null
                    val ah = c.author
                    val listed = isListed(currentSealed, ah, c.hashHex)
                    if ((ah in excluded && !listed) || role.position == 0L) return@judge null
                    if (ah != owner) {
                        val support = supportFor(ah, c, reach)
                        if (!support.has(ConcordPermissions.MANAGE_ROLES)) return@judge null
                        if (!(role.permissions minus support).isEmpty()) return@judge null
                    }
                    if (!listed && c.e.authorityCitation != null && !citationOk(citeHeads, owner, c)) return@judge null
                    if (c.kind == Kind.DELETED) Judged(null) else Judged(role.position)
                }
                val picked = replay(list, standing, owner, claims, judge, nextAdmitted)
                // Deletion is terminal over the authorized chain, and acts at every rank the Role holds from the deletion on.
                var deletion: Int? = null
                list.forEachIndexed { j, d ->
                    if (d.kind != Kind.DELETED || d.id !in nextAdmitted) return@forEachIndexed
                    var later: Long? = null
                    for (c in list) {
                        if (c.kind == Kind.LIVE && c.version >= d.version && c.id in nextAdmitted && c.role != null) {
                            later = if (later == null) c.role.position else minOf(later, c.role.position)
                        }
                    }
                    if (later != null && judge(d, later) == null) return@forEachIndexed
                    val cur = deletion
                    if (cur == null || d.version > list[cur].version || (d.version == list[cur].version && d.id < list[cur].id)) deletion = j
                }
                if (deletion != null) {
                    nextHeads[eid] = list[deletion!!].e
                    continue
                }
                if (picked == null) continue
                val c = list[picked]
                if (c.kind == Kind.EVERYONE) {
                    nextEveryone = c.role!!.permissions
                    nextLegacy = c.legacy
                } else if (c.kind == Kind.LIVE) {
                    nextRoles.add(c.role!!)
                }
                nextHeads[eid] = c.e
            }

            for ((eid, list) in cands.grants) {
                // Judged on what it names, never on whether those resolve.
                val claims = { c: Cand ->
                    var m: Long? = null
                    for (rid in c.grant?.roleIds ?: emptyList()) {
                        if (rid == everyoneHex) continue
                        val r = standing.role(rid) ?: continue
                        m = if (m == null) r.position else minOf(m, r.position)
                    }
                    m
                }
                val judge = judge@{ c: Cand, reach: Long ->
                    val grant = c.grant ?: return@judge null
                    val ah = c.author
                    val listed = isListed(currentSealed, ah, c.hashHex)
                    if ((ah in excluded && !listed) || grant.member in excluded) return@judge null
                    // An uncited edition may add authority, but one naming no Role anyone has published is a strip, and a strip must cite.
                    if (!listed && !citationOk(citeHeads, owner, c)) {
                        if (c.e.authorityCitation != null) return@judge null
                        if (grant.roleIds.all { it == everyoneHex || it !in cands.roles }) return@judge null
                    }
                    val ok = ah == owner || (grant.member != owner && supportFor(ah, c, reach).has(ConcordPermissions.MANAGE_ROLES))
                    if (!ok) return@judge null
                    Judged(claims(c))
                }
                if (list.firstOrNull()?.grant?.member in excluded) continue
                val j = replay(list, standing, owner, claims, judge, nextAdmitted) ?: continue
                nextHeads[eid] = list[j].e
                val grant = list[j].grant!!
                val ids = grant.roleIds.filter { it != everyoneHex && standing.role(it) != null }
                if (ids.isNotEmpty()) nextGrants.add(RankedGrant(grant.member, ids, grant.controlWrap))
            }

            val sameHeads = nextHeads.size == heads.size && nextHeads.all { (k, h) -> heads[k] === h }
            val settled = sameHeads && nextRoles == roles && nextGrants == grants && nextEveryone == everyone && nextLegacy == legacy && nextSealed == sealed
            roles = nextRoles
            grants = nextGrants
            everyone = nextEveryone
            legacy = nextLegacy
            heads = nextHeads
            sealed = nextSealed
            if (settled) {
                converged = true
                break
            }
        }
        return Selected(roles, grants, everyone, legacy, heads, sealed, converged)
    }

    /**
     * Whether the Community [communityId], owned by [ownerPubKey], is on Roles v2 (CORD-04 §2): an
     * owner-rooted fold of vsk 13 seats `@everyone`. [editions] must already be the ones a reader
     * of this Community admits ([ControlEdition.admissibleIn]).
     */
    fun isActivated(
        editions: Collection<ControlEdition>,
        communityId: ByteArray,
        ownerPubKey: String,
    ): Boolean {
        val everyoneHex = ConcordKeyDerivation.everyoneCoordinate(communityId).toHexKey()
        if (editions.none { it.vsk == "13" && it.isMarked && it.entityIdHex == everyoneHex }) return false
        val owner = ownerPubKey.lowercase()
        return everyoneHex in selectAuthorized(buildCands(editions, communityId, owner), communityId, owner, emptySet()).heads
    }

    /**
     * The `edition_binding` a writer marks its next edition of [kind] with (CORD-04 §1): the
     * Community's binding once it is on Roles v2, and always for a kind no older client wrote
     * (vsk 13 and up); null when the edition is written unmarked, on the legacy Roster.
     */
    fun bindingForWrite(
        editions: Collection<ControlEdition>,
        communityId: ByteArray,
        ownerPubKey: String,
        kind: ControlEntityKind,
    ): ByteArray? {
        val binding = ConcordKeyDerivation.editionBinding(communityId)
        if (kind.alwaysMarked) return binding
        return if (isActivated(ControlEdition.admissibleIn(editions, communityId), communityId, ownerPubKey)) binding else null
    }

    /**
     * Folds the Roles v2 Roster of the Community [communityId]: Roles, `@everyone`, Grants and
     * Tenures, the [excluded] (banned) members' editions refused except what a Tenure keeps.
     * Applies the 100-Role cap after authority is resolved (CORD-04 §2), and takes the legacy
     * Channel list from the switching `@everyone` edition when it is served.
     */
    fun fold(
        editions: Collection<ControlEdition>,
        communityId: ByteArray,
        ownerPubKey: String,
        excluded: Set<String> = emptySet(),
    ): RankedRosterFold {
        val owner = ownerPubKey.lowercase()
        val sel = selectAuthorized(buildCands(editions, communityId, owner), communityId, owner, excluded.mapTo(HashSet()) { it.lowercase() })
        var roles = sel.roles
        var grants = sel.grants
        if (roles.size > ConcordLimits.MAX_ROLES_PER_COMMUNITY) {
            roles = roles.sortedBy { it.roleId }.take(ConcordLimits.MAX_ROLES_PER_COMMUNITY)
            val kept = roles.mapTo(HashSet()) { it.roleId }
            grants = grants.map { it.copy(roleIds = it.roleIds.filter { r -> r in kept }) }.filter { it.roleIds.isNotEmpty() }
        }
        // The switch fixes the legacy list (CORD-04 §2): the owner's first `@everyone` edition that reads as one.
        val everyoneHex = ConcordKeyDerivation.everyoneCoordinate(communityId).toHexKey()
        val switching =
            editions
                .filter {
                    it.vsk == "13" && it.isMarked && it.author.equals(owner, ignoreCase = true) && it.prevHash == null &&
                        it.entityIdHex == everyoneHex && RankedRosterContent.parseEveryone(it.content) != null
                }.minWithOrNull(compareBy<ControlEdition> { it.version }.thenBy { it.rumorId })
        val legacy = switching?.let { RankedRosterContent.parseEveryone(it.content)?.legacy } ?: sel.legacy
        return RankedRosterFold(roles, grants, sel.everyone, legacy, sel.sealed, sel.heads, sel.converged)
    }
}
