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
package com.vitorpamplona.quartz.concord.appEvents

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookEntry
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChatEditEvent
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.GrantEntity
import com.vitorpamplona.quartz.concord.cord04Roles.RoleEntity
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.VacTag
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.concord.envelope.OpenedStreamEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.EmojiUrlTag
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What Quartz makes of a Concord rumor, in the vocabulary the generator records Armada's reading
 * in (`quartz/tools/concord-vector-gen`), so the two compare key by key.
 *
 * Per rumor it is Quartz's own API: the Chat ingest gate ([ChannelChat.acceptOpened]), the
 * ordering time, the event classes' readers (inline quote, NIP-22 root and parent, mentions, edit
 * and reaction targets, deletes and their `vac`), [Guestbook.coalesce] with its Kick gate, the
 * Control fold ([ConcordCommunityState.fold]) and [ConcordCommunityState.honorsModerationHide].
 *
 * The `after` readings (what a timeline shows once the rumor lands) need a chat fold, which
 * Quartz does not have: Amethyst folds edits (`NoteEditOverlays.latestConcordEdit`), deletes and
 * reaction tallies in its own cache. [ChatFold] restates the rules Quartz's builders document
 * (an edit applies only from the original's author, newest by ordering time; a delete removes the
 * deleter's own rumor, or another's when [ConcordCommunityState.honorsModerationHide] says so),
 * so the vectors pin those rules until a shared fold moves here.
 */
object ConcordAppEventReading {
    private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun pTags(rumor: Event) = rumor.tags.mapNotNull(PTag::parseKey)

    /** A rumor opened from a Chat Plane wrap with an encrypted seal, as the envelope hands it over. */
    private fun opened(rumor: Event) = OpenedStreamEvent(rumor, ConcordStreamEnvelope.KIND_SEAL_ENCRYPTED, rumor.pubKey, rumor)

    /** The reference chat fold the `after` readings are judged by (see the class doc). */
    class ChatFold(
        private val state: ConcordCommunityState,
        private val channel: String,
    ) {
        val seen = ArrayList<Event>()

        private fun byId(id: String) = seen.firstOrNull { it.id == id }

        private fun removedBy(
            target: Event,
            delete: Event,
        ): Boolean {
            if (delete !is DeletionRequestEvent || target.id !in delete.deleteEventIds()) return false
            if (delete.pubKey == target.pubKey) return true
            val citation = delete.tags.firstOrNull(VacTag::isTag)?.let(VacTag::parse)
            return target.kind != ReactionEvent.KIND && state.honorsModerationHide(delete.pubKey, target.pubKey, channel, citation)
        }

        fun removed(target: Event) = seen.any { removedBy(target, it) }

        fun visible(id: String): Boolean {
            val t = byId(id) ?: return false
            return (t is ChatEvent || t is CommentEvent) && !removed(t)
        }

        fun latestEdit(target: Event): ConcordChatEditEvent? =
            seen
                .filterIsInstance<ConcordChatEditEvent>()
                .filter { it.editedMessageId() == target.id && it.pubKey == target.pubKey }
                .maxWithOrNull(compareBy({ it.orderingMs() }, { it.id }))

        fun target(id: String?) = id?.let { byId(it) }
    }

    fun chat(
        rumors: List<Event>,
        state: ConcordCommunityState,
        channel: String,
        epoch: Long,
    ): List<JsonObject> {
        val fold = ChatFold(state, channel)
        return rumors.map { rumor ->
            val accepted = ChannelChat.acceptOpened(opened(rumor), channel, epoch) != null
            if (accepted) fold.seen.add(rumor)
            buildJsonObject {
                put("accepted", accepted)
                put("ms", ChannelChat.orderingMs(rumor)?.let(::JsonPrimitive) ?: JsonNull)
                if (!accepted) return@buildJsonObject
                when (rumor) {
                    is ChatEvent, is CommentEvent -> {
                        put("inline_reply_to", str((rumor as? ChatEvent)?.replyingTo()))
                        put("thread_root", str((rumor as? CommentEvent)?.rootEventIds()?.firstOrNull()))
                        put("thread_parent", str((rumor as? CommentEvent)?.replyEventIds()?.firstOrNull()))
                        put("mentions", buildJsonArray { pTags(rumor).forEach { add(JsonPrimitive(it)) } })
                        put("visible", fold.visible(rumor.id))
                    }
                    is ConcordChatEditEvent -> {
                        put("target", str(rumor.editedMessageId()))
                        val target = fold.target(rumor.editedMessageId())
                        val shown = target?.takeIf { fold.visible(it.id) }
                        val edit = shown?.let { fold.latestEdit(it) }
                        put(
                            "after",
                            buildJsonObject {
                                put("target_content", str(shown?.let { edit?.content ?: it.content }))
                                put("target_edited", edit != null)
                            },
                        )
                    }
                    is ReactionEvent -> {
                        put("target", str(rumor.originalPost().firstOrNull()))
                        val code = Regex("^:(.+):$").find(rumor.content)?.groupValues?.get(1)
                        val url = code?.let { c -> rumor.tags.firstNotNullOfOrNull { EmojiUrlTag.parse(it)?.takeIf { e -> e.code == c } }?.url }
                        put(
                            "reaction",
                            buildJsonObject {
                                put("content", rumor.content)
                                put("custom_emoji_url", str(url))
                            },
                        )
                        put(
                            "after",
                            buildJsonObject {
                                put("tallied", !fold.removed(rumor))
                                put("url", str(url))
                            },
                        )
                    }
                    is DeletionRequestEvent -> {
                        put("deletes", buildJsonArray { rumor.deleteEventIds().forEach { add(JsonPrimitive(it)) } })
                        put("citation", rumor.tags.any { VacTag.isTag(it) && VacTag.parse(it) != null })
                        put(
                            "after",
                            buildJsonObject {
                                rumor.deleteEventIds().forEach { id ->
                                    val target = fold.target(id)
                                    put(
                                        id,
                                        when {
                                            target == null -> "unknown"
                                            fold.removed(target) -> "removed"
                                            else -> "kept"
                                        },
                                    )
                                }
                            },
                        )
                    }
                    else -> {
                        if (ChannelChat.isTyping(rumor)) put("typing", true)
                    }
                }
            }
        }
    }

    fun guestbook(
        rumors: List<Event>,
        state: ConcordCommunityState,
        nowMs: Long,
    ): List<JsonObject> {
        val entries = ArrayList<GuestbookEntry>()
        return rumors.map { rumor ->
            val entry = Guestbook.parse(rumor)
            if (entry != null) entries.add(entry)
            val subject = if (rumor.kind == Guestbook.KIND_KICK) Guestbook.kickTarget(rumor) else rumor.pubKey
            val winner = subject?.let { Guestbook.coalesce(entries, nowMs, state.authority)[it.lowercase()] }?.takeIf { it.rumorId == rumor.id }
            buildJsonObject {
                put("accepted", entry != null)
                put("ms", entry?.ms?.let(::JsonPrimitive) ?: JsonNull)
                put("member", str(subject))
                put("state", str(winner?.action?.wire))
                put("invite_creator", str(winner?.inviteCreator))
                put("invite_label", str(winner?.inviteLabel))
            }
        }
    }

    fun edition(
        base: List<Event>,
        rumor: Event,
        fold: (List<ControlEdition>) -> ConcordCommunityState,
    ): JsonObject {
        val edition = ControlEdition.fromRumor(rumor)
        val state = fold((base + rumor).mapNotNull { ControlEdition.fromRumor(it) })
        val head = edition?.let { state.authority.authorityHeads()[it.entityIdHex] }
        return buildJsonObject {
            put("accepted", edition != null)
            put("honored", head != null && head.rumorId == rumor.id)
            when (edition?.entityKind) {
                ControlEntityKind.ROLE -> {
                    val roleId = ConcordJson.decodeOrNull<RoleEntity>(rumor.content)?.roleId
                    val role = roleId?.let { state.roles[it] }
                    put(
                        "role",
                        if (role == null) {
                            JsonNull
                        } else {
                            buildJsonObject {
                                put("role_id", role.roleId ?: roleId)
                                put("position", role.position)
                                put("permissions", role.permissions)
                            }
                        },
                    )
                }
                ControlEntityKind.GRANT -> {
                    val member = ConcordJson.decodeOrNull<GrantEntity>(rumor.content)?.member ?: ""
                    put(
                        "member_roles",
                        buildJsonArray {
                            state.authority
                                .rolesOf(member)
                                .sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
                else -> {}
            }
        }
    }
}
