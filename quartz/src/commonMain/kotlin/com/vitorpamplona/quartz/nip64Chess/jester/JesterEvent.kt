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
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.JsonMapper
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUserIds
import com.vitorpamplona.quartz.nip64Chess.Color
import com.vitorpamplona.quartz.nip64Chess.GameResult
import com.vitorpamplona.quartz.nip64Chess.GameTermination
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Jester Chess Event (Kind 30)
 *
 * Unified event class for all Jester chess messages.
 * The content.kind field determines the event type:
 * - 0: Game start/challenge
 * - 1: Move
 * - 2: Chat
 */
@Immutable
class JesterEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, JesterProtocol.KIND, tags, content, sig),
    EventHintProvider,
    PubKeyHintProvider {
    // `e` tags are the game's start event and the move it follows; a start event's own `e` is
    // the START_POSITION_HASH constant, which is a position digest, not an event to fetch.
    override fun eventHints(): List<EventIdHint> = tags.mapNotNull { tag -> ETag.parseAsHint(tag)?.takeIf { it.eventId != JesterProtocol.START_POSITION_HASH } }

    /** `ROOT`: the game's start event; `PARENT`: the move this one follows (`e`). */
    override fun linkedEventIds(): List<HexKey> = gameEventIds()

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    /** `OPPONENT`: the other player of a private game (`p`). */
    override fun linkedPubKeys(): List<HexKey> = opponentKeys()

    /** The `e` ids, in tag order (the start event, then the move this one follows), minus START_POSITION_HASH. */
    fun gameEventIds(): List<HexKey> = tags.mapNotNull { tag -> ETag.parseId(tag)?.takeIf { it != JesterProtocol.START_POSITION_HASH } }

    /** Every 64-char `p` key, in tag order. Jester writes one; [opponentPubkey] reads the first `p` unvalidated. */
    fun opponentKeys(): List<HexKey> = tags.taggedUserIds()

    private val parsedContent: JesterContent? by lazy {
        try {
            JsonMapper.fromJson<JesterContent>(content)
        } catch (e: Exception) {
            null
        }
    }

    /** Get the content kind (0=start, 1=move, 2=chat) */
    fun contentKind(): Int? = parsedContent?.kind

    /** Check if this is a game start event */
    fun isStartEvent(): Boolean = parsedContent?.kind == JesterProtocol.CONTENT_KIND_START

    /** Check if this is a move event */
    fun isMoveEvent(): Boolean = parsedContent?.kind == JesterProtocol.CONTENT_KIND_MOVE

    /** Get the FEN position */
    fun fen(): String? = parsedContent?.fen

    /** Get the latest move (SAN notation) */
    fun move(): String? = parsedContent?.move

    /** Get the full move history */
    fun history(): List<String> = parsedContent?.history ?: emptyList()

    /** Get the nonce (for start events) */
    fun nonce(): String? = parsedContent?.nonce

    /** Get the player color choice (for start events) */
    fun playerColor(): Color? =
        parsedContent?.playerColor?.let {
            if (it == "white") Color.WHITE else Color.BLACK
        }

    /** Get the game result (for end events) */
    fun result(): String? = parsedContent?.result

    /** Get the termination reason (for end events) */
    fun termination(): String? = parsedContent?.termination

    /** Get the start event ID (first e-tag) */
    fun startEventId(): String? = tags.firstGameEventRef()

    /** Get the head/parent move ID (second e-tag, for moves) */
    fun headEventId(): String? = tags.gameEventRefs().getOrNull(1)

    /** Get opponent pubkey (p-tag, for private games) */
    fun opponentPubkey(): String? = tags.jesterOpponent()

    /** Get all e-tags */
    fun eTags(): List<String> = tags.gameEventRefs()

    companion object {
        const val KIND = JesterProtocol.KIND

        /**
         * Build a game start event (open challenge)
         *
         * @param playerColor Color the challenger wants to play
         * @param nonce Unique nonce for this game
         * @param createdAt Event timestamp
         */
        fun buildStart(
            playerColor: Color,
            nonce: String = generateNonce(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<JesterEvent>.() -> Unit = {},
        ): EventTemplate<JesterEvent> {
            val content =
                JesterContent(
                    kind = JesterProtocol.CONTENT_KIND_START,
                    fen = JesterProtocol.FEN_START,
                    history = emptyList(),
                    nonce = nonce,
                    playerColor = if (playerColor == Color.WHITE) "white" else "black",
                )
            return eventTemplate(KIND, JsonMapper.toJson(content), createdAt) {
                // Reference the standard start position for game discovery
                gameEventRef(JesterProtocol.START_POSITION_HASH)
                initializer()
            }
        }

        /**
         * Build a private game start event (direct challenge)
         *
         * @param opponentPubkey Pubkey of the challenged player
         * @param playerColor Color the challenger wants to play
         * @param nonce Unique nonce for this game
         * @param createdAt Event timestamp
         */
        fun buildPrivateStart(
            opponentPubkey: String,
            playerColor: Color,
            nonce: String = generateNonce(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<JesterEvent>.() -> Unit = {},
        ): EventTemplate<JesterEvent> {
            val content =
                JesterContent(
                    kind = JesterProtocol.CONTENT_KIND_START,
                    fen = JesterProtocol.FEN_START,
                    history = emptyList(),
                    nonce = nonce,
                    playerColor = if (playerColor == Color.WHITE) "white" else "black",
                )
            return eventTemplate(KIND, JsonMapper.toJson(content), createdAt) {
                // Reference the standard start position
                gameEventRef(JesterProtocol.START_POSITION_HASH)
                // Tag the opponent for notifications (only if valid pubkey)
                if (opponentPubkey.isNotEmpty() && opponentPubkey.length == 64) {
                    jesterOpponent(opponentPubkey)
                }
                initializer()
            }
        }

        /**
         * Build a move event
         *
         * @param startEventId ID of the game start event
         * @param headEventId ID of the previous move (or start event for first move)
         * @param move The move in SAN notation (e.g., "e4", "Nf3")
         * @param fen Resulting board position in FEN
         * @param history Complete move history including this move
         * @param opponentPubkey Opponent's pubkey for notifications
         * @param createdAt Event timestamp
         */
        fun buildMove(
            startEventId: String,
            headEventId: String,
            move: String,
            fen: String,
            history: List<String>,
            opponentPubkey: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<JesterEvent>.() -> Unit = {},
        ): EventTemplate<JesterEvent> {
            val content =
                JesterContent(
                    kind = JesterProtocol.CONTENT_KIND_MOVE,
                    fen = fen,
                    move = move,
                    history = history,
                )
            return eventTemplate(KIND, JsonMapper.toJson(content), createdAt) {
                // e-tags: [startId, headId]
                gameEventRef(startEventId)
                gameEventRef(headEventId)
                // Tag opponent for notifications (only if valid pubkey)
                if (opponentPubkey.isNotEmpty() && opponentPubkey.length == 64) {
                    jesterOpponent(opponentPubkey)
                }
                initializer()
            }
        }

        /**
         * Build a game end move (includes result in content)
         *
         * This is a move event with additional result/termination fields.
         * Used when the game ends (checkmate, resignation, draw).
         */
        fun buildEndMove(
            startEventId: String,
            headEventId: String,
            move: String?,
            fen: String,
            history: List<String>,
            opponentPubkey: String,
            result: GameResult,
            termination: GameTermination,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<JesterEvent>.() -> Unit = {},
        ): EventTemplate<JesterEvent> {
            val content =
                JesterContent(
                    kind = JesterProtocol.CONTENT_KIND_MOVE,
                    fen = fen,
                    move = move,
                    history = history,
                    result = result.notation,
                    termination = termination.name.lowercase(),
                )
            return eventTemplate(KIND, JsonMapper.toJson(content), createdAt) {
                gameEventRef(startEventId)
                gameEventRef(headEventId)
                // Tag opponent for notifications (only if valid pubkey)
                if (opponentPubkey.isNotEmpty() && opponentPubkey.length == 64) {
                    jesterOpponent(opponentPubkey)
                }
                initializer()
            }
        }

        private fun generateNonce(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..8).map { chars.random() }.joinToString("")
        }
    }
}

/**
 * Helper to check if an event is a Jester chess event
 */
fun Event.isJesterEvent(): Boolean = kind == JesterProtocol.KIND

/**
 * Helper to check if an event is a Jester start event
 */
fun Event.isJesterStartEvent(): Boolean {
    if (kind != JesterProtocol.KIND) return false
    return try {
        val content = JsonMapper.fromJson<JesterContent>(this.content)
        content.kind == JesterProtocol.CONTENT_KIND_START && content.history.isEmpty()
    } catch (e: Exception) {
        false
    }
}

/**
 * Helper to check if an event is a Jester move event
 */
fun Event.isJesterMoveEvent(): Boolean {
    if (kind != JesterProtocol.KIND) return false
    return try {
        val content = JsonMapper.fromJson<JesterContent>(this.content)
        content.kind == JesterProtocol.CONTENT_KIND_MOVE &&
            content.history.isNotEmpty() &&
            tags.gameEventRefCount() == 2
    } catch (e: Exception) {
        false
    }
}

/**
 * Convert a raw Event to JesterEvent if valid
 */
fun Event.toJesterEvent(): JesterEvent? {
    if (kind != JesterProtocol.KIND) return null
    return JesterEvent(id, pubKey, createdAt, tags, content, sig)
}
