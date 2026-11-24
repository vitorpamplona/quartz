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

import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteRegistry
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArrayOrNull
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.cache.ConcurrentLruCache
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap

/**
 * Resolves the owner-rooted authority state of a Concord community from its
 * folded Control Plane (CORD-04).
 *
 * "The Roster is owner-rooted: every Grant and Role is signed by an npub the
 * Roster ranks strictly above it, and the chain terminates at the owner."
 *
 * Build one with [resolve] from the community's Control Plane editions, its
 * `community_id` (which pins every derived coordinate) and its known owner pubkey.
 * It then answers:
 *  - [rank] — a member's authority (lower is higher; owner is [OWNER_RANK]; a
 *    member with no validly-granted role has no rank).
 *  - [effectivePermissions] — the union of a member's roles' bits (owner: all).
 *  - [isBanned] — membership in the folded Banlist head.
 *  - [canActOn] — whether an actor may take a permissioned action on a target:
 *    the actor must hold the bit, must strictly outrank the target (equal cannot
 *    act on equal), and the owner is unremovable.
 *  - [citationFor] / [citationSatisfied] — the `vac` authority citation (CORD-04 §5)
 *    an actor writes, and whether an edition's citation resolves against this roster.
 *
 * Grants are validated by a fixpoint that only ever empowers members reachable
 * from the owner: a Grant is honored when it sits at its member's own coordinate,
 * its signer already outranks every assigned Role, holds
 * [ConcordPermissions.MANAGE_ROLES], and cites the Grant it acts under. Cycles that
 * never touch the owner can never bootstrap themselves.
 */
@ConsistentCopyVisibility
data class AuthorityResolver private constructor(
    private val communityIdHex: String,
    private val ownerLower: String,
    private val roles: Map<String, RoleEntity>,
    private val memberRoles: Map<String, Set<String>>,
    private val banned: Set<String>,
    /** Each role-holder's honored Grant head, keyed by member — what a `vac` must pin. */
    private val grantHeads: Map<String, GrantHead>,
    /** The Roles v2 Roster (CORD-04 §2) when the Community has switched to it; null on the legacy Roster. */
    private val ranked: RankedRosterFold? = null,
    /** The head edition of every authority entity this resolver settled (Roles, Grants, Tenures, and on v2 the Banlist). */
    private val heads: AuthorityHeads = AuthorityHeads.EMPTY,
) {
    // Derived from the constructor values, so deliberately outside the data class's equality.
    private val communityId: ByteArray by lazy { communityIdHex.hexToByteArray() }
    private val banlistEidHex: String by lazy { banlistCoordinateHex(communityId) }

    /**
     * `grant_locator(community_id, member)` per member asked about, memoized: it is an HKDF, and the
     * gates ask [citationSatisfied] for every edition of every fold. Only members holding a Grant
     * head are ever derived (anyone else's citation fails before the coordinate matters).
     */
    private val grantCoordinates = ConcurrentMap<String, String>()

    private fun grantCoordinateOf(member: String): String? {
        grantCoordinates[member]?.let { return it }
        val coordinate = grantCoordinateOrNull(communityId, member) ?: return null
        grantCoordinates[member] = coordinate
        return coordinate
    }

    /**
     * A member's honored Grant head: the edition every reader folded their roles from, and so
     * the one their authority actions cite (CORD-04 §5).
     */
    data class GrantHead(
        val version: Long,
        val hashHex: String,
    )

    /** True once the Community is on Roles v2 (CORD-04 §2): `@everyone` stands on an owner-rooted vsk-13 fold. */
    val isRolesV2: Boolean get() = ranked != null

    /** The Roles v2 Roster this resolver folded, or null on the legacy Roster. */
    fun rankedRoster(): RankedRosterFold? = ranked

    /** `@everyone`'s bits (CORD-04 §2), or null on the legacy Roster. */
    fun everyonePermissions(): ConcordPermissions? = ranked?.everyone

    /**
     * The Channels that stood before the switch to Roles v2 (CORD-04 §2), lowercase hex, or null
     * on the legacy Roster or when the switch listed none. An unmarked Channel edition counts only
     * for a listed Channel.
     */
    fun legacyChannels(): Set<String>? = ranked?.legacyChannels?.toSet()

    /**
     * The bits a Tenure seals for [author] over the edition [hashHex] (CORD-04 §9), from every
     * standing ranked above [reach] (default: beneath every Role). Always empty on the legacy Roster.
     */
    fun sealedFor(
        author: String,
        hashHex: String,
        reach: Long = RankedRoster.BENEATH_EVERY_ROLE,
    ): ConcordPermissions = ranked?.sealOf(author, hashHex, reach) ?: ConcordPermissions.NONE

    /** Whether some Tenure lists [hashHex] as [author]'s (CORD-04 §9): it stands without its `vac`. */
    fun isTenureListed(
        author: String,
        hashHex: String,
    ): Boolean = ranked?.isListed(author, hashHex) == true

    /**
     * The head edition of every authority entity this resolver settled, keyed by entity id: the
     * Roles, Grants and Tenures the owner-rooted fold admitted (the Roles v2 Roster's, or the
     * legacy fixpoint's), and on Roles v2 the Banlist head the ban was read from. What a floor or
     * a Refounding's compaction must remember for these entities: an edition the fold refused is
     * never among them, however it cites.
     */
    internal fun authorityHeads(): Map<String, ControlEdition> = heads.byEntity

    /** The resolved role definitions (authority-gated), keyed by role id. Safe for display. */
    fun roles(): Map<String, RoleEntity> = roles

    /** The role definitions [pubKey] currently holds (empty for the owner and plain members). */
    fun rolesFor(pubKey: String): List<RoleEntity> = rolesOf(pubKey).mapNotNull { roles[it] }

    fun isOwner(pubKey: String): Boolean = pubKey.lowercase() == ownerLower

    /** The owner's pubkey (lowercase hex), proven by the `community_id` rather than any fold. */
    fun owner(): String = ownerLower

    fun isBanned(pubKey: String): Boolean = pubKey.lowercase() in banned

    /** The role ids a member currently holds (empty for the owner and for plain members). */
    fun rolesOf(pubKey: String): Set<String> = memberRoles[pubKey.lowercase()] ?: emptySet()

    /**
     * The set of pubkeys that hold at least one validly-granted role (lowercase
     * hex). This is the *privileged* roster — admins/moderators and any other
     * role-holders — and excludes the owner and silent key-holding members, since
     * plain membership is key possession and leaves no Control-Plane trace.
     */
    fun roleHolders(): Set<String> = memberRoles.keys

    /** The folded Banlist (lowercase hex). */
    fun bannedMembers(): Set<String> = banned

    /** The member's rank, lower being higher authority; null = no authority. Owner = [OWNER_RANK]. */
    fun rank(pubKey: String): Long? {
        val m = pubKey.lowercase()
        if (m == ownerLower) return OWNER_RANK
        val held = memberRoles[m] ?: return null
        return held.mapNotNull { roles[it]?.position }.minOrNull()
    }

    /**
     * [author]'s standing for an equal-version tie-break (CORD-04 §1, "authority first"):
     * the owner first, then by Role position, a roleless author last.
     */
    fun tieBreakRank(author: String): Long = rank(author) ?: Long.MAX_VALUE

    /** The union of a member's roles' permission bits (owner holds every bit). */
    fun effectivePermissions(pubKey: String): ConcordPermissions {
        val m = pubKey.lowercase()
        if (m == ownerLower) return ConcordPermissions.ALL
        // On Roles v2 every member holds `@everyone`'s bits before any Role (CORD-04 §3).
        val base = ranked?.everyone ?: ConcordPermissions.NONE
        val held = memberRoles[m] ?: return base
        var acc = base
        for (id in held) roles[id]?.let { acc = acc union it.permissionBits() }
        return acc
    }

    /** True if the member holds [bit] and is not banned. */
    fun hasPermission(
        pubKey: String,
        bit: Int,
    ): Boolean = !isBanned(pubKey) && effectivePermissions(pubKey).has(bit)

    /**
     * True if the member is **staff** (CORD-04 §3): the owner, or any non-banned
     * holder of a Control-writing bit ([ConcordPermissions.STAFF_BITS]) — the set
     * that holds the `control_root` (CORD-02 §2).
     */
    fun isStaff(pubKey: String): Boolean = isOwner(pubKey) || (!isBanned(pubKey) && effectivePermissions(pubKey).hasAny(ConcordPermissions.STAFF_BITS))

    /**
     * The staff roster (lowercase hex): the owner plus every role-holder whose
     * effective permissions carry a staff bit. This is the recipient set that gets
     * the `control_root` in a base rotation's 136-byte blobs (CORD-06 §1).
     */
    fun staffMembers(): Set<String> = memberRoles.keys.filterTo(hashSetOf(ownerLower)) { isStaff(it) }

    /**
     * [staffMembers] among a known membership: the owner, every Role holder carrying a staff bit,
     * and, when `@everyone` itself carries one (CORD-04 §3: then every member is staff), every
     * unbanned member of [members]. Plain membership leaves no Control Plane trace, so only the
     * caller knows who the members are; a rotation that must reach all staff passes them here.
     */
    fun staffMembers(members: Collection<String>): Set<String> {
        val out = HashSet(staffMembers())
        if (ranked?.everyone?.hasAny(ConcordPermissions.STAFF_BITS) == true) {
            for (m in members) if (isStaff(m)) out.add(m.lowercase())
        }
        return out
    }

    /**
     * Whether [actor] may take the action guarded by permission [bit] against
     * [target]. Requires: actor not banned, actor holds [bit], the owner is never
     * a valid target (unremovable), and actor strictly outranks target — "equal
     * cannot act on equal".
     */
    fun canActOn(
        actor: String,
        target: String,
        bit: Int,
    ): Boolean {
        if (!hasPermission(actor, bit)) return false
        if (isOwner(target)) return false
        val actorRank = rank(actor) ?: return false
        val targetRank = rank(target) ?: Long.MAX_VALUE // no roles ⇒ lowest authority
        return actorRank < targetRank
    }

    /**
     * Whether a role edition by [author] that makes the role [roleId] read [edited] passes every
     * reader's role gate (CORD-04 §2), the Grant citation aside (the edition carries it; see
     * [citationFor]): no role may take the owner's position 0; the owner may write any other; anyone
     * else must be unbanned, hold MANAGE_ROLES through a role, strictly outrank the role where it
     * stands and (unless [edited] deletes it) where it moves to, and hold every permission [edited]
     * carries. A client asks this before publishing an edition every reader would drop.
     */
    fun canWriteRole(
        author: String,
        roleId: String,
        edited: RoleEntity,
    ): Boolean {
        if (!edited.deleted && edited.position < 1) return false
        val a = author.lowercase()
        if (a == ownerLower) return true
        if (a in banned) return false
        val held = rolesFor(a)
        if (held.none { it.permissionBits().has(ConcordPermissions.MANAGE_ROLES) }) return false
        val authorRank = rank(a) ?: return false
        val current = roles[roleId]?.position
        if (current != null && current <= authorRank) return false
        if (edited.deleted) return true
        if (edited.position <= authorRank) return false
        var bits = ConcordPermissions.NONE
        for (role in held) bits = bits union role.permissionBits()
        return bits.hasAll(edited.permissionBits())
    }

    /** [member]'s honored Grant head, or null when they hold none (the owner never does). */
    fun grantHead(member: String): GrantHead? = grantHeads[member.lowercase()]

    /**
     * The `vac` citation (CORD-04 §1/§5) [actor] must attach to a Control-authority action:
     * their own Grant coordinate, pinned at the version and hash of the head this roster
     * folded. Null for the owner (who cites nothing) and for an actor holding no honored
     * Grant (whose actions no reader would honor anyway).
     */
    fun citationFor(actor: String): AuthorityCitation? {
        val m = actor.lowercase()
        if (m == ownerLower) return null
        val head = grantHeads[m] ?: return null
        val coordinate = grantCoordinateOf(m) ?: return null
        return AuthorityCitation(coordinate.hexToByteArray(), head.version, head.hashHex.hexToByteArray())
    }

    /**
     * Whether [citation] satisfies CORD-04 §5 for an action by [actor] against this roster:
     * the owner needs none; anyone else must cite their **own** Grant coordinate, and this
     * roster must hold that Grant at the cited version with the cited hash, or past it. A
     * citation ahead of what we hold (not yet synced), at a forked hash, or naming someone
     * else's Grant parks the action — here, drops it until a later fold. It is a sync floor,
     * never the verdict: the caller still judges rank against the current roster.
     */
    fun citationSatisfied(
        actor: String,
        citation: AuthorityCitation?,
    ): Boolean {
        val m = actor.lowercase()
        if (m == ownerLower) return true
        val head = grantHeads[m] ?: return false
        val coordinate = grantCoordinateOf(m) ?: return false
        return citationMatches(citation, coordinate, head)
    }

    /** [citationSatisfied] for [edition]'s author and `vac`. */
    fun citationSatisfied(edition: ControlEdition): Boolean = citationSatisfied(edition.author, edition.authorityCitation)

    /**
     * Whether an edition's content may stand at its coordinate at all, independent of who
     * signed it: the entity coordinates CORD-04 §1 derives from the `community_id` (Metadata
     * at the `community_id`, a Grant at its member's `grant_locator`, the Banlist at
     * `banlist_locator`, an Invite Registry at its author's locator) and the content caps
     * (CORD-04 §2, CORD-02 §6). A sub-kind we do not model is judged by its signer alone.
     */
    fun isWellFormed(edition: ControlEdition): Boolean = wellFormed(edition, communityId, communityIdHex, banlistEidHex)

    /**
     * Whether a reader honors [edition] as an action gated by [bit] (CORD-04 §5): it is
     * [isWellFormed], its author is the owner or holds [bit] (and is not banned), and its
     * `vac` resolves ([citationSatisfied]). A null [bit] is owner-only.
     */
    fun admits(
        edition: ControlEdition,
        bit: Int?,
    ): Boolean {
        if (!isWellFormed(edition)) return false
        if (isOwner(edition.author)) return true
        if (bit == null) return false
        // A Tenure-sealed standing keeps what a reduced member decided (CORD-04 §9): the listed
        // edition is judged on the stronger of the sealed bits and the Roster, and needs no `vac`.
        val sealed = ranked != null && sealedFor(edition.author, edition.hashHex).has(bit)
        if (!sealed && !hasPermission(edition.author, bit)) return false
        return isTenureListed(edition.author, edition.hashHex) || citationSatisfied(edition)
    }

    companion object {
        private const val TAG = "ConcordAuthorityResolver"

        /** The owner's rank — supreme and unremovable. No Role may claim it. */
        const val OWNER_RANK = 0L

        /**
         * How many times [resolve] will re-fold chasing a stable banlist. Real communities settle on
         * the first or second — the mask only moves when a banned member authored a *ban*, and it
         * stops moving as soon as those are gone. The cap is a termination backstop for an
         * adversarial edition set, not a tuning knob.
         */
        private const val MAX_BAN_RESOLUTION_PASSES = 4

        /** `grant_locator(community_id, member)` as hex, or null when [member] is not a 32-byte hex key. */
        internal fun grantCoordinateOrNull(
            communityId: ByteArray,
            member: String,
        ): String? {
            val xOnly = member.hexToByteArrayOrNull()?.takeIf { it.size == 32 } ?: return null
            return ConcordKeyDerivation.grantCoordinate(communityId, xOnly).toHexKey()
        }

        private fun banlistCoordinateHex(communityId: ByteArray): String = ConcordKeyDerivation.banlistCoordinate(communityId).toHexKey()

        /**
         * The CORD-04 §5 citation test against the Grant head [head] a verifier holds at the
         * actor's coordinate [expectedGrantIdHex] — Armada's `citationSatisfied`, case for case:
         * the citation must name that coordinate, and the head must be past the cited version,
         * or at it with the same hash. Behind it (unsynced) or at a forked hash, it parks.
         */
        internal fun citationMatches(
            citation: AuthorityCitation?,
            expectedGrantIdHex: String,
            head: GrantHead?,
        ): Boolean {
            if (citation == null || head == null) return false
            if (citation.grantId.toHexKey() != expectedGrantIdHex) return false
            if (head.version > citation.grantVersion) return true
            if (head.version == citation.grantVersion) return head.hashHex == citation.grantHash.toHexKey()
            return false
        }

        /** The Grant content at [edition], or null when it does not sit at its member's own coordinate (S5). */
        private fun grantAt(
            edition: ControlEdition,
            communityId: ByteArray,
        ): GrantEntity? {
            val g = ConcordJson.decodeOrNull<GrantEntity>(edition.content) ?: return null
            val coordinate = grantCoordinateOrNull(communityId, g.member.lowercase()) ?: return null
            return g.takeIf { coordinate == edition.entityIdHex }
        }

        /**
         * `invite_links_locator(community, author)` as hex, memoized: it is an HKDF per call, and the
         * well-formedness gate asks it for every registry edition on every refold of every community.
         * The derivation is a pure function of its inputs, so a cached value can never go stale.
         */
        private val inviteLinksCoordinates = ConcurrentLruCache<String, String>(1024)

        internal fun inviteLinksCoordinateHex(
            communityId: ByteArray,
            communityIdHex: String,
            author: String,
        ): String {
            val key = communityIdHex + author.lowercase()
            inviteLinksCoordinates.get(key)?.let { return it }
            val coordinate = ConcordKeyDerivation.inviteLinksCoordinate(communityId, author.hexToByteArray()).toHexKey()
            inviteLinksCoordinates.put(key, coordinate)
            return coordinate
        }

        /** See [isWellFormed]. */
        private fun wellFormed(
            edition: ControlEdition,
            communityId: ByteArray,
            communityIdHex: String,
            banlistEidHex: String,
        ): Boolean =
            when (edition.entityKind) {
                ControlEntityKind.METADATA ->
                    edition.entityIdHex == communityIdHex &&
                        ConcordJson.decodeOrNull<MetadataEntity>(edition.content)?.let(ConcordLimits::metadataFits) == true
                ControlEntityKind.ROLE -> ConcordJson.decodeOrNull<RoleEntity>(edition.content)?.isWellFormedAt(edition.entityIdHex) == true
                ControlEntityKind.GRANT -> grantAt(edition, communityId) != null
                ControlEntityKind.BANLIST -> edition.entityIdHex == banlistEidHex && ConcordJson.decodeBanlist(edition.content) != null
                // CORD-05 §5: the coordinate binds to the author, so each creator owns exactly their own
                // list; the content must be a JSON array (a malformed one falls back to the previous head).
                ControlEntityKind.INVITE_REGISTRY ->
                    edition.entityIdHex == inviteLinksCoordinateHex(communityId, communityIdHex, edition.author) &&
                        ConcordInviteRegistry.isWellFormed(edition.content)
                else -> true
            }

        /**
         * The owner-rooted authority state of a community, with the banlist honored **against the
         * Control Plane itself** (CORD-04 §4: a reader "drops every event from a banned npub —
         * message, reaction, edit, or authority action").
         *
         * [communityId] pins every derived coordinate: a Grant is honored only at its member's own
         * `grant_locator(community_id, member)` and the Banlist only at `banlist_locator(community_id)`
         * (CORD-02 A.6), so a second chain minted at any other coordinate cannot override the
         * canonical one.
         *
         * This is a bounded two-pass, because the rule is circular as stated: you cannot know who is
         * banned until you fold the Banlist, and you cannot decide who may write the Banlist without
         * knowing who is banned. `docs/concord-banlist-rank-conformance.md` §4 row 3 flagged that to
         * the spec authors and left it open. We resolve it by making authority only ever **shrink**:
         *
         *  - **Pass A** resolves exactly as before, ban-blind, and yields a candidate banlist.
         *  - **Pass B** re-resolves with every author in that banlist treated as unauthorized, for
         *    roles, grants and the banlist alike.
         *
         * Two passes, always, so it terminates by construction — pass B never feeds back. It cannot
         * oscillate on mutual bans either, because the rank rule makes them unreachable: only a
         * member who strictly outranks you may ban you, and you cannot outrank them back.
         *
         * **This cascades, deliberately.** Every edition a banned member ever authored is dropped,
         * including grants they made while in good standing — so banning an admin also demotes
         * everyone that admin promoted. That is the literal reading of §4, and it is the point: the
         * escalation in `docs/concord-soft-ban-audit.md` B2 was a banned staffer minting a fresh,
         * un-banned npub and acting through it, and dropping the grant is what kills the puppet. The
         * cost is that a legitimate promotion by a later-banned admin vanishes too, and the owner has
         * to re-issue it.
         *
         * **Consensus-affecting.** Armada gates the Control Plane on role-derived permissions alone,
         * so until it ships the same rule the two clients can disagree about any community where a
         * privileged member was banned.
         */
        fun resolve(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
        ): AuthorityResolver = resolve(editions, communityId, ownerPubKey, ConcordJson::decodeBanlist)

        /**
         * [resolve] with the legacy Banlist body decoder supplied, so a test can count how often a
         * resolution parses a Banlist (each body must be parsed once per pass, not once per fold step).
         */
        internal fun resolve(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            banlistDecoder: (String) -> List<String>?,
        ): AuthorityResolver {
            // CORD-04 §1: a marked edition bound to another Community, and an unmarked edition of a
            // kind no older client wrote, are refused before anything folds.
            @Suppress("NAME_SHADOWING")
            val editions = ControlEdition.admissibleIn(editions, communityId)
            // The activation check IS the ban-blind Roster fold: run it once and reuse it (CORD-04 §2).
            RankedRoster.foldIfActivated(editions, communityId, ownerPubKey)?.let { return resolveRanked(editions, communityId, ownerPubKey, it) }

            val passA = resolveOnce(editions, communityId, ownerPubKey, bannedAuthors = emptySet(), banlistDecoder)
            // A further pass costs a whole fold, so skip it unless it could change something. Nobody
            // banned, or nobody banned who ever wrote to the Control Plane — the overwhelmingly common
            // shape, since most bans land on plain members who hold no role and author no editions —
            // and the next pass is provably identical to this one. Armada's fold checks the same.
            if (passA.banned.isEmpty()) return passA
            if (editions.none { it.author.lowercase() in passA.banned }) return passA

            // Iterate to a fixpoint where the mask a pass was resolved UNDER equals the banlist that
            // pass produced. Stopping at two passes leaves those two disagreeing, and the disagreement
            // is not cosmetic: a moderator whose only ban came from an admin the owner banned
            // concurrently is released by pass 2 — correctly — but pass 2 dropped her editions too,
            // because she was on pass 1's list. The fold then reports her as a moderator in good
            // standing whose promotions have silently vanished, and it does so deterministically, so
            // she never gets them back.
            //
            // The mask cannot simply be assumed to shrink: masking an author can strip a THIRD
            // member's role, dropping their rank to "roleless", which lets a junior BAN holder who
            // could not previously reach them ban them after all. So this is bounded rather than
            // proven monotone, and it keeps the last pass it computed if it somehow does not settle —
            // still strictly better than the two-pass answer, and it always terminates.
            var mask = passA.banned
            var result = passA
            repeat(MAX_BAN_RESOLUTION_PASSES) {
                result = resolveOnce(editions, communityId, ownerPubKey, bannedAuthors = mask, banlistDecoder)
                if (result.banned == mask) return result
                mask = result.banned
            }
            // Exhausted the cap without settling. The returned roster was folded under a mask that is
            // no longer the banlist beside it, so this is reported rather than swallowed — the same
            // reasoning as EditionFold.LOG_GAP: an unsettled fold is either an adversarial edition set
            // or a rule of ours that does not converge, and both are things a reader wants to know.
            Log.w(TAG) {
                "Banlist resolution did not settle in $MAX_BAN_RESOLUTION_PASSES passes for owner $ownerPubKey " +
                    "(${editions.size} editions, ${result.banned.size} banned): keeping the last pass"
            }
            return result
        }

        /**
         * The Roles v2 resolution (CORD-04 §2–§4, §9), Armada's `foldControlState` for a reader
         * holding no floor: fold the Roster, fold the Banlist on it, and when the Banlist names
         * anyone, fold the Roster again with the banned members' editions refused except what a
         * Tenure keeps. The Banlist itself is the one judged before the ban took anyone's support.
         */
        private fun resolveRanked(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            first: RankedRosterFold,
        ): AuthorityResolver {
            val ownerLower = ownerPubKey.lowercase()
            val communityIdHex = communityId.toHexKey()
            val banlistHead = rankedBanlistHead(editions, communityId, ownerLower, first)
            val banned = rankedBanlist(banlistHead, ownerLower, first)
            val roster = if (banned.isEmpty()) first else RankedRoster.foldAdmitted(editions, communityId, ownerLower, excluded = banned)

            val roles = LinkedHashMap<String, RoleEntity>()
            for (r in roster.roles) {
                roles[r.roleId] = RoleEntity(roleId = r.roleId, name = r.name, position = r.position, permissions = r.permissions.toWire(), color = r.color)
            }
            val memberRoles = roster.grants.associate { it.member to it.roleIds.toSet() }
            val grantHeads = HashMap<String, GrantHead>()
            for (g in roster.grants) {
                val coordinate = grantCoordinateOrNull(communityId, g.member) ?: continue
                roster.heads[coordinate]?.let { grantHeads[g.member] = GrantHead(it.version, it.hashHex) }
            }
            // A Grant that hands no standing Role still pins its member's citations (CORD-04 §5).
            for ((eid, head) in roster.heads) {
                if (head.vsk != ControlEntityKind.GRANT.wire) continue
                val member = RankedRosterContent.parseGrant(head.content)?.member ?: continue
                if (grantCoordinateOrNull(communityId, member) == eid) grantHeads.getOrPut(member) { GrantHead(head.version, head.hashHex) }
            }
            val heads = HashMap(roster.heads)
            if (banlistHead != null) heads[banlistHead.entityIdHex] = banlistHead
            return AuthorityResolver(communityIdHex, ownerLower, roles, memberRoles, banned, grantHeads, roster, AuthorityHeads(heads))
        }

        /**
         * The Roles v2 Banlist head (CORD-04 §4): among the editions whose signer holds BAN through
         * their Roles (`@everyone`'s bits author no Banlist) or a sealed standing, citing their Grant.
         */
        private fun rankedBanlistHead(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerLower: String,
            roster: RankedRosterFold,
        ): ControlEdition? {
            val banlistEid = banlistCoordinateHex(communityId)
            val list = editions.filter { it.entityKind == ControlEntityKind.BANLIST && it.entityIdHex == banlistEid }
            if (list.isEmpty()) return null
            val grantHeads = HashMap<String, GrantHead>()
            for ((eid, head) in roster.heads) if (head.vsk == ControlEntityKind.GRANT.wire) grantHeads[eid] = GrantHead(head.version, head.hashHex)

            fun rankOf(member: String): Long? = if (member == ownerLower) OWNER_RANK else roster.highestPosition(member)

            fun sealedBan(e: ControlEdition) = roster.sealOf(e.author, e.hashHex, RankedRoster.BENEATH_EVERY_ROLE).has(ConcordPermissions.BAN)

            fun cited(e: ControlEdition): Boolean {
                val author = e.author.lowercase()
                if (author == ownerLower) return true
                val coordinate = grantCoordinateOrNull(communityId, author) ?: return false
                return citationMatches(e.authorityCitation, coordinate, grantHeads[coordinate])
            }

            fun admissible(e: ControlEdition): Boolean {
                if (ConcordJson.decodeBanlist(e.content) == null) return false
                val author = e.author.lowercase()
                val bans = author == ownerLower || roster.permissionsAbove(author, RankedRoster.BENEATH_EVERY_ROLE).has(ConcordPermissions.BAN) || sealedBan(e)
                return bans && (sealedBan(e) || cited(e))
            }

            return EditionFold.foldEntityGated(list, rank = { a -> rankOf(a.lowercase()) ?: Long.MAX_VALUE }, gate = ::admissible)
        }

        /**
         * The Roles v2 Banlist (CORD-04 §4): the [head]'s list alone, each entry its author may name
         * (BAN and a strict outrank, or a sealed standing above the target), the first 500 of them.
         */
        private fun rankedBanlist(
            head: ControlEdition?,
            ownerLower: String,
            roster: RankedRosterFold,
        ): Set<String> {
            if (head == null) return emptySet()

            fun rankOf(member: String): Long? = if (member == ownerLower) OWNER_RANK else roster.highestPosition(member)

            fun entitled(
                e: ControlEdition,
                target: String,
            ): Boolean {
                if (target == ownerLower) return false
                val author = e.author.lowercase()
                if (author == ownerLower) return true
                val targetRank = rankOf(target) ?: RankedRoster.BENEATH_EVERY_ROLE
                val authorRank = rankOf(author)
                val viaRoles =
                    authorRank != null && authorRank < targetRank &&
                        ((roster.everyone ?: ConcordPermissions.NONE) union roster.rolesOf(author).fold(ConcordPermissions.NONE) { acc, r -> acc union r.permissions }).has(ConcordPermissions.BAN)
                return viaRoles || roster.sealOf(author, e.hashHex, targetRank).has(ConcordPermissions.BAN)
            }

            return ConcordJson
                .decodeBanlist(head.content)
                ?.map { it.lowercase() }
                ?.filter { entitled(head, it) }
                ?.take(MAX_BANLIST)
                ?.filterTo(HashSet()) { RankedRosterContent.isHex64(it) } ?: emptySet()
        }

        /** The Banlist entries a reader keeps (CORD-04 §4). */
        private const val MAX_BANLIST = 500

        /**
         * One resolution pass. [bannedAuthors] are treated as holding no authority at all — their
         * role, grant and banlist editions are dropped rather than merely being unable to act on
         * others. Empty on pass A; pass A's banlist on pass B. See [resolve].
         */
        private fun resolveOnce(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            bannedAuthors: Set<String>,
            banlistDecoder: (String) -> List<String>?,
        ): AuthorityResolver {
            val ownerLower = ownerPubKey.lowercase()
            val communityIdHex = communityId.toHexKey()

            // grant_locator(community_id, member) for every author the gates ask about, derived once.
            val grantCoordinates = HashMap<String, String?>()

            fun grantCoordinateOf(member: String): String? = grantCoordinates.getOrPut(member) { grantCoordinateOrNull(communityId, member) }

            // Chains grouped by entity: one role chain per role id, one grant chain per member
            // coordinate. We fold each chain through AUTHORIZED editions only, so a rogue cannot
            // supersede a legit edition by minting a higher version from an unprivileged key
            // (CORD-04 §1: "an edition whose signer isn't authorized is dropped").
            val roleChains = editions.filter { it.entityKind == ControlEntityKind.ROLE }.groupBy { it.entityIdHex }
            val grantChains = editions.filter { it.entityKind == ControlEntityKind.GRANT }.groupBy { it.entityIdHex }

            var roles: Map<String, RoleEntity> = emptyMap()
            var memberRoles: Map<String, Set<String>> = emptyMap()
            var grantHeads: Map<String, GrantHead> = emptyMap()
            // The gated head of every Role and Grant chain, deleted Roles included: what a floor keeps.
            var headEditions: Map<String, ControlEdition> = emptyMap()

            // Authority helpers read the CURRENT (previous-pass) roster, so within a pass a granter's
            // rank is judged by the chain already settled behind it — the owner-rooted resolution the
            // spec requires ("the fold starts at the owner ... and resolves outward").
            fun rankOf(member: String): Long? {
                if (member == ownerLower) return OWNER_RANK
                val held = memberRoles[member] ?: return null
                return held.mapNotNull { roles[it]?.position }.minOrNull()
            }

            // Authority first at an equal-version tie (CORD-04 §1), judged by the same settled roster.
            fun tieRank(author: String): Long = rankOf(author.lowercase()) ?: Long.MAX_VALUE

            // The bits a member currently holds, evaluated against the chain settled so far — the same
            // owner-rooted basis as rankOf. Needed inside the fixpoint; effectivePermissionsOf below is
            // the post-settlement view.
            fun bitsOf(member: String): ConcordPermissions {
                if (member == ownerLower) return ConcordPermissions.ALL
                val held = memberRoles[member] ?: return ConcordPermissions.NONE
                var acc = ConcordPermissions.NONE
                for (id in held) roles[id]?.let { acc = acc union it.permissionBits() }
                return acc
            }

            fun holdsManageRoles(member: String): Boolean {
                if (member == ownerLower) return true
                val held = memberRoles[member] ?: return false
                return held.any { roles[it]?.permissionBits()?.has(ConcordPermissions.MANAGE_ROLES) == true }
            }

            // CORD-04 §5: a non-owner edition must cite the exact Grant it acts under — its author's
            // own coordinate — and we must hold that Grant at or past the cited version, hash
            // matching at equality. Judged against the Grant heads settled so far, so the owner's
            // grants settle first and delegation bootstraps outward (Armada `citedOk`).
            fun cited(e: ControlEdition): Boolean {
                val author = e.author.lowercase()
                if (author == ownerLower) return true
                val coordinate = grantCoordinateOf(author) ?: return false
                return citationMatches(e.authorityCitation, coordinate, grantHeads[author])
            }

            // CORD-04 §5: a Role or Grant edition MAY omit `vac` and is then judged on rank alone, with
            // no floor to wait for; a citation that is present binds (Armada `citedOk`: "an uncited
            // edition declared no floor to wait for").
            fun citedIfCiting(e: ControlEdition): Boolean = e.authorityCitation == null || cited(e)

            // ...except a strip: a Grant whose role ids name no Role any edition is served for MUST cite.
            val servedRoles = roleChains.keys.mapTo(HashSet()) { it.lowercase() }

            // Owner-rooted fixpoint: each pass only ever empowers members reachable from the owner, so
            // the roster grows monotonically and settles. Bounded by the edition count as a backstop.
            val maxPasses = editions.size + 1
            var pass = 0
            while (pass++ <= maxPasses) {
                // Roles: a role edition is authorized when its author is the owner or holds MANAGE_ROLES.
                // The gate is applied to the chain's ORDERED CANDIDATES, never used to pre-filter the
                // chain — see EditionFold.candidates for why filtering first orphans honest editions.
                fun roleGate(
                    entity: String,
                    e: ControlEdition,
                ): Boolean {
                    // Well-formed first, for every author: a role_id naming another coordinate, an
                    // over-long name, or a live role at the owner's position 0 is no role at all.
                    val r = ConcordJson.decodeOrNull<RoleEntity>(e.content) ?: return false
                    if (!r.isWellFormedAt(entity)) return false
                    val author = e.author.lowercase()
                    if (author == ownerLower) return true
                    if (author in bannedAuthors) return false
                    if (!holdsManageRoles(author)) return false
                    if (!citedIfCiting(e)) return false
                    val authorRank = rankOf(author) ?: return false
                    // MANAGE_ROLES alone was the whole test, which let any holder rewrite the
                    // role they hold — position 1 with every bit — and then demote the real
                    // admins beneath them. Grants are gated on rank (a granter must outrank
                    // what it hands out); role editions must be too, in both directions:
                    //   - it may not claim a position at or above the author's own rank, and
                    //   - it may not touch a role that already sits at or above them.
                    // A delete keeps only the second rule: you may retire a role beneath you.
                    val currentPosition = roles[entity]?.position
                    if (currentPosition != null && currentPosition <= authorRank) return false
                    if (!r.deleted && r.position <= authorRank) return false
                    // Nor may it grant bits the author does not itself hold, which would
                    // otherwise escalate through a role rather than through a grant.
                    return r.deleted || bitsOf(author).hasAll(r.permissionBits())
                }

                val newRoles = HashMap<String, RoleEntity>()
                val newHeads = HashMap<String, ControlEdition>()
                for ((entity, chain) in roleChains) {
                    val head = EditionFold.foldEntityGated(chain, rank = ::tieRank) { roleGate(entity, it) } ?: continue
                    newHeads[entity] = head
                    val r = ConcordJson.decodeOrNull<RoleEntity>(head.content) ?: continue
                    if (r.deleted || r.position < 1) continue // no role may claim the owner's position 0
                    newRoles[entity] = r
                }

                // Grants: an edition is authorized when its granter is the owner, or holds MANAGE_ROLES
                // AND strictly outranks every role it hands out. Same candidate-then-gate shape, so a
                // rogue grant is dropped without orphaning the honest grants chained above it.
                fun grantGate(e: ControlEdition): Boolean {
                    // The coordinate must be the member's own grant_locator (CORD-04 §1, S5): a chain
                    // minted anywhere else is not that member's Grant, whoever signed it.
                    val g = grantAt(e, communityId) ?: return false
                    val granter = e.author.lowercase()
                    if (granter == ownerLower) return true
                    if (granter in bannedAuthors) return false
                    if (!holdsManageRoles(granter)) return false
                    val strip = g.roleIds.none { it.lowercase() in servedRoles }
                    if (if (strip) !cited(e) else !citedIfCiting(e)) return false
                    val granterRank = rankOf(granter) ?: return false
                    // Must strictly outrank each assigned role that actually exists...
                    val assigned = g.roleIds.take(ConcordLimits.MAX_ROLES_PER_MEMBER)
                    if (!assigned.all { rid -> newRoles[rid]?.let { granterRank < it.position } ?: true }) return false
                    // ...and outrank the member being edited. A grant is an action ON that
                    // member, and a REVOKE carries no role ids at all — `all {}` over an
                    // empty list is vacuously true, so without this any MANAGE_ROLES holder
                    // could strip anyone's roles, the owner's admins included. Demotion has
                    // to be at least as hard as promotion.
                    val targetRank = rankOf(g.member.lowercase())
                    return targetRank == null || granterRank < targetRank
                }

                val newMemberRoles = HashMap<String, Set<String>>()
                val newGrantHeads = HashMap<String, GrantHead>()
                for ((_, chain) in grantChains) {
                    val head = EditionFold.foldEntityGated(chain, rank = ::tieRank, gate = ::grantGate) ?: continue
                    val g = grantAt(head, communityId) ?: continue
                    newHeads[head.entityIdHex] = head
                    val member = g.member.lowercase()
                    // A member holds at most 64 Roles (CORD-04 §2): the rest of the list is ignored.
                    newMemberRoles[member] =
                        g.roleIds
                            .take(ConcordLimits.MAX_ROLES_PER_MEMBER)
                            .filter { newRoles.containsKey(it) }
                            .toSet()
                    newGrantHeads[member] = GrantHead(head.version, head.hashHex)
                }

                headEditions = newHeads
                if (newRoles == roles && newMemberRoles == memberRoles && newGrantHeads == grantHeads) break
                roles = newRoles
                memberRoles = newMemberRoles
                grantHeads = newGrantHeads
            }

            // A Community carries at most 100 Roles (CORD-04 §2): fold the 100 lowest role_ids and
            // ignore the rest, after authorization, exactly where Armada trims them.
            if (roles.size > ConcordLimits.MAX_ROLES_PER_COMMUNITY) {
                val kept =
                    roles.keys
                        .sorted()
                        .take(ConcordLimits.MAX_ROLES_PER_COMMUNITY)
                        .toSet()
                roles = roles.filterKeys { it in kept }
                memberRoles = memberRoles.mapValues { (_, held) -> held.filterTo(HashSet()) { it in kept } }
            }

            // The union of a member's roles' permission bits (owner holds every bit).
            fun effectivePermissionsOf(member: String): ConcordPermissions {
                if (member == ownerLower) return ConcordPermissions.ALL
                val held = memberRoles[member] ?: return ConcordPermissions.NONE
                var acc = ConcordPermissions.NONE
                for (id in held) roles[id]?.let { acc = acc union it.permissionBits() }
                return acc
            }

            // Banlist: honored only from a signer holding BAN (or the owner), at the one coordinate
            // CORD-02 A.6 derives for it. It is a single replaced document folded to ONE head like any
            // entity (CORD-04 §4): two admins banning different members at the same version collide,
            // the fold keeps one edition (authority first, then the lower rumor id), and the loser's
            // addition drops until its writer re-heals it on top of the winner. Unioning every fork
            // instead made a ban on a losing fork impossible to lift — no later edition supersedes a
            // fork — and diverged from every other client's fold.
            val banlistEid = banlistCoordinateHex(communityId)
            val allBanlist = editions.filter { it.entityKind == ControlEntityKind.BANLIST && it.entityIdHex == banlistEid }

            // Every body parsed once per pass, lowercased, and every edition gated once: the delta
            // rule below folds the editions under each version it judges, and each of those folds
            // asks the gate about every edition again. Parsing per ask made a resolution cubic in
            // the length of the Banlist's history (a few hundred edits took seconds).
            val banBodies = HashMap<String, Set<String>?>()

            fun banBodyOf(e: ControlEdition): Set<String>? {
                if (banBodies.containsKey(e.content)) return banBodies[e.content]
                val body = banlistDecoder(e.content)?.mapTo(HashSet()) { it.lowercase() }
                banBodies[e.content] = body
                return body
            }

            // Keyed by the edition itself (identity), so two editions claiming one rumor id are judged apart.
            val banGates = HashMap<ControlEdition, Boolean>()

            fun banGate(e: ControlEdition): Boolean =
                banGates.getOrPut(e) {
                    if (banBodyOf(e) == null) return@getOrPut false
                    val author = e.author.lowercase()
                    if (author == ownerLower) return@getOrPut true
                    author !in bannedAuthors && effectivePermissionsOf(author).has(ConcordPermissions.BAN) && cited(e)
                }

            // CORD-04 §3's rank rule binds "every action", and it names banning as its example ("an
            // admin cannot ban a peer admin"); §5 step 3 restates it. Only §4, which defines the
            // Banlist, states the BAN-bit half alone — which is why every implementation (ours and
            // Armada's) shipped the bit check without the rank check, letting the most junior BAN
            // holder ban the admins above them and the owner. See
            // docs/concord-banlist-rank-conformance.md.
            //
            // The rule is stated per TARGET, but the Banlist is one whole-list document, so it is
            // enforced as a DELTA rule: an edition may only add or remove npubs its signer strictly
            // outranks. Entries it may not act on are ignored and the rest of the edition applies —
            // rejecting the whole edition would discard the bulk-ban §4 recommends as the collision
            // remedy, and would let a rogue grief the list by forcing rejections.
            fun canBanTarget(
                author: String,
                target: String,
            ): Boolean {
                // Position 0 is "supreme and unremovable" (§2) and nothing may outrank it, so the
                // owner is never a valid target — not even for themselves.
                if (target == ownerLower) return false
                if (author == ownerLower) return true
                if (author in bannedAuthors) return false
                if (!effectivePermissionsOf(author).has(ConcordPermissions.BAN)) return false
                val authorRank = rankOf(author) ?: return false
                val targetRank = rankOf(target) ?: Long.MAX_VALUE // no roles ⇒ lowest authority
                return authorRank < targetRank
            }

            // The head readers honour among the editions below version `v` (gated, authority first),
            // memoized per version: what an edition at `v` acts on (CORD-04 §3: "each version acts
            // on what the newest version the Roster admits below it left ... It is never judged on
            // what its own `prev` names alone: its author picks that, and could name a branch where
            // nothing stood"). Judging the delta against the author's own `prev` let a junior BAN
            // holder fork from before a senior's ban, add nothing, and lift it by omission.
            val headsBelow = HashMap<Long, ControlEdition?>()

            fun headBelow(version: Long): ControlEdition? {
                if (headsBelow.containsKey(version)) return headsBelow[version]
                val below = allBanlist.filter { it.version < version }
                val head = if (below.isEmpty()) null else EditionFold.foldEntityGated(below, rank = ::tieRank, gate = ::banGate)
                headsBelow[version] = head
                return head
            }

            val effective = HashMap<String, Set<String>>()

            // The list an edition actually establishes: the list the head below its version left,
            // plus only the additions its signer may make and minus only the removals its signer may
            // make. Each step goes strictly down in version, so it terminates; memoized per edition.
            fun effectiveList(edition: ControlEdition): Set<String> {
                effective[edition.rumorId]?.let { return it }

                val base = headBelow(edition.version)?.let { effectiveList(it) } ?: emptySet()
                val author = edition.author.lowercase()
                val claimed = banBodyOf(edition)

                // A malformed body changes nothing rather than clearing the list.
                val result =
                    if (claimed == null) {
                        base
                    } else {
                        val out = HashSet(base)
                        for (added in claimed - base) if (canBanTarget(author, added)) out.add(added)
                        for (removed in base - claimed) if (canBanTarget(author, removed)) out.remove(removed)
                        out
                    }

                effective[edition.rumorId] = result
                return result
            }

            // Candidate-then-gate, like roles and grants: an unauthorized banlist edition in the
            // middle of the chain must not orphan the authorized ones chained above it (which, on
            // a banlist, would silently resurrect every ban a later unban had cleared).
            val banHead = EditionFold.foldEntityGated(allBanlist, rank = ::tieRank, gate = ::banGate)
            val banned = banHead?.let { effectiveList(it) } ?: emptySet()

            return AuthorityResolver(communityIdHex, ownerLower, roles, memberRoles.toMap(), banned, grantHeads, null, AuthorityHeads(headEditions))
        }
    }
}

/**
 * The head editions an [AuthorityResolver] settled, keyed by entity id. Compared by each head's hash
 * and rumor id, so two resolvers folded from the same plane are equal however the editions were opened.
 */
internal class AuthorityHeads(
    val byEntity: Map<String, ControlEdition>,
) {
    private val identity: Map<String, String> by lazy { byEntity.mapValues { it.value.hashHex + ":" + it.value.rumorId } }

    override fun equals(other: Any?): Boolean = other is AuthorityHeads && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    companion object {
        val EMPTY = AuthorityHeads(emptyMap())
    }
}
