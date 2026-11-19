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

import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRoster
import com.vitorpamplona.quartz.concord.cord06Rekey.ConcordRefounding
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial Roles v2 planes (CORD-04 §2–§9, CORD-03 §1): editions some holder of the bit is able
 * to sign but no reader may honor, and honest ones a looser gate would drop. Each must come out of
 * [ConcordCommunityState.fold] and [ConcordCommunityState.authorizedHeads] the same way, so a floor
 * or a Refounding's compaction never remembers what the fold refused.
 */
class RolesV2AdversarialTest {
    private val cid = ByteArray(32) { 0x42 }
    private val binding = ConcordKeyDerivation.editionBinding(cid)

    private val owner = key()
    private val admin = key()
    private val mod = key()
    private val member = key()

    private val adminRole = "a1".repeat(32)
    private val modRole = "b2".repeat(32)
    private val vipRole = "c3".repeat(32)
    private val channel = "d4".repeat(32)
    private val category = "e5".repeat(32)

    private val t0 = 1_700_000_000L
    private var counter = 0

    private fun key() = KeyPair().pubKey.toHexKey()

    private fun bits(vararg b: Int) = ConcordPermissions.of(*b).toWire()

    private fun edition(
        kind: ControlEntityKind,
        eid: String,
        version: Long,
        prev: ControlEdition?,
        content: String,
        author: String,
        createdAt: Long = t0,
    ) = ControlEdition(
        entityKind = kind,
        entityId = eid.hexToByteArray(),
        version = version,
        prevHash = prev?.hash,
        authorityCitation = null,
        content = content,
        author = author,
        rumorId = (++counter).toString().padStart(64, '0'),
        createdAt = createdAt,
        vsk = kind.wire,
        binding = binding,
    )

    private fun role(
        id: String,
        name: String,
        rank: String,
        perms: String,
        author: String = owner,
        version: Long = 1,
        prev: ControlEdition? = null,
    ) = edition(ControlEntityKind.RANKED_ROLE, id, version, prev, """{"role_id":"$id","name":"$name","rank":"$rank","permissions":"$perms"}""", author)

    private fun grant(
        to: String,
        vararg roles: String,
        author: String = owner,
    ) = edition(
        ControlEntityKind.GRANT,
        ConcordKeyDerivation.grantCoordinate(cid, to.hexToByteArray()).toHexKey(),
        1,
        null,
        """{"member":"$to","role_ids":[${roles.joinToString(",") { "\"$it\"" }}]}""",
        author,
    )

    private fun overwrite(
        scope: String,
        kind: String,
        target: String?,
        allow: String,
        deny: String,
        author: String = owner,
    ): ControlEdition {
        val targetBytes = if (target == null) ConcordKeyDerivation.everyoneCoordinate(cid) else target.hexToByteArray()
        val eid = ConcordKeyDerivation.aclCoordinate(cid, scope.hexToByteArray(), kind, targetBytes).toHexKey()
        val t = if (target == null) """{"kind":"everyone"}""" else """{"kind":"$kind","id":"$target"}"""
        return edition(ControlEntityKind.ACL, eid, 1, null, """{"scope":"$scope","target":$t,"allow":"$allow","deny":"$deny"}""", author)
    }

    private fun sync(
        category: String?,
        author: String,
        version: Long = 1,
        prev: ControlEdition? = null,
    ): ControlEdition {
        val eid = ConcordKeyDerivation.syncCoordinate(cid, channel.hexToByteArray()).toHexKey()
        val body = if (category == null) """{"channel":"$channel"}""" else """{"channel":"$channel","sync":"$category"}"""
        return edition(ControlEntityKind.SYNC, eid, version, prev, body, author)
    }

    /** The owner's switch, an Admin above a Mod above a VIP, each granted. */
    private val base: List<ControlEdition> by lazy {
        listOf(
            edition(ControlEntityKind.RANKED_ROLE, ConcordKeyDerivation.everyoneCoordinate(cid).toHexKey(), 1, null, """{"permissions":"${bits(ConcordPermissions.VIEW_CHANNEL)}"}""", owner),
            role(adminRole, "Admin", "B", bits(ConcordPermissions.MANAGE_ROLES, ConcordPermissions.MANAGE_CHANNELS, ConcordPermissions.BAN, ConcordPermissions.VIEW_CHANNEL, ConcordPermissions.SEND_MESSAGES)),
            role(modRole, "Mod", "M", bits(ConcordPermissions.MANAGE_ROLES, ConcordPermissions.MANAGE_CHANNELS)),
            role(vipRole, "VIP", "Z", "0"),
            grant(admin, adminRole),
            grant(mod, modRole),
        )
    }

    private fun cite(
        e: ControlEdition,
        on: List<ControlEdition> = base,
    ): ControlEdition = e.withCitation(AuthorityResolver.resolve(on, cid, owner).citationFor(e.author))

    private fun fold(
        editions: List<ControlEdition>,
        now: Long = t0 * 1000,
    ) = ConcordCommunityState.fold(editions, cid, owner, emptyMap(), now, null)

    private fun heads(editions: List<ControlEdition>) = ConcordCommunityState.authorizedHeads(editions, cid, owner)

    @Test
    fun theBaseIsOnRolesV2() {
        val state = fold(base)
        assertTrue(state.isRolesV2)
        assertEquals(setOf(adminRole, modRole, vipRole), state.roles.keys)
    }

    // ---- authorizedHeads judges the Roster as the fold does -----------------------------------

    @Test
    fun aModsDeletionOfTheAdminRoleIsNeverAHeadNorCompacted() {
        val v1 = base.first { it.entityIdHex == adminRole }
        val forged = cite(edition(ControlEntityKind.RANKED_ROLE, adminRole, 2, v1, """{"role_id":"$adminRole","deleted":true}""", mod))
        val plane = base + forged
        assertTrue(adminRole in fold(plane).roles, "the live fold refuses it")
        assertEquals(1L, heads(plane)[adminRole]?.version, "and so does the floor")
        assertEquals(1L, ConcordRefounding.headVersions(plane, cid, owner)[adminRole], "and the compaction")
    }

    @Test
    fun aModsRewriteOfTheirOwnRoleAboveTheAdminIsNeverAHead() {
        val v1 = base.first { it.entityIdHex == modRole }
        val forged = cite(role(modRole, "Mod", "A", bits(ConcordPermissions.MANAGE_ROLES, ConcordPermissions.MANAGE_CHANNELS), author = mod, version = 2, prev = v1))
        val plane = base + forged
        assertEquals(1L, heads(plane)[modRole]?.version)
    }

    @Test
    fun anUncitedGrantAndOverwriteThatStandAreKept() {
        // A Grant and an overwrite MAY omit `vac` (CORD-04 §5): judged on rank alone.
        val g = grant(member, vipRole, author = admin)
        val o = overwrite(channel, "everyone", null, "0", bits(ConcordPermissions.SEND_MESSAGES), author = admin)
        val plane = base + g + o
        val state = fold(plane)
        assertEquals(listOf(vipRole), state.authority.rolesOf(member).toList())
        assertEquals(1, state.overwrites.size)
        val h = heads(plane)
        assertNotNull(h[g.entityIdHex], "the uncited Grant is a floor")
        assertNotNull(h[o.entityIdHex], "the uncited overwrite is a floor")
    }

    @Test
    fun anOverwriteTouchingBitsItsSignerLacksIsNoHead() {
        // The Mod holds no SEND_MESSAGES, so may not write an overwrite touching it.
        val o = overwrite(channel, "everyone", null, "0", bits(ConcordPermissions.SEND_MESSAGES), author = mod)
        val plane = base + o
        assertTrue(fold(plane).overwrites.isEmpty())
        assertNull(heads(plane)[o.entityIdHex])
    }

    @Test
    fun aTenureNeverSealsItsOwnSigner() {
        val eid = ConcordKeyDerivation.tenureCoordinate(cid, admin.hexToByteArray(), admin.hexToByteArray()).toHexKey()
        val self =
            edition(ControlEntityKind.TENURE, eid, 1, null, """{"member":"$admin","sealed":[{"rank":"B","permissions":"${bits(ConcordPermissions.BAN)}","heads":[]}]}""", admin)
        val plane = base + self
        assertNull(fold(plane).authority.rankedRoster()!!.sealed[admin])
        assertNull(heads(plane)[eid])
    }

    @Test
    fun anOwnerEditionBuiltOnARefusedOneStands() {
        val v1 = base.first { it.entityIdHex == vipRole }
        // The Mod hands the VIP Role a bit the Mod lacks: refused.
        val refused = cite(role(vipRole, "VIP", "Z", bits(ConcordPermissions.BAN), author = mod, version = 2, prev = v1))
        val healed = role(vipRole, "VIP", "Y", "0", author = owner, version = 3, prev = refused)
        val state = fold(base + refused + healed)
        val vip = state.authority.rankedRoster()!!.role(vipRole)!!
        assertEquals("Y", vip.rank)
        assertEquals("0", vip.permissions.toWire())
        assertEquals(3L, heads(base + refused + healed)[vipRole]?.version)
    }

    // ---- syncs (CORD-04 §8) -------------------------------------------------------------------

    private val categoryOpen by lazy { overwrite(category, "everyone", null, bits(ConcordPermissions.VIEW_CHANNEL), "0") }

    @Test
    fun aModWhoCannotWriteTheCategorysRulesCannotSyncToThem() {
        val s = sync(category, mod)
        val plane = base + categoryOpen + s
        assertNull(fold(plane).syncs[channel], "MANAGE_ROLES alone does not swap a VIEW_CHANNEL rule")
        assertNull(heads(plane)[s.entityIdHex])
        // The owner, and an Admin who holds every bit the rules touch, may.
        assertEquals(category, fold(base + categoryOpen + sync(category, owner)).syncs[channel])
        assertEquals(category, fold(base + categoryOpen + sync(category, admin)).syncs[channel])
    }

    @Test
    fun anUnsyncIsJudgedOnTheCategoryItLeaves() {
        val synced = sync(category, owner)
        val leave = sync(null, mod, version = 2, prev = synced)
        val plane = base + categoryOpen + synced + leave
        assertEquals(category, fold(plane).syncs[channel], "the Mod may not shed the Category's VIEW rule")
        assertEquals(1L, heads(plane)[synced.entityIdHex]?.version)
        // The Admin may.
        val adminLeave = sync(null, admin, version = 2, prev = synced)
        assertNull(fold(base + categoryOpen + synced + adminLeave).syncs[channel])
    }

    @Test
    fun aSyncWhosePredecessorIsNotServedIsRefused() {
        val unserved = sync(category, owner)
        val orphan = sync(category, owner, version = 2, prev = unserved)
        assertNull(fold(base + categoryOpen + orphan).syncs[channel])
    }

    // ---- Writers ledgers (CORD-03 §1) ---------------------------------------------------------

    private val ledgerEid = ConcordKeyDerivation.writersCoordinate(cid, channel.hexToByteArray()).toHexKey()
    private val writer1 = key()
    private val writer2 = key()

    private fun ledger(
        entries: String,
        author: String,
        version: Long,
        prev: ControlEdition?,
        createdAt: Long = t0,
        retired: String? = null,
    ): ControlEdition {
        val r = if (retired == null) "" else ""","retired":$retired"""
        return cite(edition(ControlEntityKind.WRITERS, ledgerEid, version, prev, """{"channel":"$channel","ledger":[$entries]$r}""", author, createdAt))
    }

    private val at0 = t0 * 1000
    private val e1 get() = """{"at":$at0,"epoch":1,"writer":"$writer1","root":0,"by":"$admin"}"""
    private val stop get() = """{"at":${at0 + 1000},"stop":"$member"}"""
    private val e2ByAdmin get() = """{"at":${at0 + 2000},"epoch":2,"writer":"$writer2","root":0,"by":"$admin"}"""

    private val v1 by lazy { ledger(e1, admin, 1, null) }
    private val v2 by lazy { ledger("$e1,$stop", admin, 2, v1) }

    @Test
    fun anHonestLedgerChainFolds() {
        val l = fold(base + v1 + v2).writers[channel]
        assertNotNull(l)
        assertEquals(1, l.stops.size)
        assertTrue(l.isRestricted)
    }

    @Test
    fun aLedgerThatDropsAStopIsRefused() {
        // The Mod holds MANAGE_CHANNELS, and rewrites history instead of appending.
        val e2ByMod = """{"at":${at0 + 2000},"epoch":2,"writer":"$writer2","root":0,"by":"$mod"}"""
        val forged = ledger("$e1,$e2ByMod", mod, 3, v2)
        val plane = base + v1 + v2 + forged
        assertEquals(1, fold(plane).writers[channel]!!.stops.size)
        assertEquals(2L, heads(plane)[ledgerEid]?.version)
    }

    @Test
    fun aLedgerEntryFarFromItsEditionsTimeIsRefused() {
        // Its new entry sits an hour before the edition's own time: backdated into a window.
        val backdated = ledger("$e1,$stop,$e2ByAdmin", admin, 3, v2, createdAt = t0 + 3600)
        assertEquals(1, fold(base + v1 + v2 + backdated, now = at0 + 7_200_000).writers[channel]!!.windows.size)
        assertEquals(2, fold(base + v1 + v2 + ledger("$e1,$stop,$e2ByAdmin", admin, 3, v2)).writers[channel]!!.windows.size)
    }

    @Test
    fun aLedgerEntryAnHourPastTheReadersClockIsRefused() {
        val later = at0 + 7_200_000
        val e2Later = """{"at":$later,"epoch":2,"writer":"$writer2","root":0,"by":"$admin"}"""
        val ahead = ledger("$e1,$stop,$e2Later", admin, 3, v2, createdAt = later / 1000)
        assertEquals(2, fold(base + v1 + v2 + ahead, now = later).writers[channel]!!.windows.size)
        assertEquals(1, fold(base + v1 + v2 + ahead, now = at0 + 1000).writers[channel]!!.windows.size)
    }

    @Test
    fun aWriterEntryNamesItsSigner() {
        val wrongBy = ledger("$e1,$stop,$e2ByAdmin", mod, 3, v2)
        assertEquals(1, fold(base + v1 + v2 + wrongBy).writers[channel]!!.windows.size)
    }

    @Test
    fun aFirstLedgerHoldsOneEntryAtVersionOne() {
        assertNull(fold(base + ledger("$e1,$stop", admin, 1, null)).writers[channel])
        assertNull(fold(base + ledger(e1, admin, 2, null)).writers[channel])
    }

    @Test
    fun aLedgerOnARefusedPredecessorIsRefused() {
        // v2's stop is dated two hours before v2 itself: refused, and so is the v3 built on it.
        val lateV2 = ledger("$e1,$stop", admin, 2, v1, createdAt = t0 + 7200)
        val v3 = ledger("$e1,$stop,$e2ByAdmin", admin, 3, lateV2)
        val l = fold(base + v1 + lateV2 + v3).writers[channel]!!
        assertEquals(1, l.windows.size, "v3 does not stand on a refused v2")
        assertTrue(l.stops.isEmpty())
    }

    @Test
    fun aStopAppliesOnlyToTheWindowItFollows() {
        // Two windows meet at one millisecond, a stop at that millisecond written inside the first.
        val content = """{"channel":"$channel","ledger":[$e1,{"at":${at0 + 2000},"stop":"$member"},{"at":${at0 + 2000},"epoch":2,"writer":"$writer2","root":0,"by":"$admin"}]}"""
        val ledger = ConcordWritersLedger.parse(content, cid, ledgerEid)
        assertNotNull(ledger)
        val (first, second) = ledger.windows
        assertTrue(ledger.isStopped(first, member, at0 + 2000))
        assertFalse(ledger.isStopped(second, member, at0 + 3000))
    }

    // ---- staff, marking ------------------------------------------------------------------------

    @Test
    fun everyMemberIsStaffWhenEveryoneCarriesAStaffBit() {
        val everyone = base.first()
        val staffEveryone = edition(ControlEntityKind.RANKED_ROLE, everyone.entityIdHex, 2, everyone, """{"permissions":"${bits(ConcordPermissions.VIEW_CHANNEL, ConcordPermissions.CREATE_INVITE)}"}""", owner)
        val authority = fold(base + staffEveryone).authority
        assertTrue(authority.isStaff(member))
        assertFalse(member in authority.staffMembers(), "the roster alone cannot know the members")
        assertTrue(member in authority.staffMembers(listOf(member)))
        assertFalse(member in fold(base).authority.staffMembers(listOf(member)))
    }

    @Test
    fun aRosterFoldIgnoresEditionsBoundToAnotherCommunity() {
        val other = ConcordKeyDerivation.editionBinding(ByteArray(32) { 0x01 })
        val replayed =
            base.map {
                ControlEdition(it.entityKind, it.entityId, it.version, it.prevHash, it.authorityCitation, it.content, it.author, it.rumorId, it.createdAt, it.vsk, other)
            }
        assertFalse(RankedRoster.isActivated(replayed, cid, owner))
        assertNull(RankedRoster.fold(replayed, cid, owner).everyone)
    }

    @Test
    fun anUnmarkedEditionOfAHugeSubKindIsRefused() {
        val unmarked = { vsk: String -> ControlEdition(null, cid, 1, null, null, "{}", owner, "ff".repeat(32), t0, vsk, null) }
        assertFalse(unmarked("99999999999").isAdmissibleIn(binding))
        assertFalse(unmarked("13").isAdmissibleIn(binding))
        assertFalse(unmarked("100").isAdmissibleIn(binding))
        assertTrue(unmarked("12").isAdmissibleIn(binding))
        assertTrue(unmarked("1").isAdmissibleIn(binding))
    }
}
