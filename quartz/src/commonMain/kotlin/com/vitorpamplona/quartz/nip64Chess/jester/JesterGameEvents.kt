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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip64Chess.Color
import com.vitorpamplona.quartz.nip64Chess.GameResult
import com.vitorpamplona.quartz.nip64Chess.GameTermination

/**
 * Game events container for Jester protocol
 */
data class JesterGameEvents(
    val startEvent: JesterEvent?,
    val moves: List<JesterEvent>,
) {
    companion object {
        fun empty() = JesterGameEvents(null, emptyList())
    }

    /**
     * The game as its two players played it, from these events alone; see [JesterMainLine].
     * Moves are checked for who may make them and what they claim to follow, not for chess
     * legality, which needs an engine: a client replays [JesterMainLine.history] and stops at the
     * first illegal move.
     *
     * @param abandonmentAfterSeconds how long the player to move must stay silent before their
     *   opponent's abandonment claim counts; null accepts none. The app's policy, so every
     *   client that wants the same outcome passes the same value.
     */
    fun mainLine(abandonmentAfterSeconds: Long? = null): JesterMainLine? {
        val start = startEvent?.takeIf { it.isStartEvent() } ?: return null
        val challengerColor = start.playerColor() ?: Color.WHITE
        val seats = mutableMapOf<Color, HexKey>(challengerColor to start.pubKey)
        // A challenge that names its opponent seats them from the start; a jesterui one, filed only
        // under a private ref, seats them when they move.
        start.opponentKeys().firstOrNull()?.takeIf { it != start.pubKey }?.let { seats[challengerColor.opposite()] = it }
        val gameMoves = moves.filter { it.isMoveEvent() && it.startEventId() == start.id }

        val line = ArrayList<JesterEvent>()
        var history = emptyList<String>()
        var ending: JesterEvent? = null
        while (true) {
            val toMove = if (history.size % 2 == 0) Color.WHITE else Color.BLACK
            val seated = seats[toMove]
            val tip = line.lastOrNull() ?: start
            val next =
                gameMoves
                    .filter { move ->
                        val san = move.move() ?: return@filter false
                        val moved = move.history()
                        moved.size == history.size + 1 &&
                            moved.last() == san &&
                            moved.subList(0, history.size) == history &&
                            (if (seated != null) move.pubKey == seated else start.isSeatOpenTo(move.pubKey))
                    }
                    // The one that names the tip as its parent first, then the earliest (relays deliver in any order).
                    .minWithOrNull(compareBy<JesterEvent>({ it.headEventId() != tip.id }, { it.createdAt }, { it.id }))
                    ?: break
            if (seated == null) seats[toMove] = next.pubKey
            line.add(next)
            history = next.history()
            if (next.result() != null) {
                ending = next
                break
            }
        }

        val sideToMove = if (history.size % 2 == 0) Color.WHITE else Color.BLACK
        if (ending == null) {
            val tip = line.lastOrNull()
            ending =
                gameMoves
                    .filter { it.move() == null && it.history() == history && isValidEnding(it, seats, sideToMove, tip, abandonmentAfterSeconds) }
                    .minWithOrNull(compareBy<JesterEvent>({ it.createdAt }, { it.id }))
        }

        return JesterMainLine(start, seats[Color.WHITE], seats[Color.BLACK], line, ending)
    }

    /**
     * Whether a move-less end (a resignation, a draw by agreement, an abandonment claim) is one
     * its author may make here: the loser resigns, a draw is accepted only right after the
     * opponent offered it, and an abandonment is claimed by the waiting player once the one to
     * move has been silent long enough.
     */
    private fun isValidEnding(
        end: JesterEvent,
        seats: Map<Color, HexKey>,
        sideToMove: Color,
        tip: JesterEvent?,
        abandonmentAfterSeconds: Long?,
    ): Boolean {
        val authorColor = seats.entries.firstOrNull { it.value == end.pubKey }?.key ?: return false
        if (seats.size < 2) return false
        val result = end.result()?.let { GameResult.parse(it) } ?: return false
        val termination = end.termination()?.let { tag -> GameTermination.entries.firstOrNull { it.name.equals(tag, ignoreCase = true) } }
        val authorWins = if (authorColor == Color.WHITE) GameResult.WHITE_WINS else GameResult.BLACK_WINS
        val authorLoses = if (authorColor == Color.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS
        return when (termination) {
            GameTermination.RESIGNATION -> result == authorLoses
            GameTermination.DRAW_AGREEMENT -> result == GameResult.DRAW && tip != null && tip.pubKey != end.pubKey && tip.drawOffered()
            GameTermination.ABANDONMENT ->
                abandonmentAfterSeconds != null &&
                    result == authorWins &&
                    authorColor != sideToMove &&
                    end.createdAt - (tip ?: return false).createdAt >= abandonmentAfterSeconds
            else -> false
        }
    }

    /** Get the latest move (with longest history). Trusts any author; prefer [mainLine]. */
    fun latestMove(): JesterEvent? = moves.maxByOrNull { it.history().size }

    /** Get the current FEN position */
    fun currentFen(): String = latestMove()?.fen() ?: startEvent?.fen() ?: JesterProtocol.FEN_START

    /** Get the complete move history */
    fun fullHistory(): List<String> = latestMove()?.history() ?: emptyList()

    /** Check if game has ended */
    fun isEnded(): Boolean = latestMove()?.result() != null

    /** Get the game result if ended */
    fun result(): String? = latestMove()?.result()
}

/**
 * A Jester game as [JesterGameEvents.mainLine] reads it: the challenge, who holds each seat
 * (null while open), the moves that count in order, and the event that ended it, if any.
 *
 * A move counts when it comes from the player whose turn it is and its history is the line so
 * far plus its own move. An empty seat goes to the first player allowed to take it (anyone on an
 * open challenge, the named player on a direct one) who makes that seat's first move; a
 * challenge that names its opponent with a `p` tag seats them before they move. Among
 * moves that would both count, the one naming the previous move as its parent wins, then the
 * earliest. Everything after an ending is ignored.
 */
@Immutable
class JesterMainLine(
    val start: JesterEvent,
    val white: HexKey?,
    val black: HexKey?,
    val moves: List<JesterEvent>,
    /** A move that carries a `result`, or a move-less end (resignation, draw, abandonment); null while the game runs. */
    val ending: JesterEvent?,
) {
    val history: List<String> get() = moves.lastOrNull()?.history() ?: emptyList()

    /** The event the next move answers: the last move, or the challenge before any. */
    val head: JesterEvent get() = moves.lastOrNull() ?: start

    val sideToMove: Color get() = if (moves.size % 2 == 0) Color.WHITE else Color.BLACK

    fun seatOf(pubkey: HexKey): Color? =
        when (pubkey) {
            white -> Color.WHITE
            black -> Color.BLACK
            else -> null
        }

    /** The colour whose draw offer stands: the last move's author, when that move offered one and the game goes on. */
    val drawOfferedBy: Color? get() = moves.lastOrNull()?.takeIf { ending == null && it.drawOffered() }?.let { seatOf(it.pubKey) }
}
