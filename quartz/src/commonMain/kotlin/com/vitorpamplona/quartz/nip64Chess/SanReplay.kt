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
package com.vitorpamplona.quartz.nip64Chess

import kotlin.math.abs
import kotlin.math.max

/**
 * Plays parsed SAN moves on a [ChessPosition] by the rules: the piece that moves is the one that
 * can legally reach the square (blocked paths and pins count, so SAN without disambiguation picks
 * the right piece), and castling, en passant, promotion, castling rights and the clocks follow.
 * It does not decide checkmate or draws; it only places pieces.
 */
internal object SanReplay {
    private const val SIZE = 8

    /** [position] after [move], or null when no piece can legally make it. */
    fun play(
        position: ChessPosition,
        move: ChessMove,
    ): ChessPosition? {
        val color = position.activeColor
        if (move.isCastling) return castle(position, kingSide = move.toSquare.startsWith("g"))

        val target = square(move.toSquare) ?: return null
        val board = boardOf(position)
        var played: ChessPosition? = null
        for (rank in 0 until SIZE) {
            for (file in 0 until SIZE) {
                val piece = board[rank][file] ?: continue
                if (piece.color != color || piece.type != move.piece) continue
                if (!matchesDisambiguation(file, rank, move.fromSquare)) continue
                if (!canReach(board, position.enPassantSquare, file, rank, target.first, target.second)) continue
                val next = apply(position, board, file, rank, target.first, target.second, move.promotion)
                if (!isInCheck(boardOf(next), color)) {
                    // SAN names exactly one legal mover; a second would make the move ambiguous.
                    if (played != null) return null
                    played = next
                }
            }
        }
        return played
    }

    private fun square(name: String): Pair<Int, Int>? {
        if (name.length != 2) return null
        val file = name[0] - 'a'
        val rank = name[1] - '1'
        return if (file in 0 until SIZE && rank in 0 until SIZE) file to rank else null
    }

    private fun name(
        file: Int,
        rank: Int,
    ) = "${'a' + file}${rank + 1}"

    private fun boardOf(position: ChessPosition): Array<Array<ChessPiece?>> = Array(SIZE) { rank -> Array(SIZE) { file -> position.pieceAt(file, rank) } }

    private fun matchesDisambiguation(
        file: Int,
        rank: Int,
        hint: String?,
    ): Boolean {
        if (hint.isNullOrEmpty()) return true
        return hint.all { c ->
            when (c) {
                in 'a'..'h' -> c - 'a' == file
                in '1'..'8' -> c - '1' == rank
                else -> true
            }
        }
    }

    private fun canReach(
        board: Array<Array<ChessPiece?>>,
        enPassant: String?,
        fromFile: Int,
        fromRank: Int,
        toFile: Int,
        toRank: Int,
    ): Boolean {
        val piece = board[fromRank][fromFile] ?: return false
        val occupant = board[toRank][toFile]
        if (occupant != null && occupant.color == piece.color) return false
        val dx = toFile - fromFile
        val dy = toRank - fromRank
        return when (piece.type) {
            PieceType.KNIGHT -> (abs(dx) == 1 && abs(dy) == 2) || (abs(dx) == 2 && abs(dy) == 1)
            PieceType.KING -> max(abs(dx), abs(dy)) == 1
            PieceType.ROOK -> (dx == 0 || dy == 0) && clearPath(board, fromFile, fromRank, toFile, toRank)
            PieceType.BISHOP -> abs(dx) == abs(dy) && dx != 0 && clearPath(board, fromFile, fromRank, toFile, toRank)
            PieceType.QUEEN -> (dx == 0 || dy == 0 || abs(dx) == abs(dy)) && clearPath(board, fromFile, fromRank, toFile, toRank)
            PieceType.PAWN -> {
                val forward = if (piece.color == Color.WHITE) 1 else -1
                val startRank = if (piece.color == Color.WHITE) 1 else 6
                when {
                    dx == 0 && dy == forward -> occupant == null
                    dx == 0 && dy == 2 * forward && fromRank == startRank -> occupant == null && board[fromRank + forward][fromFile] == null
                    abs(dx) == 1 && dy == forward -> occupant != null || name(toFile, toRank) == enPassant
                    else -> false
                }
            }
        }
    }

    private fun clearPath(
        board: Array<Array<ChessPiece?>>,
        fromFile: Int,
        fromRank: Int,
        toFile: Int,
        toRank: Int,
    ): Boolean {
        val stepX = (toFile - fromFile).coerceIn(-1, 1)
        val stepY = (toRank - fromRank).coerceIn(-1, 1)
        var file = fromFile + stepX
        var rank = fromRank + stepY
        while (file != toFile || rank != toRank) {
            if (board[rank][file] != null) return false
            file += stepX
            rank += stepY
        }
        return true
    }

    private fun apply(
        position: ChessPosition,
        source: Array<Array<ChessPiece?>>,
        fromFile: Int,
        fromRank: Int,
        toFile: Int,
        toRank: Int,
        promotion: PieceType?,
    ): ChessPosition {
        val board = Array(SIZE) { source[it].copyOf() }
        val piece = board[fromRank][fromFile]!!
        val captured = board[toRank][toFile]
        val isPawn = piece.type == PieceType.PAWN
        val enPassantCapture = isPawn && fromFile != toFile && captured == null
        if (enPassantCapture) board[fromRank][toFile] = null

        val lastRank = if (piece.color == Color.WHITE) SIZE - 1 else 0
        board[toRank][toFile] = if (isPawn && toRank == lastRank) ChessPiece(promotion ?: PieceType.QUEEN, piece.color) else piece
        board[fromRank][fromFile] = null

        var rights = position.castlingRights
        if (piece.type == PieceType.KING) rights = rights.without(piece.color, kingSide = true).without(piece.color, kingSide = false)
        rights = rights.withoutRookOn(fromFile, fromRank).withoutRookOn(toFile, toRank)

        val doublePush = isPawn && abs(toRank - fromRank) == 2
        return position.copy(
            board = board,
            activeColor = position.activeColor.opposite(),
            moveNumber = if (position.activeColor == Color.BLACK) position.moveNumber + 1 else position.moveNumber,
            castlingRights = rights,
            enPassantSquare = if (doublePush) name(fromFile, (fromRank + toRank) / 2) else null,
            halfMoveClock = if (isPawn || captured != null || enPassantCapture) 0 else position.halfMoveClock + 1,
        )
    }

    private fun castle(
        position: ChessPosition,
        kingSide: Boolean,
    ): ChessPosition? {
        val color = position.activeColor
        val rights = position.castlingRights
        val allowed =
            when (color) {
                Color.WHITE -> if (kingSide) rights.whiteKingSide else rights.whiteQueenSide
                Color.BLACK -> if (kingSide) rights.blackKingSide else rights.blackQueenSide
            }
        if (!allowed) return null

        val rank = if (color == Color.WHITE) 0 else SIZE - 1
        val board = boardOf(position)
        val rookFile = if (kingSide) SIZE - 1 else 0
        val between = if (kingSide) 5..6 else 1..3
        if (board[rank][4]?.type != PieceType.KING || board[rank][rookFile]?.type != PieceType.ROOK) return null
        if (between.any { board[rank][it] != null }) return null
        // The king may not castle out of, through, or into check.
        val kingPath = if (kingSide) 4..6 else 2..4
        if (kingPath.any { isAttacked(board, it, rank, color.opposite()) }) return null

        val king = board[rank][4]
        val rook = board[rank][rookFile]
        board[rank][4] = null
        board[rank][rookFile] = null
        board[rank][if (kingSide) 6 else 2] = king
        board[rank][if (kingSide) 5 else 3] = rook

        return position.copy(
            board = board,
            activeColor = color.opposite(),
            moveNumber = if (color == Color.BLACK) position.moveNumber + 1 else position.moveNumber,
            castlingRights = rights.without(color, kingSide = true).without(color, kingSide = false),
            enPassantSquare = null,
            halfMoveClock = position.halfMoveClock + 1,
        )
    }

    private fun isInCheck(
        board: Array<Array<ChessPiece?>>,
        color: Color,
    ): Boolean {
        for (rank in 0 until SIZE) {
            for (file in 0 until SIZE) {
                val piece = board[rank][file]
                if (piece?.type == PieceType.KING && piece.color == color) return isAttacked(board, file, rank, color.opposite())
            }
        }
        return false
    }

    /** True when a piece of [by] attacks the square, whether or not anything stands on it. */
    private fun isAttacked(
        board: Array<Array<ChessPiece?>>,
        file: Int,
        rank: Int,
        by: Color,
    ): Boolean {
        fun at(
            f: Int,
            r: Int,
        ): ChessPiece? = if (f in 0 until SIZE && r in 0 until SIZE) board[r][f] else null

        fun isEnemy(
            piece: ChessPiece?,
            vararg types: PieceType,
        ) = piece != null && piece.color == by && piece.type in types

        val knightJumps = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
        if (knightJumps.any { (dx, dy) -> isEnemy(at(file + dx, rank + dy), PieceType.KNIGHT) }) return true

        for (dx in -1..1) {
            for (dy in -1..1) {
                if ((dx != 0 || dy != 0) && isEnemy(at(file + dx, rank + dy), PieceType.KING)) return true
            }
        }

        // A pawn attacks diagonally forward, so an attacker stands one rank behind, from its side.
        val pawnRank = if (by == Color.WHITE) rank - 1 else rank + 1
        if (isEnemy(at(file - 1, pawnRank), PieceType.PAWN) || isEnemy(at(file + 1, pawnRank), PieceType.PAWN)) return true

        val straight = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val diagonal = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        for ((dx, dy) in straight + diagonal) {
            val slider = if (dx == 0 || dy == 0) PieceType.ROOK else PieceType.BISHOP
            var f = file + dx
            var r = rank + dy
            while (f in 0 until SIZE && r in 0 until SIZE) {
                val piece = board[r][f]
                if (piece != null) {
                    if (isEnemy(piece, slider, PieceType.QUEEN)) return true
                    break
                }
                f += dx
                r += dy
            }
        }
        return false
    }

    private fun CastlingRights.without(
        color: Color,
        kingSide: Boolean,
    ): CastlingRights =
        when {
            color == Color.WHITE && kingSide -> copy(whiteKingSide = false)
            color == Color.WHITE -> copy(whiteQueenSide = false)
            kingSide -> copy(blackKingSide = false)
            else -> copy(blackQueenSide = false)
        }

    /** A rook leaving or captured on its corner takes that side's castling with it. */
    private fun CastlingRights.withoutRookOn(
        file: Int,
        rank: Int,
    ): CastlingRights =
        when {
            file == 0 && rank == 0 -> copy(whiteQueenSide = false)
            file == SIZE - 1 && rank == 0 -> copy(whiteKingSide = false)
            file == 0 && rank == SIZE - 1 -> copy(blackQueenSide = false)
            file == SIZE - 1 && rank == SIZE - 1 -> copy(blackKingSide = false)
            else -> this
        }
}
