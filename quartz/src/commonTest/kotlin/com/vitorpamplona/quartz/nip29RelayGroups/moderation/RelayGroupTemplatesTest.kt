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
package com.vitorpamplona.quartz.nip29RelayGroups.moderation

import com.vitorpamplona.quartz.buzz.workspace.BUZZ_ROLE_GUEST
import com.vitorpamplona.quartz.buzz.workspace.BUZZ_ROLE_MEMBER
import com.vitorpamplona.quartz.buzz.workspace.buzzRoleFor
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMembersEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The kind 9000 / 9002 shapes a client sends to NIP-29 and Buzz relays, and Buzz's 39000/39002 roles. Ported from Amethyst. */
class RelayGroupTemplatesTest {
    private val pk = "bb".repeat(32)
    private val gid = "11111111-1111-4111-8111-111111111111"

    private fun metadata(vararg extra: Array<String>): GroupMetadataEvent =
        EventFactory.create(
            "00".repeat(32),
            "aa".repeat(32),
            100,
            GroupMetadataEvent.KIND,
            arrayOf(arrayOf("d", gid), arrayOf("name", "general"), *extra),
            "",
            "22".repeat(64),
        ) as GroupMetadataEvent

    private fun buzzMetadata(): GroupMetadataEvent =
        metadata(
            arrayOf("t", "stream"),
            arrayOf("t", "workspace"),
            arrayOf("P", pk),
            arrayOf("created_at", "90"),
            arrayOf("join_role", "member"),
            arrayOf("public"),
        )

    private fun edit(
        isBuzz: Boolean,
        buzzLabels: List<String>? = null,
    ) = GroupEditMetadataEvent
        .buildEdit(
            gid,
            currentMetadata = buzzMetadata(),
            isBuzz = isBuzz,
            name = "renamed",
            about = null,
            picture = null,
            banner = null,
            status = emptySet(),
            // What an edit form pre-fills: the 39000's hashtags, which on Buzz are the type and labels.
            hashtags = buzzMetadata().hashtags(),
            geohashes = emptyList(),
            parent = null,
            children = emptyList(),
            buzzLabels = buzzLabels,
        ).tags
        .map { it.toList() }

    @Test
    fun buzzPutUserSendsTheRoleTagWithABarePTag() {
        // buzz d0dee1cb5 reads p[2] before `role`: "moderator" there is refused, and an edit's old
        // role first won over the new one.
        val tags = GroupPutUserEvent.buildRoleChange(gid, pk, listOf("member", "admin"), buzzRoleFor(listOf("member", "admin"))).tags.map { it.toList() }
        assertEquals(listOf(listOf("h", gid), listOf("p", pk), listOf("role", "admin")), tags)

        val moderator = GroupPutUserEvent.buildRoleChange(gid, pk, listOf("moderator"), buzzRoleFor(listOf("moderator"))).tags.map { it.toList() }
        assertEquals(listOf(listOf("h", gid), listOf("p", pk), listOf("role", "member")), moderator)
    }

    @Test
    fun nip29PutUserKeepsTheRolesInThePTag() {
        val tags = GroupPutUserEvent.buildRoleChange(gid, pk, listOf("moderator"), null).tags.map { it.toList() }
        assertEquals(listOf(listOf("h", gid), listOf("p", pk, "moderator")), tags)
    }

    @Test
    fun buzzEditNeverEchoesTheChannelTypeOrLabels() {
        val tags = edit(isBuzz = true)
        assertTrue(tags.none { it[0] == "t" }, "a Buzz rename must not send t tags: $tags")
        // Nor the relay-internal tags of its 39000.
        assertTrue(tags.none { it[0] == "P" || it[0] == "join_role" || it[0] == "created_at" }, "$tags")
        assertTrue(listOf("visibility", "open") in tags)
    }

    @Test
    fun buzzEditSendsLabelsOnlyWhenTheyChange() {
        assertEquals(listOf(listOf("t", "team:core")), edit(isBuzz = true, buzzLabels = listOf("team:core")).filter { it[0] == "t" })
        assertEquals(listOf(listOf("t", "")), edit(isBuzz = true, buzzLabels = emptyList()).filter { it[0] == "t" })
        assertFailsWith<IllegalArgumentException> { edit(isBuzz = true, buzzLabels = listOf("stream")) }
    }

    @Test
    fun nip29EditStillCarriesHashtagsAndUnmanagedTags() {
        val tags = edit(isBuzz = false)
        assertTrue(listOf("t", "stream") in tags && listOf("t", "workspace") in tags, "$tags")
        assertTrue(listOf("join_role", "member") in tags, "$tags")
    }

    @Test
    fun theJoinRoleReadsFailClosed() {
        // Absent: a relay from before announce channels, where joiners are members.
        assertEquals(BUZZ_ROLE_MEMBER, metadata().buzzJoinRole())
        assertEquals(BUZZ_ROLE_MEMBER, metadata(arrayOf("join_role", "member")).buzzJoinRole())
        assertEquals(BUZZ_ROLE_GUEST, metadata(arrayOf("join_role", "guest")).buzzJoinRole())
        // Anything else never widens who may write (ChannelJoinRole::parse_fail_closed).
        assertEquals(BUZZ_ROLE_GUEST, metadata(arrayOf("join_role", "observer")).buzzJoinRole())
        assertEquals(BUZZ_ROLE_GUEST, metadata(arrayOf("join_role", "")).buzzJoinRole())
    }

    @Test
    fun guestsAreTheMembersWhoseRoleIsGuest() {
        val members =
            EventFactory.create(
                "00".repeat(32),
                "aa".repeat(32),
                100,
                GroupMembersEvent.KIND,
                arrayOf(
                    arrayOf("d", gid),
                    arrayOf("p", "01".repeat(32), "", "guest"),
                    arrayOf("p", "02".repeat(32), "", "member"),
                    arrayOf("p", "03".repeat(32)),
                ),
                "",
                "22".repeat(64),
            ) as GroupMembersEvent
        assertEquals(setOf("01".repeat(32)), members.buzzGuests())
    }
}
