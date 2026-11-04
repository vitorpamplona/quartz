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

import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `din` repository as published: chad (`8618…`) announced it in July with Alex (`0461…`) in his
 * legacy `maintainers` list; on Nov 2 Team Soapbox (`781a…`) became the lead and recorded Alex as a
 * maintainer only from then. gitworkshop shows Alex's July "merged" status on chad's PR as "User is
 * not a maintainer — status change not applied", and these tests hold the history to that verdict.
 */
class GitRepositoryRoleHistoryTest {
    private val sig = "00".repeat(64)
    private val alex = "0461fcbecc4c3374439932d6b8f11269ccdb7cc973ad7a50ae362db135a474dd"
    private val soapbox = "781a1527055f74c1f70230f10384609b34548f8ab6a0a6caa74025827f9fdae5"
    private val chad = "86184109eae937d8d6f980b4a0b46da4ef0d983eade403ee1b4c0b6bde238b47"

    private val alexAnnouncement =
        GitRepositoryEvent(
            "573aab8572fbb42cbc143a4d3594a02e4b3bc86da3d3d67adb6f8840f71ea554",
            alex,
            1793647335,
            arrayOf(
                arrayOf("d", "din"),
                arrayOf("maintainers", alex, soapbox),
                arrayOf("m", alex),
                arrayOf("M", soapbox, "1793647289"),
                arrayOf("m", soapbox, "1793647060", "1793647289"),
                arrayOf("m", chad, "0", "1793647255", "1793647255", "defer"),
            ),
            "",
            sig,
        )

    private val soapboxAnnouncement =
        GitRepositoryEvent(
            "ef23a3f7bb6cc75aec878ce06d2d23276fc5105dded2abbb9d2d17da949d8085",
            soapbox,
            1793647255,
            arrayOf(
                arrayOf("d", "din"),
                arrayOf("maintainers", soapbox, alex, chad),
                arrayOf("M", soapbox, "1793647255"),
                arrayOf("m", soapbox, "1793647172", "1793647255"),
                arrayOf("m", alex, "1793647172"),
                arrayOf("m", chad, "0", "1793647255", "1793647255"),
            ),
            "",
            sig,
        )

    private val chadAnnouncement =
        GitRepositoryEvent(
            "7540f16c85a1bce5870aac78b03d4e53b52f5b88da2b7c814b50ff23abc50eec",
            chad,
            1784466593,
            arrayOf(arrayOf("d", "din"), arrayOf("maintainers", chad, alex)),
            "",
            sig,
        )

    private val latest = GitRepositoryMembership.latestByAuthor(listOf(alexAnnouncement, soapboxAnnouncement, chadAnnouncement), "din")

    private val julyMerge = 1784431506L
    private val now = 1793735694L

    @Test
    fun todayAllThreeAreMaintainers() {
        val membership = GitRepositoryMembership.resolveFromLead(alex, "din", latest)!!
        assertEquals(soapbox, membership.confirmedMaintainers.first())
        assertEquals(setOf(soapbox, alex, chad), membership.confirmedMaintainers.toSet())
        assertEquals(setOf(soapbox, alex, chad), membership.membersAt(now))

        // History alone reads today as ambiguous: chad's July legacy list (timeless) makes a second
        // repository of him alone, while Alex's record of chad now ends in `defer`. Today's events
        // are judged by today's membership instead (membersAt), so the current team still counts.
        assertTrue(membership.history.membersAt(now).isEmpty())
    }

    @Test
    fun alexWasNotAMaintainerWhenHeMarkedTheJulyPrMerged() {
        // Opened from any of the three coordinates, the lead's history decides.
        for (opened in listOf(alex, chad, soapbox)) {
            val membership = GitRepositoryMembership.resolveFromLead(opened, "din", latest)!!
            assertFalse(alex in membership.membersAt(julyMerge), "opened from $opened")
        }
    }

    @Test
    fun theLeadsRecordWinsOverACoMaintainersCopy() {
        // From Alex's own coordinate, without moving to the lead, his untimed self-record would
        // make him a maintainer in July: the reason the view is anchored at the lead.
        val fromAlex = GitRepositoryMembership.resolve(alex, "din", latest)!!
        assertTrue(alex in fromAlex.membersAt(julyMerge))
    }

    @Test
    fun roleStateFollowsTheBoundaries() {
        fun state(vararg boundaries: Long?) = GitRepositoryRoleHistory.stateAt(RepositoryRoleRecord(RepositoryRole.MAINTAINER, alex, boundaries.toList()), 100)
        assertEquals(RoleState.ACTIVE, state())
        assertEquals(RoleState.ACTIVE, state(50))
        assertEquals(RoleState.INACTIVE, state(150))
        assertEquals(RoleState.INACTIVE, state(10, 50))
        assertEquals(RoleState.ACTIVE, state(10, 50, 90))
        assertEquals(RoleState.UNKNOWN, state(10, 50, 90, null))
        assertEquals(RoleState.ACTIVE, state(10, 150, 160, null))
    }
}
