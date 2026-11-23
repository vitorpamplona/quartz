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
package com.vitorpamplona.quartz.concord.cord02Community

import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelOrder
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelSeal
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.cord03Channels.OpenedChannelSeal
import com.vitorpamplona.quartz.concord.cord03Channels.SealedChannelMeta
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityCitation
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelOverwrite
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.EditionFold
import com.vitorpamplona.quartz.concord.cord04Roles.EntityFloor
import com.vitorpamplona.quartz.concord.cord04Roles.MetadataEntity
import com.vitorpamplona.quartz.concord.cord04Roles.RoleEntity
import com.vitorpamplona.quartz.concord.cord04Roles.asFloor
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteRegistry
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A channel id paired with its current folded definition, and the two orders the sidebar sorts it by
 * ([ConcordChannelOrder]): the CORD-03 §2 [position] rank key and Armada's legacy [armadaOrder].
 */
data class ConcordChannel(
    val channelIdHex: String,
    val definition: ChannelEntity,
    /** The Channel's CORD-03 §2 `position` (a rank key), or null when it carries none or a malformed one. */
    val position: String? = null,
    /** Armada's legacy `custom["armada.order"].position`, or null ([ConcordChannelOrder.armadaOrderOf]). */
    val armadaOrder: Long? = null,
) {
    @Deprecated("Use the primary constructor. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
    constructor(channelIdHex: String, definition: ChannelEntity) : this(channelIdHex, definition, null, null)

    /** The `copy` this data class had before the order fields, kept so a caller compiled against it still links. */
    @Deprecated("Use copy with the order fields. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
    fun copy(
        channelIdHex: String = this.channelIdHex,
        definition: ChannelEntity = this.definition,
    ): ConcordChannel = ConcordChannel(channelIdHex, definition, position, armadaOrder)
}

/**
 * The current, folded state of a Concord community's Control Plane (CORD-02).
 *
 * Produced by [fold] from the set of decrypted, verified control editions plus
 * the community's known [ownerPubKey]. It exposes the community [metadata], the
 * live (non-deleted) [channels], the live [roles], the owner-rooted [authority]
 * resolver, and whether the community has been [dissolved].
 *
 * "Every member keeps the entire Control Plane in sync — it is small and must
 * stay complete." Recompute this whenever the known editions change.
 */
data class ConcordCommunityState(
    val ownerPubKey: String,
    val metadata: MetadataEntity?,
    val channels: Map<String, ConcordChannel>,
    val roles: Map<String, RoleEntity>,
    val authority: AuthorityResolver,
    val dissolved: Boolean,
    /**
     * Each creator's honored Invite Registry (CORD-05 §5, `vsk 8`): creator pubkey → the link-signer
     * pubkeys (lowercase) of their live public links. A creator is present only while their registry
     * head is honored — well-formed at their own coordinate, authored while holding `CREATE_INVITE`
     * (or by the owner), citing their Grant — so a creator who loses the bit drops out.
     */
    val inviteRegistries: Map<HexKey, List<HexKey>> = emptyMap(),
    /**
     * The sealed metadata of each Private Channel whose head is sealed (CORD-03 §2, Roles v2),
     * keyed by channel id. A key holder opens it with [ConcordChannelSeal.open]; [fold] already did
     * for the Channels whose keys it was handed, filling their names.
     */
    val sealedChannels: Map<HexKey, SealedChannelMeta> = emptyMap(),
    /** The sealed Channels [fold] could open, keyed by channel id: their details and `creator`. */
    val openedChannels: Map<HexKey, OpenedChannelSeal> = emptyMap(),
    /** Every standing channel-permission overwrite (CORD-04 §8, vsk 14). Empty on the legacy Roster. */
    val overwrites: List<ChannelOverwrite> = emptyList(),
    /** Channel id → the Category whose overwrites it takes (CORD-04 §8, vsk 17). */
    val syncs: Map<HexKey, HexKey> = emptyMap(),
    /** Each restricted Channel's folded Writers ledger (CORD-03 §1, vsk 18), keyed by channel id. */
    val writers: Map<HexKey, ConcordWritersLedger> = emptyMap(),
) {
    @Deprecated("Use the primary constructor. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
    constructor(
        ownerPubKey: String,
        metadata: MetadataEntity?,
        channels: Map<String, ConcordChannel>,
        roles: Map<String, RoleEntity>,
        authority: AuthorityResolver,
        dissolved: Boolean,
        // The default it had before the Roles v2 fields, so a caller compiled against it still links.
        inviteRegistries: Map<HexKey, List<HexKey>> = emptyMap(),
    ) : this(ownerPubKey, metadata, channels, roles, authority, dissolved, inviteRegistries, emptyMap())

    /** True once the Community is on Roles v2 (CORD-04 §2). */
    val isRolesV2: Boolean get() = authority.isRolesV2

    /** [overwrites] indexed by scope: every member × Channel query reads one scope's rows. */
    private val overwritesByScope: Map<String, List<ChannelOverwrite>> by lazy { overwrites.groupBy { it.scope } }

    /** The overwrites standing in [scope] (a Channel's or Category's id, lowercase hex). */
    fun overwritesIn(scope: HexKey): List<ChannelOverwrite> = overwritesByScope[scope.lowercase()] ?: emptyList()

    /**
     * [member]'s permissions in the Channel [channelIdHex] (CORD-04 §8), resolved Discord's way:
     * server bits, the `@everyone` overwrite, a Private Channel closed to `@everyone`, the Roles'
     * denies then allows, the sealed creator let in while they hold MANAGE_CHANNELS, the member's
     * own overwrite last, and nothing at all without VIEW_CHANNEL. The owner holds everything. On the
     * legacy Roster there are no channel permissions: this is the member's server permissions.
     */
    fun channelPermissions(
        member: HexKey,
        channelIdHex: HexKey,
        isPrivate: Boolean? = null,
    ): ConcordPermissions {
        val m = member.lowercase()
        if (authority.isOwner(m)) return ConcordPermissions.ALL
        val roster = authority.rankedRoster() ?: return authority.effectivePermissions(m)
        val channel = channelIdHex.lowercase()

        @Suppress("NAME_SHADOWING")
        val isPrivate = isPrivate ?: channels[channel]?.definition?.private ?: (channel in sealedChannels)
        val held = roster.rolesOf(m)
        var p = held.fold(roster.everyone ?: ConcordPermissions.NONE) { acc, r -> acc union r.permissions }
        val created = p.has(ConcordPermissions.MANAGE_CHANNELS) && openedChannels[channel]?.creator == m
        val scope = syncs[channel] ?: channel
        val here = overwritesByScope[scope] ?: emptyList()
        here.firstOrNull { it.targetKind == ChannelOverwrite.EVERYONE }?.let { p = (p minus it.deny) union it.allow }
        if (isPrivate) p = p.without(ConcordPermissions.VIEW_CHANNEL)
        var deny = ConcordPermissions.NONE
        var allow = ConcordPermissions.NONE
        val heldIds = held.mapTo(HashSet()) { it.roleId }
        for (o in here) {
            if (o.targetKind == ChannelOverwrite.ROLE && o.targetId in heldIds) {
                deny = deny union o.deny
                allow = allow union o.allow
            }
        }
        p = (p minus deny) union allow
        if (created) p = p.with(ConcordPermissions.VIEW_CHANNEL)
        here.firstOrNull { it.targetKind == ChannelOverwrite.MEMBER && it.targetId == m }?.let { p = (p minus it.deny) union it.allow }
        return if (p.has(ConcordPermissions.VIEW_CHANNEL)) p else ConcordPermissions.NONE
    }

    /**
     * Whether a reader honors a **moderation hide** (CORD-03 §3): a kind 5 on [channelIdHex]'s Chat
     * Plane by [hider] against a message [author] wrote, honored like the author's own delete when
     * the hider holds MANAGE_MESSAGES in the Channel (CORD-04 §8; server-wide on the legacy Roster),
     * strictly outranks the author, isn't banned, and cites the Grant it acts under ([citation],
     * the deletion's `vac`). Judged against the Roster as it stands, so a hide by someone later
     * stripped of the bit comes undone. A member's own delete is not a hide; the caller honors it.
     */
    fun honorsModerationHide(
        hider: HexKey,
        author: HexKey,
        channelIdHex: HexKey,
        citation: AuthorityCitation?,
    ): Boolean {
        val h = hider.lowercase()
        val a = author.lowercase()
        if (h == a) return false
        if (authority.isOwner(a) || authority.isBanned(h)) return false
        if (authority.isOwner(h)) return true
        val bits = if (authority.isRolesV2) channelPermissions(h, channelIdHex) else authority.effectivePermissions(h)
        if (!bits.has(ConcordPermissions.MANAGE_MESSAGES)) return false
        val hiderRank = authority.rank(h) ?: return false
        if (hiderRank >= (authority.rank(a) ?: Long.MAX_VALUE)) return false
        return authority.citationSatisfied(h, citation)
    }

    /**
     * Whether `@everyone`, resolved up to the View Channel step, may post in [channelIdHex]: one
     * where it can't is restricted (CORD-03 §1). The legacy Roster restricts nothing.
     */
    fun everyoneSends(channelIdHex: HexKey): Boolean {
        val everyone = authority.everyonePermissions() ?: return true
        val channel = channelIdHex.lowercase()
        val scope = syncs[channel] ?: channel
        val row = overwritesByScope[scope]?.firstOrNull { it.targetKind == ChannelOverwrite.EVERYONE }
        val p = if (row != null) (everyone minus row.deny) union row.allow else everyone
        return p.has(ConcordPermissions.SEND_MESSAGES)
    }

    /** The live [channels] in sidebar order ([ConcordChannelOrder]): `position`, Armada's legacy order, name, id. */
    val orderedChannels: List<ConcordChannel> by lazy { ConcordChannelOrder.sorted(channels.values) }

    /** The ids of the live (non-deleted) Private Channels (CORD-03), lowercase hex. */
    val privateChannelIds: Set<HexKey> by lazy { channels.filterValues { it.definition.private }.keys.mapTo(HashSet()) { it.lowercase() } }

    /** The aggregate active-set of live public links: every honored registry's link signers (CORD-05 §5). */
    val liveInviteLinks: Set<HexKey> by lazy { inviteRegistries.values.flatMapTo(HashSet()) { it } }

    /**
     * The community's Public/Private mode (CORD-05 §5): Public while any live link exists in the
     * aggregate registry set, Private otherwise. A Public ban is the Banlist alone; only a Private
     * ban Refounds (CORD-06 §3).
     */
    val isPublic: Boolean get() = liveInviteLinks.isNotEmpty()

    /**
     * [isPublic] with [excludingCreators]' registries left out — the mode a ban of those members
     * lands in, since a banned creator's registry stops being honored (Armada `isCommunityPublic`).
     */
    fun isPublic(excludingCreators: Collection<HexKey>): Boolean {
        if (excludingCreators.isEmpty()) return isPublic
        val excluded = excludingCreators.mapTo(HashSet()) { it.lowercase() }
        return inviteRegistries.any { (creator, links) -> creator !in excluded && links.isNotEmpty() }
    }

    /**
     * Whether any live link belongs to someone other than [viewer] (and [excludingCreators]) —
     * links a rotation by [viewer] would strand, since only a link's creator can refresh its bundle
     * (Armada `hasForeignLiveLinks`).
     */
    fun hasForeignLiveLinks(
        viewer: HexKey,
        excludingCreators: Collection<HexKey> = emptyList(),
    ): Boolean {
        val excluded = excludingCreators.mapTo(HashSet()) { it.lowercase() } + viewer.lowercase()
        return inviteRegistries.any { (creator, links) -> creator !in excluded && links.isNotEmpty() }
    }

    /**
     * Whether banning [targets] must Refound (CORD-06 §3): only a ban from a **Private** community
     * does; a Public ban is the Banlist alone, because anyone holding a live link can fetch the
     * rotated root straight back out of its bundle. Judged with the targets' own registries left
     * out, since the ban stops honoring them.
     */
    fun banRequiresRefounding(targets: Collection<HexKey>): Boolean = !isPublic(targets)

    /** [creator]'s honored registry (their live link signers), empty when they publish none. */
    fun registryOf(creator: HexKey): List<HexKey> = inviteRegistries[creator.lowercase()] ?: emptyList()

    /**
     * Whether retiring [linkSigners] would flip the community Private (CORD-05 §2): it is Public
     * now and no live link would remain. Retiring the last live link is a Refounding (CORD-06).
     * Never for a [dissolved] community: death wins every race (CORD-02 §9), so there is nothing
     * left to Refound and a revoke there is only a revoke.
     */
    fun retiringWouldPrivatize(linkSigners: Collection<HexKey>): Boolean {
        if (dissolved || !isPublic) return false
        val retiring = linkSigners.mapTo(HashSet()) { it.lowercase() }
        return liveInviteLinks.all { it in retiring }
    }

    /**
     * This state with [dissolved] set from the community's dissolution plane
     * ([ConcordDissolution.isDissolved]). One-way by the caller's contract: there is no un-dissolve.
     */
    fun withDissolved(dissolved: Boolean): ConcordCommunityState = if (dissolved == this.dissolved) this else copy(dissolved = dissolved)

    /**
     * The `copy` this data class had before the Roles v2 fields, kept so a caller compiled against it
     * (its `copy$default` stub) still links. The Roles v2 fields carry over unchanged.
     */
    @Deprecated("Use copy with the Roles v2 fields. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
    fun copy(
        ownerPubKey: String = this.ownerPubKey,
        metadata: MetadataEntity? = this.metadata,
        channels: Map<String, ConcordChannel> = this.channels,
        roles: Map<String, RoleEntity> = this.roles,
        authority: AuthorityResolver = this.authority,
        dissolved: Boolean = this.dissolved,
        inviteRegistries: Map<HexKey, List<HexKey>> = this.inviteRegistries,
    ): ConcordCommunityState =
        ConcordCommunityState(
            ownerPubKey,
            metadata,
            channels,
            roles,
            authority,
            dissolved,
            inviteRegistries,
            sealedChannels,
            openedChannels,
            overwrites,
            syncs,
            writers,
        )

    companion object {
        /**
         * The permission bit an edition of each entity kind must be authored under.
         * `null` means owner-only (no bit grants it). Mirrors the per-kind gating
         * [fold] applies before the structural fold.
         */
        internal fun requiredPermission(kind: ControlEntityKind): Int? =
            when (kind) {
                ControlEntityKind.METADATA -> ConcordPermissions.MANAGE_METADATA
                ControlEntityKind.CHANNEL -> ConcordPermissions.MANAGE_CHANNELS
                ControlEntityKind.ROLE, ControlEntityKind.GRANT -> ConcordPermissions.MANAGE_ROLES
                ControlEntityKind.BANLIST -> ConcordPermissions.BAN
                ControlEntityKind.INVITE_LIVE, ControlEntityKind.INVITE_REGISTRY, ControlEntityKind.INVITE_REVOKED -> ConcordPermissions.CREATE_INVITE
                ControlEntityKind.DISSOLVED -> null
                ControlEntityKind.PIN_LIST -> ConcordPermissions.PIN_MESSAGES
                ControlEntityKind.RANKED_ROLE, ControlEntityKind.ACL, ControlEntityKind.TENURE, ControlEntityKind.SYNC -> ConcordPermissions.MANAGE_ROLES
                ControlEntityKind.CATEGORY, ControlEntityKind.WRITERS -> ConcordPermissions.MANAGE_CHANNELS
            }

        /**
         * The authority-gated structural head of **every** control entity, keyed by
         * [ControlEdition.entityIdHex] — the source of the anti-rollback [EntityFloor]s a
         * client carries across a CORD-06 Refounding, and the set of heads a Refounding's
         * compaction re-wraps (sub-kinds we do not model included, so another client's Pins
         * or Signals survive our Refounding).
         *
         * Every entity is judged by the same gate [fold] applies to it, never a looser one: an
         * edition [fold] refuses must never become a head, or a Refounding would re-wrap it as the
         * entity's whole history (a moderator's forged deletion of the Admin Role, say) and every
         * reader of the new epoch would fold it. So the Roles, Grants and Tenures, and on Roles v2
         * the Banlist, are the heads the owner-rooted Roster fold itself settled
         * ([AuthorityResolver]); overwrites, syncs and Writers ledgers pass their own gates (downward
         * support, the sync's swap check, the ledger's chain rules), which read no `vac`, so a valid
         * uncited edition is kept; the rest pass the bit-and-citation gate of their kind.
         *
         * An *ungated* head map would let any ex-member who still holds a rotated-out root mint a
         * high-version edition on the old Control Plane and thereby raise our floor, freezing the
         * entity for us. The floor must only ever remember editions we would actually have honored.
         */
        fun authorizedHeads(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            floors: Map<String, EntityFloor> = emptyMap(),
        ): Map<String, EntityFloor> {
            val bound = ControlEdition.admissibleIn(editions, communityId)
            // This call folds exactly one epoch's editions, so they ARE the snapshot: an entity
            // appearing here has been re-wrapped into this epoch and must anchor on version, not
            // on a `prev` that necessarily dangles back into the epoch the floor came from. The
            // known heads `admissible` re-seats are not in the set, so an entity this epoch never
            // mentioned correctly keeps chain-walk semantics.
            val snapshot = bound.mapTo(HashSet(bound.size)) { it.rumorId }
            val pool = EditionFold.admissible(bound, floors, snapshot = snapshot)
            val authority = AuthorityResolver.resolve(pool, communityId, ownerPubKey)
            val plane = ControlPlaneFold(bound, pool, communityId, authority, floors, snapshot, TimeUtils.nowMillis())
            val out = HashMap<String, EntityFloor>(floors)

            fun raise(
                entity: String,
                head: ControlEdition,
            ) {
                // Monotonic: a floor only ever rises. Folding epoch by epoch, an entity the
                // newer epoch never mentions keeps the version the older one reached.
                val prior = out[entity]
                if (prior == null || head.version >= prior.version) out[entity] = head.asFloor()
            }

            for ((entity, head) in authority.authorityHeads()) raise(entity, head)
            for (kind in pool.mapTo(HashSet()) { it.entityKind }) {
                if (plane.settledByResolver(kind)) continue
                for ((entity, head) in plane.heads(kind)) raise(entity, head)
            }
            return out
        }

        /**
         * Folds one epoch's Control Plane [editions] of the community [communityId] (which pins
         * every derived entity coordinate, CORD-04 §1) owned by [ownerPubKey] into its current
         * state, honoring the anti-rollback [floors] carried from earlier epochs.
         */
        @Deprecated("Use the overload with channelConversationKey. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
        fun fold(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            floors: Map<String, EntityFloor> = emptyMap(),
        ): ConcordCommunityState = fold(editions, communityId, ownerPubKey, floors, null)

        /**
         * [fold], opening each sealed Private Channel (CORD-03 §2) whose conversation key at the seal's
         * epoch [channelConversationKey] returns (`group_key("concord/channel", channel_key,
         * channel_id, epoch).conv_key`), so its name lands in [ConcordCommunityState.channels].
         */
        fun fold(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            floors: Map<String, EntityFloor> = emptyMap(),
            channelConversationKey: ((channelIdHex: HexKey, epoch: Long) -> ByteArray?)? = null,
        ): ConcordCommunityState = fold(editions, communityId, ownerPubKey, floors, TimeUtils.nowMillis(), channelConversationKey)

        /**
         * [fold] read at the reader's clock [nowMillis], against which a Writers ledger entry dated
         * more than an hour ahead is refused (CORD-03 §1, CORD-02 §5).
         */
        fun fold(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
            ownerPubKey: String,
            floors: Map<String, EntityFloor>,
            nowMillis: Long,
            channelConversationKey: ((channelIdHex: HexKey, epoch: Long) -> ByteArray?)?,
        ): ConcordCommunityState {
            // CORD-04 §1: a marked edition bound to another Community, and an unmarked edition of a
            // kind no older client wrote, are refused before anything folds.
            val bound = ControlEdition.admissibleIn(editions, communityId)

            // Everything below folds a *derived* view of the same editions (the resolver's
            // authority chains, the per-kind gated folds), so the anti-rollback floor is applied
            // once, up front, on the shared pool: a rolled-back edition is never seen by any of
            // them, and the head we already folded is re-seated so the entity keeps its state.
            // The editions handed in are one epoch's — the current one — so they are the
            // snapshot that selects the compaction arm. Captured BEFORE `admissible` re-seats
            // known heads from older epochs, which must not be mistaken for this epoch's.
            val snapshot = bound.mapTo(HashSet(bound.size)) { it.rumorId }
            val pool = EditionFold.admissible(bound, floors, snapshot = snapshot)

            // Resolve authority from the FULL edition set (not the structural heads): the resolver
            // folds each role/grant chain through authorized editions only, so a rogue higher-version
            // edition can't supersede a legit one before authority is even judged.
            val authority = AuthorityResolver.resolve(pool, communityId, ownerPubKey)

            // CORD-04 §1: "an edition whose signer isn't authorized is dropped." Each kind's gate
            // (ControlPlaneFold) is applied to each entity's ORDERED CANDIDATES (chain head first, then
            // the remaining editions version-descending), never as a pre-filter on the chain: dropping
            // a rejected edition out of the middle of a chain permanently orphans every honest edition
            // above it, freezing the entity. See EditionFold.candidates. authorizedHeads reads the
            // same gates, so a floor never remembers an edition this fold refused.
            val plane = ControlPlaneFold(bound, pool, communityId, authority, floors, snapshot, nowMillis)

            // Metadata is ONE entity, at the community_id itself (CORD-04 §1): an edition at any
            // other coordinate is not this community's metadata however high its version, so it can
            // neither shadow the chain nor bypass it (S8). Name and description caps are fold gates.
            val metadata =
                plane
                    .heads(ControlEntityKind.METADATA)[communityId.toHexKey()]
                    ?.let { ConcordJson.decodeOrNull<MetadataEntity>(it.content) }

            // Channels are gated by MANAGE_CHANNELS, per channel entity, dropping the tombstoned ones.
            // Deletion is terminal (CORD-03 §2): any gated edition anywhere in a channel's accepted
            // chain that says `deleted` retires it for good, even if a later edition "restores" it —
            // members may already have discarded its keys, so a resurrection would split them.
            val everDeleted = plane.everDeletedChannels()
            val channels = LinkedHashMap<String, ConcordChannel>()
            val sealedChannels = HashMap<HexKey, SealedChannelMeta>()
            val openedChannels = HashMap<HexKey, OpenedChannelSeal>()
            for (head in plane.heads(ControlEntityKind.CHANNEL).values) {
                if (head.entityIdHex in everDeleted) continue
                val sealed = ConcordChannelSeal.readSealed(head.content, head.prevHash?.toHexKey())
                if (sealed != null) {
                    if (sealed.deleted) continue
                    sealedChannels[head.entityIdHex] = sealed
                    val opened =
                        channelConversationKey?.invoke(head.entityIdHex, sealed.epoch)?.let { ConcordChannelSeal.open(sealed, it) }
                    if (opened != null) openedChannels[head.entityIdHex] = opened
                    // ChannelEntity models only the name and the Private flag; a sealed Channel's other
                    // opened fields (topic, position, category, ...) are in [openedChannels].
                    channels[head.entityIdHex] =
                        ConcordChannel(
                            head.entityIdHex,
                            ChannelEntity(name = opened?.name ?: "", private = true),
                            opened?.fields?.let(ConcordChannelOrder::positionOf),
                            opened?.fields?.let(ConcordChannelOrder::armadaOrderOf),
                        )
                    continue
                }
                val def = ConcordJson.decodeOrNull<ChannelEntity>(head.content) ?: continue
                if (def.deleted) continue
                val (position, armadaOrder) = ConcordChannelOrder.ordersOf(head.content)
                channels[head.entityIdHex] = ConcordChannel(head.entityIdHex, def, position, armadaOrder)
            }

            // Role definitions come from the authority-gated fold (not the raw structural heads): a
            // rogue can mint a higher-version edition on a legit role's coordinate (e.g. marking the
            // Admin role deleted) that would win a structural fold and corrupt the displayed roster,
            // so we take the roles the AuthorityResolver actually accepted from the owner outward.
            val roles = authority.roles()

            // Dissolution is NOT read from the Control Plane. The tombstone is chainless and lives at its
            // own address (CORD-02 §9, [ConcordDissolution]) where it must also name this community in its
            // `eid`; a vsk-10 edition folded here would skip that binding check, so an owner's tombstone
            // for another community re-wrapped onto this plane would kill this one. The caller that reads
            // the dissolved plane sets [dissolved] via [withDissolved].
            val dissolved = false

            // Invite Registries (CORD-05 §5): one entity per creator at invite_links_locator(community_id,
            // creator), honored while its author holds CREATE_INVITE. The gate (AuthorityResolver.admits)
            // pins the coordinate to the author and requires a JSON array, so a registry at someone
            // else's coordinate or a malformed one never lands; entries are kept only when they are 64-hex.
            val inviteRegistries = HashMap<HexKey, List<HexKey>>()
            for (head in plane.heads(ControlEntityKind.INVITE_REGISTRY).values) {
                val links = ConcordInviteRegistry.decodeOrNull(head.content) ?: continue
                inviteRegistries[head.author.lowercase()] = links
            }

            return ConcordCommunityState(
                ownerPubKey = ownerPubKey.lowercase(),
                metadata = metadata,
                channels = channels,
                roles = roles,
                authority = authority,
                dissolved = dissolved,
                inviteRegistries = inviteRegistries,
                sealedChannels = sealedChannels,
                openedChannels = openedChannels,
                // Channel permissions (CORD-04 §8) and Writers ledgers (CORD-03 §1), on Roles v2 only.
                overwrites = plane.overwrites(),
                syncs = plane.syncs(),
                writers = plane.writers(),
            )
        }
    }
}
