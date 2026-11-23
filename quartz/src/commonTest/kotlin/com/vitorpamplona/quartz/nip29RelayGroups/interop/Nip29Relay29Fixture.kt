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
package com.vitorpamplona.quartz.nip29RelayGroups.interop

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.CreateGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.DeleteGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupCreateInviteEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupDeleteEventEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupPutUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupRemoveUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupJoinRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupLeaveRequestEvent

/**
 * The fixture the NIP-29 relay29 vectors share with their generator (`quartz/tools/nip29-vector-gen`):
 * the same user keys, group id and relay key. [ourCases] is what Quartz's builders write for the
 * moderation and request events a client sends; the generator reads them back through relay29
 * (`ours-read-by-relay29.json`).
 */
object Nip29Relay29Fixture {
    private val SK =
        linkedMapOf(
            "owner" to "11".repeat(32),
            "alice" to "44".repeat(32),
            "bob" to "55".repeat(32),
            "carol" to "66".repeat(32),
        )
    val PK: Map<String, String> = SK.mapValues { KeyPair(it.value.hexToByteArray()).pubKey.toHexKey() }
    val owner = PK.getValue("owner")
    val alice = PK.getValue("alice")
    val bob = PK.getValue("bob")
    val carol = PK.getValue("carol")

    const val RELAY_SK = "9999999999999999999999999999999999999999999999999999999999999999"
    val relay = KeyPair(RELAY_SK.hexToByteArray()).pubKey.toHexKey()

    const val GROUP = "interop-pizza"

    /** 2026-01-01T00:00:00Z. */
    const val T0 = 1767225600L

    class Case(
        val name: String,
        val event: Event,
    )

    private fun <T : Event> sign(
        who: String,
        template: EventTemplate<T>,
    ): T = NostrSignerSync(KeyPair(SK.getValue(who).hexToByteArray())).sign(template)

    /** A relay-signed 39000 of a private, closed group: what an edit starts from. */
    fun privateClosedMetadata(): GroupMetadataEvent =
        NostrSignerSync(KeyPair(RELAY_SK.hexToByteArray())).sign(
            GroupMetadataEvent.build(
                GROUP,
                name = "Pizza Lovers",
                about = "a group for people who love pizza",
                picture = "https://pizza.example/p.png",
                status = setOf(GroupMetadataEvent.GroupStatus.PRIVATE, GroupMetadataEvent.GroupStatus.CLOSED),
                createdAt = T0,
            ),
        )

    private fun edit(
        status: Set<GroupMetadataEvent.GroupStatus>,
        name: String,
        at: Long,
    ) = GroupEditMetadataEvent.buildEdit(
        GROUP,
        currentMetadata = privateClosedMetadata(),
        isBuzz = false,
        name = name,
        about = "a group for people who love pizza",
        picture = "https://pizza.example/p.png",
        banner = null,
        status = status,
        hashtags = emptyList(),
        geohashes = emptyList(),
        parent = null,
        children = emptyList(),
        buzzLabels = null,
        createdAt = at,
    )

    fun ourCases(): List<Case> {
        val private = GroupMetadataEvent.GroupStatus.PRIVATE
        val closed = GroupMetadataEvent.GroupStatus.CLOSED
        return listOf(
            Case("create", sign("owner", CreateGroupEvent.build(GROUP, name = "Pizza Lovers", about = "a group for people who love pizza", createdAt = T0))),
            Case(
                "edit_new_private_closed",
                sign(
                    "owner",
                    GroupEditMetadataEvent.build(
                        GROUP,
                        name = "Pizza Lovers",
                        about = "a group for people who love pizza",
                        picture = "https://pizza.example/p.png",
                        status = setOf(private, closed),
                        createdAt = T0 + 1,
                    ),
                ),
            ),
            Case("edit_rename_keeps_private_closed", sign("owner", edit(setOf(private, closed), "Pizza Fans", T0 + 2))),
            Case("edit_to_public_open", sign("owner", edit(emptySet(), "Pizza Lovers", T0 + 3))),
            Case("edit_to_public_keeps_closed", sign("owner", edit(setOf(closed), "Pizza Lovers", T0 + 4))),
            Case("edit_to_open_keeps_private", sign("owner", edit(setOf(private), "Pizza Lovers", T0 + 5))),
            Case("put_user_plain", sign("owner", GroupPutUserEvent.build(GROUP, listOf(alice to emptyList()), createdAt = T0 + 10))),
            Case("put_user_moderator", sign("owner", GroupPutUserEvent.buildRoleChange(GROUP, bob, listOf("moderator"), buzzRole = null, createdAt = T0 + 11))),
            Case("put_user_two_roles", sign("owner", GroupPutUserEvent.build(GROUP, listOf(bob to listOf("admin", "moderator")), createdAt = T0 + 12))),
            Case("remove_user", sign("bob", GroupRemoveUserEvent.build(GROUP, listOf(carol), createdAt = T0 + 13))),
            Case("delete_event", sign("bob", GroupDeleteEventEvent.build(GROUP, listOf("ab".repeat(32)), createdAt = T0 + 14))),
            Case("delete_group", sign("owner", DeleteGroupEvent.build(GROUP, createdAt = T0 + 15))),
            Case("create_invite", sign("owner", GroupCreateInviteEvent.build(GROUP, "pizza-night", createdAt = T0 + 16))),
            Case("join_request", sign("alice", GroupJoinRequestEvent.build(GROUP, reason = "I like pizza", createdAt = T0 + 20))),
            Case("join_request_with_code", sign("carol", GroupJoinRequestEvent.build(GROUP, inviteCode = "pizza-night", createdAt = T0 + 21))),
            Case("leave_request", sign("alice", GroupLeaveRequestEvent.build(GROUP, createdAt = T0 + 22))),
        )
    }
}
