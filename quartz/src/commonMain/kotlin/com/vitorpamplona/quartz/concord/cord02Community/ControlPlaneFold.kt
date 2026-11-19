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

import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelSeal
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.cord03Channels.WritersLedgerDoc
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelOverwrite
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.EditionFold
import com.vitorpamplona.quartz.concord.cord04Roles.EntityFloor
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRoster
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRosterContent
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlin.math.abs

/**
 * One epoch's Control Plane judged entity kind by entity kind: the gate each kind's editions must
 * pass and the head each entity settles on. [ConcordCommunityState.fold] reads its state from here
 * and [ConcordCommunityState.authorizedHeads] its floors and compaction heads, so the two can never
 * disagree on which editions stand.
 *
 * [pool] is the floor-admitted edition set the heads are folded from; [served] is every edition a
 * reader of the Community admits plus the heads its floors still hold, where a sync's or a ledger's
 * predecessor is looked up: it need only be served, not stand above the floor.
 */
internal class ControlPlaneFold(
    served: Collection<ControlEdition>,
    private val pool: List<ControlEdition>,
    private val communityId: ByteArray,
    private val authority: AuthorityResolver,
    private val floors: Map<String, EntityFloor>,
    private val snapshot: Set<String>,
    private val nowMillis: Long,
) {
    private val byKind: Map<ControlEntityKind?, List<ControlEdition>> = pool.groupBy { it.entityKind }

    private val served: List<ControlEdition> = served.toList() + floors.values.mapNotNull { it.known }

    private val roster = authority.rankedRoster()

    private val headsByKind = HashMap<ControlEntityKind?, Map<String, ControlEdition>>()

    /**
     * Whether the [AuthorityResolver] already settled [kind]'s heads ([AuthorityResolver.authorityHeads]):
     * the Roles and Grants on either Roster, and on Roles v2 the vsk-13 Roles, the Tenures and the Banlist.
     */
    fun settledByResolver(kind: ControlEntityKind?): Boolean =
        when (kind) {
            ControlEntityKind.ROLE, ControlEntityKind.GRANT -> true
            ControlEntityKind.RANKED_ROLE, ControlEntityKind.TENURE, ControlEntityKind.BANLIST -> roster != null
            else -> false
        }

    /** The head of every entity of [kind] (null: the sub-kinds this client does not model), each through its kind's gate. */
    fun heads(kind: ControlEntityKind?): Map<String, ControlEdition> =
        headsByKind.getOrPut(kind) {
            val list = byKind[kind] ?: emptyList()
            when {
                kind == null -> foldGated(list, ::honorsUnmodeled)
                kind == ControlEntityKind.CHANNEL -> foldGated(list, ::channelGate)
                roster != null && kind == ControlEntityKind.ACL -> foldGated(list, ::overwriteGate)
                roster != null && kind == ControlEntityKind.SYNC -> foldGated(list, ::syncGate)
                // A ledger tie goes to the lower rumor id alone, as Armada folds it (no authority order).
                roster != null && kind == ControlEntityKind.WRITERS -> EditionFold.foldGated(list, floors, snapshot = snapshot, gate = ::ledgerGate)
                else -> {
                    val bit = ConcordCommunityState.requiredPermission(kind)
                    foldGated(list) { authority.admits(it, bit) }
                }
            }
        }

    private fun foldGated(
        list: List<ControlEdition>,
        gate: (ControlEdition) -> Boolean,
    ): Map<String, ControlEdition> = EditionFold.foldGated(list, floors, snapshot = snapshot, rank = authority::tieBreakRank, gate = gate)

    // ---- Sub-kinds this client does not model -------------------------------------------------

    /**
     * A sub-kind we do not model is still gated — a floor or a compaction must only remember
     * editions some reader honors: a Signal by `MANAGE_CHANNELS` (the one gate Armada implements,
     * `pause`), and anything newer by any staff bit, the set whose actions are Control editions at
     * all (CORD-04 §3).
     */
    private fun honorsUnmodeled(edition: ControlEdition): Boolean =
        when (edition.vsk) {
            VSK_SIGNALS -> authority.admits(edition, ConcordPermissions.MANAGE_CHANNELS)
            else -> authority.isOwner(edition.author) || (authority.isStaff(edition.author) && authority.citationSatisfied(edition))
        }

    // ---- Channels (CORD-03 §2) ----------------------------------------------------------------

    private val legacyChannels = authority.legacyChannels()

    /**
     * MANAGE_CHANNELS, the content's form (a plain edition needs a name, non-empty and <= 64 UTF-8
     * bytes; a sealed one its `{private, epoch, sealed}` shape), and on Roles v2 an unmarked edition
     * only for a Channel the switch listed (CORD-04 §2).
     */
    private fun channelGate(edition: ControlEdition): Boolean =
        authority.admits(edition, ConcordPermissions.MANAGE_CHANNELS) &&
            (legacyChannels == null || edition.isMarked || edition.entityIdHex in legacyChannels) &&
            channelReadable(edition)

    private fun channelReadable(edition: ControlEdition): Boolean {
        if (ConcordChannelSeal.readSealed(edition.content, edition.prevHash?.toHexKey()) != null) return true
        return ConcordJson.decodeOrNull<ChannelEntity>(edition.content)?.hasValidName() == true
    }

    /** The Channels any gated edition ever deleted: deletion is terminal (CORD-03 §2). */
    fun everDeletedChannels(): Set<String> =
        (byKind[ControlEntityKind.CHANNEL] ?: emptyList())
            .filter { channelGate(it) && ConcordJson.decodeOrNull<ChannelEntity>(it.content)?.deleted == true }
            .mapTo(HashSet()) { it.entityIdHex }

    // ---- Overwrites (CORD-04 §8) --------------------------------------------------------------

    private val everyoneId: ByteArray by lazy { ConcordKeyDerivation.everyoneCoordinate(communityId) }

    /** Each edition's overwrite when it sits at the coordinate its own scope and target derive: parsed and derived once. */
    private val overwriteMemo = HashMap<String, ChannelOverwrite?>()

    private fun overwriteOf(e: ControlEdition): ChannelOverwrite? {
        val key = e.rumorId + e.hashHex
        if (overwriteMemo.containsKey(key)) return overwriteMemo[key]
        val o =
            RankedRosterContent.parseOverwrite(e.content)?.takeIf { o ->
                val target = if (o.targetKind == ChannelOverwrite.EVERYONE) everyoneId else o.targetId!!.hexToByteArray()
                ConcordKeyDerivation.aclCoordinate(communityId, o.scope.hexToByteArray(), o.targetKind, target).toHexKey() == e.entityIdHex
            }
        overwriteMemo[key] = o
        return o
    }

    /** The rank an overwrite acts at (CORD-04 §8), or null when nobody may write it (a missing Role, the owner, a banned member). */
    private fun targetRank(o: ChannelOverwrite): Long? {
        val r = roster ?: return null
        return when (o.targetKind) {
            ChannelOverwrite.EVERYONE -> RankedRoster.BENEATH_EVERY_ROLE
            ChannelOverwrite.ROLE -> r.role(o.targetId!!)?.position
            else -> if (authority.isOwner(o.targetId!!) || authority.isBanned(o.targetId)) null else r.highestPosition(o.targetId) ?: RankedRoster.BENEATH_EVERY_ROLE
        }
    }

    /** MANAGE_ROLES held above the target, and every bit the overwrite touches (Armada `overwriteAuthorized`). */
    private fun overwriteGate(e: ControlEdition): Boolean {
        val r = roster ?: return false
        val o = overwriteOf(e) ?: return false
        val rank = targetRank(o) ?: return false
        if (authority.isOwner(e.author)) return true
        if (authority.isBanned(e.author) && !authority.isTenureListed(e.author, e.hashHex)) return false
        val support = r.permissionsAbove(e.author, rank) union r.sealOf(e.author, e.hashHex, rank)
        return support.has(ConcordPermissions.MANAGE_ROLES) && ((o.allow union o.deny) minus support).isEmpty()
    }

    private val overwriteList: List<ChannelOverwrite> by lazy {
        if (roster == null) {
            emptyList()
        } else {
            heads(ControlEntityKind.ACL)
                .values
                .mapNotNull { overwriteOf(it) }
                .filter { !(it.allow.isEmpty() && it.deny.isEmpty()) }
                .sortedWith(compareBy<ChannelOverwrite> { it.scope }.thenBy { it.targetKind }.thenBy { it.targetId ?: "" })
        }
    }

    /** Every standing overwrite; empty on the legacy Roster. */
    fun overwrites(): List<ChannelOverwrite> = overwriteList

    private val overwritesByScope: Map<String, List<ChannelOverwrite>> by lazy { overwriteList.groupBy { it.scope } }

    // ---- Syncs (CORD-04 §8) -------------------------------------------------------------------

    private val syncMemo = HashMap<String, Pair<String, String?>?>()

    /** A sync's `(channel, category)` when it sits at the coordinate its own channel derives. */
    private fun syncOf(e: ControlEdition): Pair<String, String?>? {
        val key = e.rumorId + e.hashHex
        if (syncMemo.containsKey(key)) return syncMemo[key]
        val sync =
            RankedRosterContent.parseSync(e.content)?.takeIf {
                ConcordKeyDerivation.syncCoordinate(communityId, it.first.hexToByteArray()).toHexKey() == e.entityIdHex
            }
        syncMemo[key] = sync
        return sync
    }

    /** The served syncs, by entity: where a sync's predecessor is found. */
    private val servedSyncs: Map<String, List<ControlEdition>> by lazy {
        served.filter { it.entityKind == ControlEntityKind.SYNC && syncOf(it) != null }.groupBy { it.entityIdHex }
    }

    /**
     * A sync swaps every rule on both sides at once (CORD-04 §8), so its signer must be able to write
     * every overwrite in the Channel's scope, the Category's it syncs to and the one it leaves, named
     * by its predecessor, which must be served; MANAGE_ROLES held through a Role even where no
     * overwrite stands; a banned signer's refused unless a Tenure lists it (Armada `syncOk`).
     */
    private fun syncGate(e: ControlEdition): Boolean {
        val sync = syncOf(e) ?: return false
        val author = e.author.lowercase()
        val owner = authority.isOwner(author)
        if (!owner && authority.isBanned(author) && !authority.isTenureListed(author, e.hashHex)) return false
        var left: String? = null
        val prevHex = e.prevHash?.toHexKey()
        if (prevHex != null) {
            val before = servedSyncs[e.entityIdHex]?.firstOrNull { it.hashHex == prevHex } ?: return false
            left = syncOf(before)?.second
        }
        if (owner) return true
        return maySwapOverwrites(author, e.hashHex, setOfNotNull(sync.first, sync.second, left))
    }

    /** Whether [author] could write every overwrite standing in [scopes] (Armada `maySwapOverwrites`). */
    private fun maySwapOverwrites(
        author: String,
        hashHex: String,
        scopes: Set<String>,
    ): Boolean {
        val r = roster ?: return false

        fun held(rank: Long) = r.permissionsAbove(author, rank) union r.sealOf(author, hashHex, rank)
        if (!held(RankedRoster.BENEATH_EVERY_ROLE).has(ConcordPermissions.MANAGE_ROLES)) return false
        for (scope in scopes) {
            for (o in overwritesByScope[scope] ?: continue) {
                val rank =
                    when (o.targetKind) {
                        ChannelOverwrite.EVERYONE -> RankedRoster.BENEATH_EVERY_ROLE
                        ChannelOverwrite.ROLE -> r.role(o.targetId!!)?.position ?: RankedRoster.BENEATH_EVERY_ROLE
                        else -> r.highestPosition(o.targetId!!) ?: RankedRoster.BENEATH_EVERY_ROLE
                    }
                val support = held(rank)
                if (!support.has(ConcordPermissions.MANAGE_ROLES) || !((o.allow union o.deny) minus support).isEmpty()) return false
            }
        }
        return true
    }

    private val syncMap: Map<HexKey, HexKey> by lazy {
        if (roster == null) return@lazy emptyMap()
        val out = HashMap<HexKey, HexKey>()
        for (head in heads(ControlEntityKind.SYNC).values) {
            val (channel, category) = syncOf(head) ?: continue
            if (category != null) out[channel] = category
        }
        out
    }

    /** Channel id → the Category whose overwrites it takes; empty on the legacy Roster. */
    fun syncs(): Map<HexKey, HexKey> = syncMap

    // ---- Writers ledgers (CORD-03 §1) ---------------------------------------------------------

    /** Point checks shared by every ledger parse of this fold: a ledger names the same writers in every version. */
    private val pointChecks = HashMap<String, Boolean>()

    /** Each ledger content parsed once per coordinate (null: malformed, or another Channel's). */
    private val ledgerMemo = HashMap<String, WritersLedgerDoc?>()

    private fun ledgerOf(e: ControlEdition): WritersLedgerDoc? {
        val key = e.entityIdHex + e.content
        if (ledgerMemo.containsKey(key)) return ledgerMemo[key]
        val doc = WritersLedgerDoc.parse(e.content, pointChecks)?.takeIf { it.isAt(communityId, e.entityIdHex) }
        ledgerMemo[key] = doc
        return doc
    }

    /** The served ledger editions by entity, the first served with each hash: where a predecessor is found. */
    private val servedLedgers: Map<String, Map<String, ControlEdition>> by lazy {
        val out = HashMap<String, LinkedHashMap<String, ControlEdition>>()
        for (e in served) {
            if (e.entityKind != ControlEntityKind.WRITERS) continue
            val byHash = out.getOrPut(e.entityIdHex) { LinkedHashMap() }
            if (e.hashHex !in byHash) byHash[e.hashHex] = e
        }
        out
    }

    /** Each ledger edition's verdict, judged once along its chain. */
    private val ledgerVerdicts = HashMap<String, Boolean>()

    /**
     * Whether a Writers ledger edition stands (CORD-03 §1, Armada's ledger gate): MANAGE_CHANNELS
     * citing its Grant; its newest entry within ten minutes of its own time and at most an hour past
     * the reader's clock; a writer entry naming its signer as `by`; a first edition at version 1
     * holding one entry and no `retired`; and every later one appending exactly one entry, verbatim,
     * to a predecessor that itself stands. A predecessor nobody serves (a Refounding's compaction) is
     * no evidence either way, so the edition is judged on itself.
     *
     * Judged iteratively from the oldest unjudged predecessor up, so a long chain never recurses deep.
     */
    private fun ledgerGate(e: ControlEdition): Boolean {
        ledgerVerdicts[verdictKey(e)]?.let { return it }
        val byHash = servedLedgers[e.entityIdHex] ?: emptyMap()
        val chain = ArrayList<ControlEdition>()
        val visiting = HashSet<String>()
        var cur: ControlEdition? = e
        while (cur != null && verdictKey(cur) !in ledgerVerdicts && visiting.add(verdictKey(cur))) {
            chain.add(cur)
            cur = cur.prevHash?.toHexKey()?.let { byHash[it] }
        }
        for (i in chain.indices.reversed()) {
            val x = chain[i]
            ledgerVerdicts[verdictKey(x)] = judgeLedger(x, byHash)
        }
        return ledgerVerdicts[verdictKey(e)] ?: false
    }

    private fun verdictKey(e: ControlEdition) = e.rumorId + e.hashHex

    private fun judgeLedger(
        e: ControlEdition,
        byHash: Map<String, ControlEdition>,
    ): Boolean {
        val doc = ledgerOf(e) ?: return false
        if (!authority.admits(e, ConcordPermissions.MANAGE_CHANNELS)) return false
        val last = doc.entries.lastOrNull() ?: return false
        if (abs(last.at - e.createdAt * 1000) > LEDGER_ENTRY_SKEW_MS) return false
        if (last.at > nowMillis + LEDGER_HORIZON_MS) return false
        val spell = last.spell
        if (spell is WritersLedgerDoc.Spell.Writer && spell.by != e.author.lowercase()) return false
        val prevHex = e.prevHash?.toHexKey() ?: return e.version == 1L && doc.entries.size == 1 && doc.retired.isEmpty()
        val prev = byHash[prevHex] ?: return true
        val before = ledgerOf(prev) ?: return false
        // A predecessor on this chain not yet judged is a cycle: it stands on nothing.
        return ledgerVerdicts[verdictKey(prev)] == true && doc.extends(before)
    }

    private val writerMap: Map<HexKey, ConcordWritersLedger> by lazy {
        if (roster == null) return@lazy emptyMap()
        val out = HashMap<HexKey, ConcordWritersLedger>()
        for (head in heads(ControlEntityKind.WRITERS).values) {
            val ledger = ledgerOf(head)?.toLedger() ?: continue
            out[ledger.channelIdHex] = ledger
        }
        out
    }

    /** Each restricted Channel's folded Writers ledger, by channel id; empty on the legacy Roster. */
    fun writers(): Map<HexKey, ConcordWritersLedger> = writerMap

    companion object {
        /** The Community Signals sub-kind (CORD-04 §8, upstream PR #17), carried but not modeled here. */
        private const val VSK_SIGNALS = "12"

        /** How far a Writers ledger entry may sit from its edition's own time (CORD-03 §1). */
        const val LEDGER_ENTRY_SKEW_MS = 10 * 60_000L

        /** CORD-02 §5's horizon: a ledger entry dated past it is refused. */
        const val LEDGER_HORIZON_MS = 60 * 60_000L
    }
}
