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

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitRepositoryMembershipTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)
    private val sig = "00".repeat(64)
    private val repoId = "din"

    private fun announcement(
        author: String,
        vararg roleTags: Array<String>,
        createdAt: Long = 100,
        id: String = author.take(2).repeat(32),
    ) = GitRepositoryEvent(id, author, createdAt, arrayOf(arrayOf("d", repoId), *roleTags), "", sig)

    private fun resolve(
        selected: String,
        vararg events: GitRepositoryEvent,
    ) = GitRepositoryMembership.resolve(selected, repoId, GitRepositoryMembership.latestByAuthor(events.toList(), repoId))!!

    @Test
    fun roleTagParsesBoundariesAndActivity() {
        assertEquals(RepositoryRoleRecord(RepositoryRole.LEAD, alice, emptyList()), RepositoryRoleTag.parse(arrayOf("M", alice)))
        assertTrue(RepositoryRoleTag.parse(arrayOf("m", bob, "10"))!!.isActive)
        assertFalse(RepositoryRoleTag.parse(arrayOf("m", bob, "10", "20"))!!.isActive)
        assertTrue(RepositoryRoleTag.parse(arrayOf("o", bob, "10", "defer"))!!.endsInDefer)
        // defer only as the final end; digits only; a real pubkey.
        assertNull(RepositoryRoleTag.parse(arrayOf("m", bob, "defer")))
        assertNull(RepositoryRoleTag.parse(arrayOf("m", bob, "10", "defer", "30")))
        assertNull(RepositoryRoleTag.parse(arrayOf("m", bob, "-5")))
        assertNull(RepositoryRoleTag.parse(arrayOf("m", "not-a-key")))
        assertNull(RepositoryRoleTag.parse(arrayOf("x", bob)))
    }

    @Test
    fun legacyListedMaintainerMustListBack() {
        // Alice lists Bob; Bob's own announcement lists nobody: an invitation only.
        val oneSided = resolve(alice, announcement(alice, arrayOf("maintainers", bob)), announcement(bob))
        assertEquals(listOf(alice), oneSided.confirmedMaintainers)
        assertEquals(listOf(bob), oneSided.invitedMaintainers)

        // Once Bob lists Alice back, both are confirmed and both coordinates count.
        val mutual = resolve(alice, announcement(alice, arrayOf("maintainers", bob)), announcement(bob, arrayOf("maintainers", alice)))
        assertEquals(listOf(alice, bob), mutual.confirmedMaintainers)
        assertTrue(mutual.invitedMaintainers.isEmpty())
        assertEquals(listOf(Address(30617, alice, repoId), Address(30617, bob, repoId)), mutual.confirmedCoordinates())
    }

    @Test
    fun anAnnouncementCannotClaimSomeoneElsesRepository() {
        // Dave copies the identifier and lists Alice; Alice never lists Dave. Seen from Dave's
        // announcement, Dave is the only maintainer: Alice did not accept.
        val view = resolve(dave, announcement(alice), announcement(dave, arrayOf("maintainers", alice)))
        assertEquals(listOf(dave), view.confirmedMaintainers)
        assertFalse(view.isMaintainer(alice))
    }

    @Test
    fun leadPathSeedsAtTheLeadWhoNamesThemselves() {
        val lead = announcement(alice, arrayOf("M", alice), arrayOf("m", bob), arrayOf("maintainers", bob))
        val follower = announcement(bob, arrayOf("M", alice), arrayOf("m", bob), arrayOf("maintainers", alice, bob))
        // Viewed from Bob's announcement the path forwards to Alice, who confirms Bob.
        val view = resolve(bob, lead, follower)
        assertEquals(setOf(alice, bob), view.confirmedMaintainers.toSet())
        assertEquals(alice, view.confirmedMaintainers.first())
    }

    @Test
    fun conflictingLeadsSeedNothing() {
        val view = resolve(alice, announcement(alice, arrayOf("M", bob), arrayOf("M", carol)), announcement(bob, arrayOf("M", bob)), announcement(carol, arrayOf("M", carol)))
        assertTrue(view.confirmedMaintainers.isEmpty())
    }

    @Test
    fun anAuthorWhoEndedTheirOwnRoleIsNotAMaintainer() {
        val lead = announcement(alice, arrayOf("M", alice), arrayOf("m", bob))
        val leaving = announcement(bob, arrayOf("m", bob, "10", "20"), arrayOf("m", alice))
        val view = resolve(alice, lead, leaving)
        assertEquals(listOf(alice), view.confirmedMaintainers)
        assertTrue(leaving.authorCannotMaintain())
        assertTrue(leaving.authorDeclinesMaintainership())
    }

    @Test
    fun moderatorIsConfirmedOnlyAfterAcknowledging() {
        val lead = announcement(alice, arrayOf("M", alice), arrayOf("o", carol))
        val unacknowledged = resolve(alice, lead, announcement(carol))
        assertTrue(unacknowledged.confirmedModerators.isEmpty())
        assertEquals(listOf(carol), unacknowledged.invitedModerators)

        val acknowledging = announcement(carol, arrayOf("o", carol), arrayOf("M", alice))
        val confirmed = resolve(alice, lead, acknowledging)
        assertEquals(listOf(carol), confirmed.confirmedModerators)
        assertTrue(confirmed.isMember(carol))
        assertFalse(confirmed.isMaintainer(carol))
    }

    @Test
    fun indexedRolesReplaceTheLegacyList() {
        // With role tags present, the maintainers tag is only a projection: an inactive m wins.
        val event = announcement(alice, arrayOf("m", bob, "10", "20"), arrayOf("maintainers", bob))
        assertEquals(listOf(alice), event.activeMaintainerAssignments())
        assertTrue(event.hasIndexedRoles())
    }

    @Test
    fun latestAnnouncementPerAuthorBreaksTiesOnTheLowerId() {
        val low = announcement(alice, createdAt = 100, id = "1".repeat(64))
        val high = announcement(alice, arrayOf("maintainers", bob), createdAt = 100, id = "f".repeat(64))
        val newer = announcement(alice, arrayOf("maintainers", carol), createdAt = 200, id = "9".repeat(64))
        assertEquals(low, GitRepositoryMembership.latestByAuthor(listOf(high, low), repoId)[alice])
        assertEquals(newer, GitRepositoryMembership.latestByAuthor(listOf(high, newer, low), repoId)[alice])
    }

    @Test
    fun unknownSelectedAnnouncementResolvesToNull() {
        assertNull(GitRepositoryMembership.resolve(alice, repoId, emptyMap()))
    }
}
