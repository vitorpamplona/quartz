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

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legacy Roster's Banlist delta rule (an edition adds or removes only members its signer
 * strictly outranks) against editions whose chain the author picked: a version-0 genesis of
 * their own, a fork that outruns the honest chain, a senior's dead-end fork. Each must leave the
 * senior's bans standing (CORD-04 §1, §3).
 */
class BanlistChainTest {
    private val owner = "a0".repeat(32)
    private val dave = "d4".repeat(32) // Admin, position 2: only the owner may ban or unban him
    private val bob = "b2".repeat(32) // Mod, position 5, BAN
    private val eve = "e5".repeat(32) // Senior mod, position 3, BAN
    private val carol = "c3".repeat(32) // plain member
    private val frank = "f6".repeat(32) // plain member

    private val adminRole = "11".repeat(32)
    private val modRole = "22".repeat(32)
    private val seniorRole = "33".repeat(32)
    private val banEid = ControlFixtures.banlistEid().hexToByteArray()

    private fun edition(
        kind: ControlEntityKind,
        eid: ByteArray,
        version: Long,
        prev: ByteArray?,
        content: String,
        author: String,
        rumorId: String,
    ) = ControlEdition(kind, eid, version, prev, null, content, author, rumorId, 0L)

    private fun ban(
        version: Long,
        prev: ControlEdition?,
        author: String,
        rumorId: String,
        vararg members: String,
    ) = edition(ControlEntityKind.BANLIST, banEid, version, prev?.hash, members.joinToString(",", "[", "]") { "\"$it\"" }, author, rumorId)

    private fun grant(
        member: String,
        role: String,
    ) = edition(ControlEntityKind.GRANT, ControlFixtures.grantEid(member).hexToByteArray(), 1, null, """{"member":"$member","role_ids":["$role"]}""", owner, "g-$member")

    private val roster =
        listOf(
            edition(ControlEntityKind.ROLE, adminRole.hexToByteArray(), 1, null, """{"name":"Admin","position":2,"permissions":"95"}""", owner, "r1"),
            edition(ControlEntityKind.ROLE, modRole.hexToByteArray(), 1, null, """{"name":"Mod","position":5,"permissions":"24"}""", owner, "r2"),
            edition(ControlEntityKind.ROLE, seniorRole.hexToByteArray(), 1, null, """{"name":"Senior","position":3,"permissions":"24"}""", owner, "r3"),
            grant(dave, adminRole),
            grant(bob, modRole),
            grant(eve, seniorRole),
        )

    private fun banned(vararg banlist: ControlEdition) = ControlFixtures.resolve(roster + banlist, owner)

    @Test
    fun aJuniorChainingOnTheOwnersListLiftsOnlyWhomTheyOutrank() {
        val o1 = ban(1, null, owner, "o1", dave, carol)
        val r = banned(o1, ban(2, o1, bob, "b2"))
        assertTrue(r.isBanned(dave), "bob cannot unban an admin")
        assertFalse(r.isBanned(carol), "bob may unban a plain member")
    }

    @Test
    fun aVersionZeroGenesisOfTheirOwnDoesNotUnanchorTheOwnersList() {
        val o1 = ban(1, null, owner, "o1", dave, carol)
        val x0 = ban(0, null, bob, "x0")
        val x1 = ban(1, x0, bob, "x1")
        val x2 = ban(2, x1, bob, "x2")
        val r = banned(o1, x0, x1, x2)
        assertTrue(r.isBanned(dave), "a v0 genesis anchored the list on bob's own chain")
        assertTrue(r.isBanned(carol))
    }

    @Test
    fun aSeniorsDeadEndForkDoesNotStrandTheOwnersLaterEdition() {
        val o1 = ban(1, null, owner, "o1", carol)
        val b2 = ban(2, o1, bob, "b2", carol, frank)
        val o3 = ban(3, b2, owner, "o3", carol, frank, dave)
        val e2 = ban(2, o1, eve, "e2", carol)
        val r = banned(o1, b2, o3, e2)
        assertTrue(r.isBanned(dave), "eve's fork beat bob's v2 on rank and stranded the owner's v3")
        assertTrue(r.isBanned(frank))
    }

    @Test
    fun aJuniorsLongerForkFromBeforeABanCannotLiftItByOmission() {
        val o1 = ban(1, null, owner, "o1", carol)
        val b2 = ban(2, o1, bob, "b2", carol, frank)
        val o3 = ban(3, b2, owner, "o3", carol, frank, dave)
        // bob forks from before dave's ban and outruns the honest chain: the delta against his
        // own prev removes nothing, yet dave is gone from it.
        // Rumor ids are author-chosen: these sort before bob's honest b2, so a walk that picks one
        // sibling per version follows them.
        val x2 = ban(2, o1, bob, "a2", carol)
        val x3 = ban(3, x2, bob, "a3", carol)
        val x4 = ban(4, x3, bob, "a4", carol)
        val r = banned(o1, b2, o3, x2, x3, x4)
        assertTrue(r.isBanned(dave), "bob lifted the owner's ban of an admin by forking around it")
        assertTrue(r.isBanned(carol))
    }
}
