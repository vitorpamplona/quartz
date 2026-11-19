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
package com.vitorpamplona.quartz.buzz.dm

import com.vitorpamplona.quartz.buzz.dm.tags.DmHistoryTag
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Buzz DM rules as of buzz `97d5a46cc` (`handle_dm_open` / `handle_dm_add_member` in
 * `crates/buzz-relay/src/handlers/command_executor.rs`, `MAX_DM_PEOPLE` in `crates/buzz-db/src/store/dm.rs`).
 *
 * - A DM's identity is its **people**: it holds at most [MAX_PEOPLE] of them, the caller included,
 *   and agents (keys with an owner) neither count toward that cap nor fork the DM.
 * - A kind:41011 with `["history", "include"]` grows the DM in place and shares its history; one
 *   without the tag (what older clients send) adds no one in place: the relay finds or creates the
 *   DM of the old people plus the newcomers. `["history", "ask"]` makes the relay refuse with
 *   [NEEDS_HISTORY_CHOICE] instead, so a client can ask the user first ([DmHistoryTag]).
 * - Both commands answer in the OK as `response:{"channel_id":"…","created":bool}` ([channelIdFromOk]).
 */
object BuzzDmRules {
    /** People in one DM, the caller included. Agents don't count. */
    const val MAX_PEOPLE = 9

    /** The relay's refusal of a `history=ask` add that would let people into an existing DM. */
    const val NEEDS_HISTORY_CHOICE = "needs-choice: history"

    /**
     * Whether [candidate] fits next to the [others] already picked (the caller is not in [others]): an
     * agent always fits, a person only while the DM holds fewer than [MAX_PEOPLE] people with the
     * caller. The relay decides who is an agent from its own records; [isAgent] is the client's best guess.
     */
    fun canAdd(
        others: List<HexKey>,
        candidate: HexKey,
        isAgent: (HexKey) -> Boolean,
    ): Boolean = isAgent(candidate) || 1 + others.count { !isAgent(it) } < MAX_PEOPLE

    /**
     * A kind:41010 for [participants] (the others; the relay adds the caller). Unlike
     * [DmOpenEvent.build], which stops at [DmOpenEvent.MAX_PARTICIPANTS], this lets agents ride past
     * the people cap, as the relay does.
     */
    fun openTemplate(
        participants: List<HexKey>,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<DmOpenEvent> {
        require(participants.isNotEmpty()) { "A DM open needs at least one other participant" }
        return eventTemplate(DmOpenEvent.KIND, "", createdAt) { participants(participants) }
    }

    /**
     * A kind:41011 adding [member] to the DM [channelId]. With [includeHistory] the DM grows in place
     * and [member] sees its earlier messages; without it the relay moves the conversation to the DM
     * of these people instead.
     */
    fun addMemberTemplate(
        channelId: String,
        member: HexKey,
        includeHistory: Boolean,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<DmAddMemberEvent> =
        DmAddMemberEvent.build(channelId, member, createdAt) {
            if (includeHistory) dmHistory(DmHistoryTag.INCLUDE)
        }

    /** The relay-assigned DM channel id in an OK message (`response:{"channel_id":"…"}`), if any. */
    fun channelIdFromOk(message: String?): String? =
        message
            ?.substringAfter("\"channel_id\":\"", "")
            ?.substringBefore('"')
            ?.takeIf { it.isNotBlank() }
}
