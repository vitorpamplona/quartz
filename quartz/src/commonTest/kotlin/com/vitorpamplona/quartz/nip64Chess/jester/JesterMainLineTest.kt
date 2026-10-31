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
package com.vitorpamplona.quartz.nip64Chess.jester

import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip64Chess.Color
import com.vitorpamplona.quartz.nip64Chess.GameResult
import com.vitorpamplona.quartz.nip64Chess.GameTermination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who may move, which moves count, and which endings stand, as every client should read a Jester game. */
class JesterMainLineTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun EventTemplate<JesterEvent>.signedAs(
        id: String,
        author: String,
    ) = JesterEvent(id, author, createdAt, tags, content, "")

    private fun challenge(
        color: Color = Color.WHITE,
        opponent: String? = null,
    ) = JesterEvent.buildChallenge(color, opponent, nonce = "00ff00ff", createdAt = 1000).signedAs(START, alice)

    private fun move(
        id: String,
        author: String,
        head: String,
        history: List<String>,
        createdAt: Long,
        drawOffered: Boolean = false,
    ) = JesterEvent.buildMove(START, head, history.last(), "fen", history, "", drawOffered, createdAt).signedAs(id, author)

    private fun end(
        id: String,
        author: String,
        head: String,
        history: List<String>,
        result: GameResult,
        termination: GameTermination,
        createdAt: Long,
    ) = JesterEvent.buildEndMove(START, head, null, "fen", history, "", result, termination, createdAt).signedAs(id, author)

    private fun mainLine(
        start: JesterEvent,
        vararg moves: JesterEvent,
        abandonmentAfter: Long? = null,
    ) = JesterGameEvents(start, moves.toList()).mainLine(abandonmentAfter)!!

    @Test
    fun `the private start ref matches jesterui`() {
        // jesterui's jesterPrivateStartGameRef for fiatjaf's key, computed outside this codebase.
        assertEquals(
            "7d06e03b044bdc9200caa570d117ed40ca06d35c7308264e6870266c9663b5c4",
            JesterProtocol.privateStartRef("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"),
        )
    }

    @Test
    fun `a direct challenge is filed under the private ref and links no event`() {
        val start = challenge(opponent = bob)
        assertEquals(listOf(listOf("e", JesterProtocol.privateStartRef(bob)), listOf("p", bob)), start.tags.map { it.toList() })
        assertTrue(start.eventHints().isEmpty(), "a private ref is a digest, not an event to fetch")
        assertTrue(start.linkedEventIds().isEmpty())
        assertTrue(start.isSeatOpenTo(bob))
        assertFalse(start.isSeatOpenTo(carol))
        assertFalse(start.isSeatOpenTo(alice), "nobody plays themselves")
    }

    @Test
    fun `nostr blue's player_color is read too`() {
        val start = JesterEvent(START, alice, 1000, arrayOf(arrayOf("e", JesterProtocol.START_POSITION_HASH)), """{"version":"0","kind":0,"history":[],"player_color":"black"}""", "")
        assertEquals(Color.BLACK, start.playerColor())
        assertTrue(challenge(Color.BLACK).content.contains("\"player_color\":\"black\""), "we write both spellings")
    }

    @Test
    fun `the first answer takes the open seat and strangers' moves do not count`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val carolLate = move("m2c", carol, "m1", listOf("e4", "e5"), 2010)
        val bobEarly = move("m2b", bob, "m1", listOf("e4", "c5"), 2005)
        val carolForged = move("m9", carol, "m2b", listOf("e4", "c5", "Nf3", "d6", "d4"), 2020)

        val line = mainLine(challenge(), e4, carolLate, bobEarly, carolForged)
        assertEquals(alice, line.white)
        assertEquals(bob, line.black)
        assertEquals(listOf("e4", "c5"), line.history)
        assertEquals(Color.WHITE, line.sideToMove)
    }

    @Test
    fun `a challenger playing black lets the first answer play white`() {
        val d4 = move("m1", bob, START, listOf("d4"), 2000)
        val line = mainLine(challenge(Color.BLACK), d4)
        assertEquals(bob, line.white)
        assertEquals(alice, line.black)
    }

    @Test
    fun `a move must extend the line and come from the player to move`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val e5 = move("m2", bob, "m1", listOf("e4", "e5"), 2001)
        val rewritten = move("m3x", alice, "m2", listOf("d4", "e5", "Nf3"), 2002)
        val outOfTurn = move("m3y", bob, "m2", listOf("e4", "e5", "Nf3"), 2003)
        val mismatched =
            move("m3z", alice, "m2", listOf("e4", "e5", "Nf3"), 2004).let {
                // Its move field says one thing and its history another.
                JesterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content.replace("\"move\":\"Nf3\"", "\"move\":\"Nc3\""), "")
            }

        assertEquals(listOf("e4", "e5"), mainLine(challenge(), e4, e5, rewritten, outOfTurn, mismatched).history)
    }

    @Test
    fun `a direct challenge seats only the named player`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val carolE5 = move("m2c", carol, "m1", listOf("e4", "e5"), 2001)
        val bobC5 = move("m2b", bob, "m1", listOf("e4", "c5"), 2002)

        val line = mainLine(challenge(opponent = bob), e4, carolE5, bobC5)
        assertEquals(bob, line.black)
        assertEquals(listOf("e4", "c5"), line.history)
    }

    @Test
    fun `a named opponent holds the seat before moving`() {
        val line = mainLine(challenge(opponent = bob))
        assertEquals(alice, line.white)
        assertEquals(bob, line.black)
        assertTrue(line.moves.isEmpty())
    }

    @Test
    fun `the loser can resign but the winner cannot resign for them`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val e5 = move("m2", bob, "m1", listOf("e4", "e5"), 2001)
        val history = listOf("e4", "e5")

        val claim = end("x", alice, "m2", history, GameResult.WHITE_WINS, GameTermination.RESIGNATION, 3000)
        assertNull(mainLine(challenge(), e4, e5, claim).ending, "alice cannot resign on bob's behalf")

        val resign = end("r", bob, "m2", history, GameResult.WHITE_WINS, GameTermination.RESIGNATION, 3001)
        assertEquals("r", mainLine(challenge(), e4, e5, claim, resign).ending?.id)
    }

    @Test
    fun `a draw stands only when the other player offered it`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val offer = move("m2", bob, "m1", listOf("e4", "e5"), 2001, drawOffered = true)
        val history = listOf("e4", "e5")
        val accept = end("d", alice, "m2", history, GameResult.DRAW, GameTermination.DRAW_AGREEMENT, 2002)
        val selfAccept = end("s", bob, "m2", history, GameResult.DRAW, GameTermination.DRAW_AGREEMENT, 2002)

        assertEquals(Color.BLACK, mainLine(challenge(), e4, offer).drawOfferedBy)
        assertNull(mainLine(challenge(), e4, offer, selfAccept).ending)
        assertEquals("d", mainLine(challenge(), e4, offer, accept).ending?.id)
        assertNull(mainLine(challenge(), e4, offer, accept).drawOfferedBy, "an accepted offer no longer stands")

        val plain = move("m2", bob, "m1", listOf("e4", "e5"), 2001)
        assertNull(mainLine(challenge(), e4, plain, accept).ending, "no offer, nothing to accept")
    }

    @Test
    fun `abandonment is claimed by the waiting player after the app's delay`() {
        val e4 = move("m1", alice, START, listOf("e4"), 2000)
        val e5 = move("m2", bob, "m1", listOf("e4", "e5"), 2001)
        val history = listOf("e4", "e5")
        val day = 86_400L

        // Alice is to move and silent; bob waits.
        val early = end("early", bob, "m2", history, GameResult.BLACK_WINS, GameTermination.ABANDONMENT, 2001 + day)
        val late = end("late", bob, "m2", history, GameResult.BLACK_WINS, GameTermination.ABANDONMENT, 2001 + 3 * day)
        val byMover = end("mover", alice, "m2", history, GameResult.WHITE_WINS, GameTermination.ABANDONMENT, 2001 + 3 * day)

        assertNull(mainLine(challenge(), e4, e5, early, abandonmentAfter = 3 * day).ending)
        assertNull(mainLine(challenge(), e4, e5, byMover, abandonmentAfter = 3 * day).ending, "the player to move cannot claim")
        assertNull(mainLine(challenge(), e4, e5, late).ending, "no delay given, no claim counts")
        assertEquals("late", mainLine(challenge(), e4, e5, late, abandonmentAfter = 3 * day).ending?.id)
    }

    @Test
    fun `nothing counts after the game ends`() {
        val f3 = move("m1", alice, START, listOf("f3"), 2000)
        val e5 = move("m2", bob, "m1", listOf("f3", "e5"), 2001)
        val g4 = move("m3", alice, "m2", listOf("f3", "e5", "g4"), 2002)
        val mate =
            JesterEvent
                .buildEndMove(START, "m3", "Qh4#", "fen", listOf("f3", "e5", "g4", "Qh4#"), alice, GameResult.BLACK_WINS, GameTermination.CHECKMATE, 2003)
                .signedAs("m4", bob)
        val after = move("m5", alice, "m4", listOf("f3", "e5", "g4", "Qh4#", "a3"), 2004)

        val line = mainLine(challenge(), f3, e5, g4, mate, after)
        assertEquals("m4", line.ending?.id)
        assertEquals(4, line.moves.size)
    }

    companion object {
        const val START = "start-event"
    }
}
