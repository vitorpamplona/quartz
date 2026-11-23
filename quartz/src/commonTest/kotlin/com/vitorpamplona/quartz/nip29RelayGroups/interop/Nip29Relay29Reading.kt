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
import com.vitorpamplona.quartz.nip01Core.core.hasTagName
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupAdminsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMembersEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupRolesEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.CreateGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.DeleteGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupCreateInviteEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupDeleteEventEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupPutUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupRemoveUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupJoinRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupLeaveRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupAdminTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What Quartz makes of a NIP-29 event, in the vocabulary of `quartz/tools/nip29-vector-gen` (go-nostr's
 * nip29 reader and relay29's moderation parser), so a case's reading compares key by key with what
 * relay29 decided.
 */
object Nip29Relay29Reading {
    private fun str(value: String?) = JsonPrimitive(value)

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    private fun targets(admins: List<GroupAdminTag>) = JsonArray(admins.map { obj("pubkey" to str(it.pubKey), "roles" to strings(it.roles)) })

    /** A relay-signed group state event (39000-39003) or a relay-issued 9000/9001. */
    fun state(event: Event): JsonObject =
        when (event) {
            // go-nostr shows the group id when a 39000 has no name; missing about/picture read as "".
            is GroupMetadataEvent ->
                obj(
                    "group_id" to str(event.groupId()),
                    "name" to str(event.name() ?: event.groupId()),
                    "about" to str(event.about() ?: ""),
                    "picture" to str(event.picture() ?: ""),
                    "private" to JsonPrimitive(event.isPrivate()),
                    "closed" to JsonPrimitive(event.isClosed()),
                )
            is GroupAdminsEvent ->
                obj(
                    "group_id" to str(event.groupId()),
                    "admins" to JsonObject(event.admins().filter { it.roles.isNotEmpty() }.associate { it.pubKey to strings(it.roles) }),
                )
            is GroupMembersEvent -> obj("group_id" to str(event.groupId()), "members" to strings(event.members().sorted()))
            is GroupRolesEvent ->
                obj(
                    "group_id" to str(event.groupId()),
                    "roles" to JsonArray(event.roles().map { obj("name" to str(it.name), "description" to str(it.description ?: "")) }),
                )
            else -> action(event)
        }

    /**
     * A moderation or request event a client sends. An edit is read as NIP-29 defines kind 9002 ("all
     * the fields of group-metadata"): the group it describes, whatever the group was before.
     */
    fun action(event: Event): JsonObject =
        when (event) {
            is CreateGroupEvent -> obj("group_id" to str(event.groupId()), "action" to str("create-group"))
            is DeleteGroupEvent -> obj("group_id" to str(event.groupId()), "action" to str("delete-group"))
            is GroupPutUserEvent -> obj("group_id" to str(event.groupId()), "action" to str("put-user"), "targets" to targets(event.tags.mapNotNull(GroupAdminTag::parse)))
            is GroupRemoveUserEvent -> obj("group_id" to str(event.groupId()), "action" to str("remove-user"), "targets" to strings(event.userPubKeys()))
            is GroupDeleteEventEvent -> obj("group_id" to str(event.groupId()), "action" to str("delete-event"), "targets" to strings(event.deletedEventIds()))
            is GroupCreateInviteEvent -> obj("group_id" to str(event.groupId()), "action" to str("create-invite"), "code" to str(event.code()))
            is GroupEditMetadataEvent ->
                obj(
                    "group_id" to str(event.groupId()),
                    "action" to str("edit-metadata"),
                    "applied_to_private_closed" to
                        obj(
                            "name" to str(event.name()),
                            "about" to str(event.about()),
                            "picture" to str(event.picture()),
                            "private" to JsonPrimitive(event.tags.hasTagName("private")),
                            "closed" to JsonPrimitive(event.tags.hasTagName("closed")),
                        ),
                )
            is GroupJoinRequestEvent -> obj("group_id" to str(event.groupId()), "action" to str("join-request"), "code" to str(event.inviteCode() ?: ""))
            is GroupLeaveRequestEvent -> obj("group_id" to str(event.groupId()), "action" to str("leave-request"))
            else -> obj("action" to str("message"), "kind" to JsonPrimitive(event.kind))
        }

    /** [action] plus whether the signature verifies, which relay29 checks too. */
    fun signedAction(event: Event): JsonObject = JsonObject(action(event) + ("signature_valid" to JsonPrimitive(event.verify())))
}
