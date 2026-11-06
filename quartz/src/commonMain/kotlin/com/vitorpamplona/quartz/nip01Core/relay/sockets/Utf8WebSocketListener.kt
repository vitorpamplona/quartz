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
package com.vitorpamplona.quartz.nip01Core.relay.sockets

import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message

/**
 * A [WebSocketListener] that reads a frame straight from its UTF-8 bytes, so a socket holding
 * the bytes never builds the frame's text. The socket calls [decodeUtf8] on its reader thread,
 * while the bytes are still valid (it reuses its buffer for the next frame), then
 * [onDecodedMessage] in order, where it would have called [onMessage]. A socket that has only
 * text keeps calling [onMessage].
 *
 * The relay client's own listener implements it (`BasicRelayClient`); a socket of your own can
 * call it the same way when it reads frames as bytes.
 */
interface Utf8WebSocketListener : WebSocketListener {
    /**
     * The message in [length] bytes of [bytes] from [offset], or null to drop the frame
     * (unparseable, or a forged copy the decoder caught). Must not keep [bytes].
     */
    fun decodeUtf8(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Message?

    /** A message [decodeUtf8] returned, delivered in order; [frameBytes] is its frame's size. */
    suspend fun onDecodedMessage(
        msg: Message,
        frameBytes: Int,
    )
}
