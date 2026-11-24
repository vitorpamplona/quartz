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
package com.vitorpamplona.quartz.buzz.vectors

import com.vitorpamplona.quartz.buzz.dm.BuzzDmRules
import com.vitorpamplona.quartz.buzz.pmPinnedMessages.PinMessageEvent
import com.vitorpamplona.quartz.buzz.pmPinnedMessages.UnpinMessageEvent
import com.vitorpamplona.quartz.buzz.workspace.BUZZ_VISIBILITY_OPEN
import com.vitorpamplona.quartz.buzz.workspace.BUZZ_VISIBILITY_PRIVATE
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.CreateGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupPutUserEvent

/**
 * The fixture the Buzz vectors share with their generator (`quartz/tools/buzz-vector-gen`): the
 * same user keys (the relay's owner is [owner]), fixed channel ids, and [ourCases], the templates
 * Quartz's builders write for the commands a client sends Buzz, in the order the generator sends
 * them. Ids that only exist once the relay runs (the messages to pin, the DM's channel) are
 * [PLACEHOLDERS] the generator swaps for the live ones before signing.
 */
object BuzzVectorFixture {
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

    const val STREAM = "0b0b0b0b-0000-4000-8000-000000000001"
    const val FORUM = "0b0b0b0b-0000-4000-8000-000000000002"
    const val DUPLICATE = "0b0b0b0b-0000-4000-8000-000000000003"
    val MESSAGE_ONE = "a1".repeat(32)
    val MESSAGE_TWO = "a2".repeat(32)
    const val DM = "0d0d0d0d-0000-4000-8000-00000000000d"

    val PLACEHOLDERS =
        linkedMapOf(
            "stream_channel" to STREAM,
            "forum_channel" to FORUM,
            "message_one" to MESSAGE_ONE,
            "message_two" to MESSAGE_TWO,
            "dm_channel" to DM,
        )

    /** 2026-01-01T00:00:00Z; the generator re-signs at the time it runs. */
    const val T0 = 1767225600L

    class Case(
        val name: String,
        val who: String,
        val template: EventTemplate<*>,
    )

    fun ourCases(): List<Case> =
        listOf(
            // The app's create on Buzz (relayGroupCreateTemplates): the metadata rides the 9007, then a 9002.
            Case("create_stream", "owner", CreateGroupEvent.build(STREAM, name = "pizza-lovers", about = "a channel for people who love pizza", visibility = BUZZ_VISIBILITY_OPEN, channelType = "stream", createdAt = T0)),
            Case("create_stream_edit", "owner", GroupEditMetadataEvent.build(STREAM, name = "pizza-lovers", about = "a channel for people who love pizza", createdAt = T0)),
            Case("put_user_member", "owner", GroupPutUserEvent.buildRoleChange(STREAM, alice, listOf("member"), buzzRole = "member", createdAt = T0)),
            Case("put_user_admin", "owner", GroupPutUserEvent.buildRoleChange(STREAM, bob, listOf("admin"), buzzRole = "admin", createdAt = T0)),
            Case("pin_first", "owner", PinMessageEvent.build(STREAM, MESSAGE_ONE, clientId = "pin-1", createdAt = T0)),
            Case("pin_second", "owner", PinMessageEvent.build(STREAM, MESSAGE_TWO, clientId = "pin-2", createdAt = T0)),
            Case("unpin_first", "owner", UnpinMessageEvent.build(STREAM, MESSAGE_ONE, clientId = "unpin-1", createdAt = T0)),
            // A second listed channel may not take a listed name another one holds.
            Case("create_duplicate_listed_name", "alice", CreateGroupEvent.build(DUPLICATE, name = "pizza-lovers", about = "again", visibility = BUZZ_VISIBILITY_OPEN, channelType = "stream", createdAt = T0)),
            // A private channel is unlisted, so its name may repeat a listed one.
            Case("create_private_forum", "owner", CreateGroupEvent.build(FORUM, name = "pizza-lovers", about = "recipes", visibility = BUZZ_VISIBILITY_PRIVATE, channelType = "forum", createdAt = T0)),
            Case(
                "create_private_forum_edit",
                "owner",
                GroupEditMetadataEvent.build(FORUM, name = "pizza-lovers", about = "recipes", status = setOf(GroupMetadataEvent.GroupStatus.PRIVATE), createdAt = T0),
            ),
            Case("dm_open", "owner", BuzzDmRules.openTemplate(listOf(alice), createdAt = T0)),
            Case("dm_add_with_history", "owner", BuzzDmRules.addMemberTemplate(DM, carol, includeHistory = true, createdAt = T0)),
            Case("dm_add_without_history", "owner", BuzzDmRules.addMemberTemplate(DM, bob, includeHistory = false, createdAt = T0)),
            Case("dm_open_again", "owner", BuzzDmRules.openTemplate(listOf(alice), createdAt = T0)),
        )
}
