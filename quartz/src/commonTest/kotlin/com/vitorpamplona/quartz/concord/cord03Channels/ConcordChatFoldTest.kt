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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.appEvents.ConcordAppEventFixture
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ConcordChatFold] and [ConcordChatRules]: edits, deletes, moderation hides and reaction tallies (CORD-03 §3). */
class ConcordChatFoldTest {
    private val fx = ConcordAppEventFixture
    private val ch = fx.GENERAL
    private val e = fx.EPOCH
    private val t0 = fx.T0

    private fun fold(control: List<Event>) = ConcordCommunityState.fold(control.mapNotNull { ControlEdition.fromRumor(it) }, fx.communityId, fx.owner)

    private val state = fold(fx.ourControl())

    // The mod's Grant is the last Control edition: without it they hold no MANAGE_MESSAGES.
    private val modStripped = fold(fx.ourControl().dropLast(1))

    private val roster = fx.resolver(fx.ourControl())

    private fun msg(
        author: String,
        text: String,
        secs: Long,
        ms: Int = 0,
    ) = ChannelChat.message(author, ch, e, text, t0 + secs, ms = ms)

    @Test
    fun onlyTheAuthorsNewestEditApplies() {
        val m = msg(fx.alice, "original", 1)
        val first = ChannelChat.edit(fx.alice, ch, e, m.id, "first", t0 + 2)
        val second = ChannelChat.edit(fx.alice, ch, e, m.id, "second", t0 + 3)
        val foreign = ChannelChat.edit(fx.bob, ch, e, m.id, "not yours", t0 + 4)
        val f = ConcordChatFold(ch)
        // Order of arrival does not matter.
        f.addAll(listOf(second, foreign, m, first), state)
        assertEquals("second", f.timeline(state).single().content)
        assertTrue(f.timeline(state).single().edited)
        assertEquals(second.id, f.latestEdit(m.id)?.id)
    }

    @Test
    fun anEditTieBreaksOnTheLargerIdAndADeletedEditStopsCounting() {
        val m = msg(fx.alice, "original", 1)
        val a = ChannelChat.edit(fx.alice, ch, e, m.id, "a", t0 + 2, ms = 5)
        val b = ChannelChat.edit(fx.alice, ch, e, m.id, "b", t0 + 2, ms = 5)
        val winner = if (a.id > b.id) a else b
        val loser = if (a.id > b.id) b else a
        assertEquals(winner.id, ConcordChatRules.latestEdit(fx.alice, listOf(a, b).map { it as ConcordChatEditEvent })?.id)
        val f = ConcordChatFold(ch)
        f.addAll(listOf(m, a, b), state)
        assertEquals(winner.content, f.timeline(state).single().content)
        f.add(ChannelChat.delete(fx.alice, ch, e, listOf(winner), t0 + 3), state)
        assertEquals(loser.content, f.timeline(state).single().content)
    }

    @Test
    fun aSelfDeleteRemovesAndAMembersDeleteOfAnotherDoesNot() {
        val mine = msg(fx.alice, "mine", 1)
        val theirs = msg(fx.bob, "theirs", 2)
        val f = ConcordChatFold(ch)
        f.addAll(listOf(mine, theirs), state)
        f.add(ChannelChat.delete(fx.alice, ch, e, listOf(mine), t0 + 3), state)
        f.add(ChannelChat.moderationHide(fx.alice, ch, e, listOf(theirs), roster.citationFor(fx.alice), t0 + 4), state)
        assertFalse(f.isVisible(mine.id, state))
        assertTrue(f.isVisible(theirs.id, state))
        assertEquals(listOf("theirs"), f.timeline(state).map { it.content })
    }

    @Test
    fun aModeratorHideAppliesToALateTargetAndComesUndoneWhenTheBitGoes() {
        val target = msg(fx.alice, "spam", 1)
        val hide = ChannelChat.moderationHide(fx.mod, ch, e, listOf(target), roster.citationFor(fx.mod), t0 + 2)
        val f = ConcordChatFold(ch)
        f.add(hide, state)
        f.add(target, state)
        assertFalse(f.isVisible(target.id, state))
        assertTrue(f.isRemoved(target, state))
        assertTrue(f.isVisible(target.id, modStripped), "a hide by someone stripped of MANAGE_MESSAGES comes undone")
        assertTrue(f.isVisible(target.id, null), "before the Control Plane folds only self-deletes count")
    }

    @Test
    fun aModeratorCannotHideTheOwnerOrAHigherRank() {
        val byOwner = msg(fx.owner, "owner", 1)
        val byAdmin = msg(fx.admin, "admin", 2)
        val f = ConcordChatFold(ch)
        f.addAll(listOf(byOwner, byAdmin), state)
        f.add(ChannelChat.moderationHide(fx.mod, ch, e, listOf(byOwner, byAdmin), roster.citationFor(fx.mod), t0 + 3), state)
        assertEquals(listOf("owner", "admin"), f.timeline(state).map { it.content })
    }

    @Test
    fun reactionsTallyOncePerReactorAndKey() {
        val m = msg(fx.alice, "hello", 1)
        val plus = ChannelChat.reaction(fx.bob, ch, e, m.id, fx.alice, 9, "+", t0 + 2)
        val thumbs = ChannelChat.reaction(fx.bob, ch, e, m.id, fx.alice, 9, "\uD83D\uDC4D", t0 + 3)
        val modPlus = ChannelChat.reaction(fx.mod, ch, e, m.id, fx.alice, 9, "+", t0 + 4)
        val dislike = ChannelChat.reaction(fx.alice, ch, e, m.id, fx.alice, 9, "-", t0 + 5)
        val sentence = ChannelChat.reaction(fx.mod, ch, e, m.id, fx.alice, 9, "this is a whole sentence", t0 + 6)
        val link = ChannelChat.reaction(fx.mod, ch, e, m.id, fx.alice, 9, "https://x.y", t0 + 7)
        val custom = ChannelChat.reaction(fx.mod, ch, e, m.id, fx.alice, 9, ":soap:", t0 + 8, arrayOf(arrayOf("emoji", "soap", "https://e.invalid/soap.png")))
        val f = ConcordChatFold(ch)
        f.addAll(listOf(m, plus, thumbs, modPlus, dislike, sentence, link, custom), state)
        val tallies = f.reactions(m.id, state).associateBy { it.key }
        assertEquals(setOf("\uD83D\uDC4D", "\uD83D\uDC4E", ":soap:"), tallies.keys)
        assertEquals(setOf(fx.bob, fx.mod), tallies.getValue("\uD83D\uDC4D").reactors.keys)
        assertEquals(thumbs.id, tallies.getValue("\uD83D\uDC4D").reactors[fx.bob], "the reactor's newest reaction under a key stands for them")
        assertEquals("https://e.invalid/soap.png", tallies.getValue(":soap:").url)
        assertEquals(
            tallies.keys,
            f
                .timeline(state)
                .single()
                .reactions
                .map { it.key }
                .toSet(),
        )
    }

    @Test
    fun onlyItsAuthorWithdrawsAReaction() {
        val m = msg(fx.alice, "hello", 1)
        val plus = ChannelChat.reaction(fx.bob, ch, e, m.id, fx.alice, 9, "+", t0 + 2)
        val f = ConcordChatFold(ch)
        f.addAll(listOf(m, plus), state)
        f.add(ChannelChat.moderationHide(fx.mod, ch, e, listOf(plus), roster.citationFor(fx.mod), t0 + 3), state)
        assertEquals(1, f.reactions(m.id, state).single().count, "a moderation hide never withdraws a reaction")
        f.add(ChannelChat.delete(fx.bob, ch, e, listOf(plus), t0 + 4), state)
        assertTrue(f.reactions(m.id, state).isEmpty())
    }

    @Test
    fun aFloodOfSelfDeletesDoesNotEvictAModeratorsHide() {
        val target = msg(fx.alice, "spam", 1)
        val f = ConcordChatFold(ch, maxHeldDeletes = 5)
        f.add(target, state)
        f.add(ChannelChat.moderationHide(fx.mod, ch, e, listOf(target), roster.citationFor(fx.mod), t0 + 2), state)
        repeat(50) { i ->
            val own = msg(fx.bob, "flood $i", 10L + i)
            f.add(own, state)
            f.add(ChannelChat.delete(fx.bob, ch, e, listOf(own), t0 + 10 + i), state)
        }
        assertEquals(5, f.hides.size(ch))
        assertFalse(f.isVisible(target.id, state), "the moderator's hide survived the flood")
        assertTrue(f.timeline(state).isEmpty(), "every flooded message stays deleted: a self-delete always stands")
    }

    // Before the Control Plane folds no delete can be judged. A member's flood must still not push
    // out a moderator's hide held meanwhile: unjudged deletes are evicted from whoever holds the
    // most of them, and judged once a fold is there to judge them.
    @Test
    fun aFloodBeforeTheControlPlaneFoldsDoesNotEvictAModeratorsHide() {
        val target = msg(fx.alice, "spam", 1)
        val f = ConcordChatFold(ch, maxHeldDeletes = 5)
        f.add(target)
        f.add(ChannelChat.moderationHide(fx.mod, ch, e, listOf(target), roster.citationFor(fx.mod), t0 + 2))
        repeat(50) { i ->
            val own = msg(fx.bob, "flood $i", 10L + i)
            f.add(own)
            f.add(ChannelChat.delete(fx.bob, ch, e, listOf(own), t0 + 10 + i))
        }
        assertEquals(5, f.hides.size(ch))
        assertFalse(f.isVisible(target.id, state), "the moderator's hide survived the unjudged flood")
        assertTrue(f.timeline(state).isEmpty(), "every flooded message stays deleted: a self-delete always stands")
    }

    @Test
    fun aHideHeldUnjudgedIsJudgedOnceAFoldArrives() {
        val target = msg(fx.alice, "spam", 1)
        val f = ConcordChatFold(ch, maxHeldDeletes = 5)
        f.add(target)
        f.add(ChannelChat.moderationHide(fx.mod, ch, e, listOf(target), roster.citationFor(fx.mod), t0 + 2))
        // Several members' deletes, held before the fold, then a flood judged against it.
        repeat(4) { i ->
            val own = msg(fx.bob, "early $i", 5L + i)
            f.add(own)
            f.add(ChannelChat.delete(fx.bob, ch, e, listOf(own), t0 + 5 + i))
        }
        repeat(50) { i ->
            val own = msg(fx.alice, "flood $i", 10L + i)
            f.add(own, state)
            f.add(ChannelChat.delete(fx.alice, ch, e, listOf(own), t0 + 10 + i), state)
        }
        assertEquals(5, f.hides.size(ch))
        assertFalse(f.isVisible(target.id, state), "the moderator's hide survived once judged")
    }

    @Test
    fun renderableReactions() {
        assertTrue(ConcordChatRules.isRenderableReaction(""))
        assertTrue(ConcordChatRules.isRenderableReaction("+"))
        assertTrue(ConcordChatRules.isRenderableReaction("\uD83E\uDD19"))
        assertTrue(ConcordChatRules.isRenderableReaction(":party_parrot:"))
        assertFalse(ConcordChatRules.isRenderableReaction(":${"a".repeat(49)}:"))
        assertFalse(ConcordChatRules.isRenderableReaction("two words"))
        assertFalse(ConcordChatRules.isRenderableReaction("a".repeat(13)))
        assertTrue(ConcordChatRules.isRenderableReaction("\uD83D\uDE00".repeat(12)), "twelve code points, twenty-four UTF-16 units")
        assertFalse(ConcordChatRules.isRenderableReaction("x://y"))
        assertNull(ConcordChatRules.latestEdit(fx.alice, emptyList()))
    }
}
