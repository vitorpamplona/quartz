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
package com.vitorpamplona.quartz.nip01Core.relay.client.single

/**
 * How a relay client's socket session ended, and what it had sent on that socket.
 *
 * Read it from [IRelayClient.lastDisconnect] inside
 * [com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener.onDisconnected]:
 * the client sets it before that callback runs, and the next session's end replaces it.
 */
class RelayDisconnect(
    val side: Side,
    /** The close frame's code for [Side.RELAY]; the HTTP status of a refused upgrade for [Side.TRANSPORT]. */
    val code: Int?,
    /** The close frame's reason, the reason passed to [IRelayClient.disconnect], or the failure's message. */
    val reason: String?,
    /** The failure's type for [Side.TRANSPORT], e.g. `SocketTimeoutException`. */
    val errorType: String?,
    val atMillis: Long,
    /** When the socket opened; null when it never did. */
    val openedAtMillis: Long?,
    val sent: SentFrames,
) {
    /** Who ended the session. */
    enum class Side {
        /** The relay sent a close frame. */
        RELAY,

        /** This client closed it, through [IRelayClient.disconnect]. */
        CLIENT,

        /** It died with no close frame: a reset, an EOF, a failed dial, handshake or ping. */
        TRANSPORT,
    }

    /** How long the socket was open before it ended; null when it never opened. */
    val openForMillis: Long? get() = openedAtMillis?.let { atMillis - it }

    override fun toString(): String =
        buildString {
            append(side.name.lowercase())
            code?.let { append(' ').append(it) }
            errorType?.let { append(' ').append(it) }
            reason?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it).append('"') }
            append(openForMillis?.let { " after ${it}ms open" } ?: " before it opened")
            append("; ").append(sent)
        }
}

/**
 * The frames one socket session sent: how many, the largest by UTF-8 size (what a relay's
 * payload limit counts), and the last. Labels are the frames' NIP-01 verbs (`REQ`, `NEG-OPEN`, ...).
 */
class SentFrames(
    val count: Long,
    val largestBytes: Int,
    val largestLabel: String?,
    val lastBytes: Int,
    val lastLabel: String?,
) {
    override fun toString(): String = if (count == 0L) "nothing sent" else "$count frame(s) sent, largest $largestBytes bytes $largestLabel, last $lastBytes bytes $lastLabel"

    companion object {
        val NONE = SentFrames(0, 0, null, 0, null)
    }
}
