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
package com.vitorpamplona.quartz.nip17Dm.files

import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A kind-15 file message links the message it replies to (`e`), as a kind-14 message does. */
class ChatMessageEncryptedFileHeaderHintProviderTest {
    private val author = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val recipient = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val parent = "43575072239da152afe3d7b5c70ed2beb48db2b10e60c60da45229c09c877d2a"
    private val relay = "wss://relay.damus.io/"

    private fun header(vararg tags: Array<String>) = ChatMessageEncryptedFileHeaderEvent("00".repeat(32), author, 1, arrayOf(arrayOf("p", recipient), *tags), "https://files.example.com/blob", "00".repeat(64))

    @Test
    fun replyIsLinkedWithItsHint() {
        val event = header(arrayOf("e", parent, relay), arrayOf("x", "ab".repeat(32)))
        assertIs<EventHintProvider>(event)
        assertEquals(listOf(parent), event.replyTo())
        assertEquals(listOf(parent), event.linkedEventIds())
        assertEquals(listOf(parent), event.eventHints().map { it.eventId })
        assertEquals(listOf(relay), event.eventHints().map { it.relay.url })
        // The base still links the recipients.
        assertEquals(listOf(recipient), event.linkedPubKeys())
    }

    @Test
    fun aHeaderThatRepliesToNothingLinksNoEvent() {
        val event = header()
        assertEquals(emptyList(), event.linkedEventIds())
        assertEquals(emptyList(), event.eventHints())
    }
}
