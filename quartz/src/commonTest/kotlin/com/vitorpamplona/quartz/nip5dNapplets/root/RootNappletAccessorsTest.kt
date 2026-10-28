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
package com.vitorpamplona.quartz.nip5dNapplets.root

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootNappletAccessorsTest {
    private val zero = "00".repeat(32)
    private val artifact = "11".repeat(32)

    private val fotohop =
        """{"id":"72e97779aab2c5d129774fe1bf3f0206109af0d54943edd9f444a33c81db3bba","pubkey":"e771af0b05c8e95fcdf6feb3500544d2fb1ccd384788e9f490bb3ee28e8ed66f","created_at":1793099370,"kind":15129,"tags":[["x","6faaad876de6984e100fc4937f8db88854ef9c31f6b3ad6ed2bdbbac59141e8c"],["R","fs"],["O","keys"],["O","storage"],["O","theme"],["title","Fotohop"],["server","https://blssm.us"],["client","napplet.run"]],"content":"Layered image editor with drawing, text, cropping, adjustments, and PNG export","sig":"7a029d1ddb45bddd2082df96a44c54c4fe6810a49cb0b3f989aaf22afc87fb340ca2e6542ddb36a75de8aaf1c9a3fe7373ff9d19f262eb2fa7d2feaa29c09a60"}"""

    @Test
    fun readsAPublishedRootNapplet() {
        val event = assertIs<RootNappletEvent>(Event.fromJson(fotohop))
        assertTrue(event.isValid())
        assertEquals("6faaad876de6984e100fc4937f8db88854ef9c31f6b3ad6ed2bdbbac59141e8c", event.artifact())
        assertEquals("Layered image editor with drawing, text, cropping, adjustments, and PNG export", event.description())
        assertEquals(listOf("fs"), event.required())
        assertEquals(listOf("keys", "storage", "theme"), event.optional())
        assertEquals("Fotohop", event.title())
    }

    @Test
    fun aBlankDescriptionIsInvalid() {
        val event = RootNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact)), "  ", zero)
        assertEquals(artifact, event.artifact())
        assertFalse(event.isValid())
    }

    @Test
    fun aRootNappletHasNoIdentifier() {
        val event = RootNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact), arrayOf("d", "root")), "d", zero)
        assertFalse(event.isValid())
    }

    @Test
    fun twoArtifactsAreInvalid() {
        val event = RootNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact), arrayOf("x", "33".repeat(32))), "d", zero)
        assertNull(event.artifact())
        assertNull(event.artifactFile())
        assertFalse(event.isValid())
    }

    @Test
    fun buildRoundTrips() {
        val template = RootNappletEvent.build(artifactHash = artifact, description = "An editor", required = listOf("relay", "relay"))
        val event = RootNappletEvent(zero, zero, template.createdAt, template.tags, template.content, zero)
        assertEquals(RootNappletEvent.KIND, template.kind)
        assertTrue(event.isValid())
        assertEquals(listOf("relay"), event.required())
        assertTrue(event.servers().isEmpty())
        assertNull(event.title())
        assertNull(event.icon())
    }

    @Test
    fun indexesTitleAndDescription() {
        val template = RootNappletEvent.build(artifactHash = artifact, description = "An editor", title = "Edit")
        val event = RootNappletEvent(zero, zero, template.createdAt, template.tags, template.content, zero)
        assertEquals("Edit\nAn editor", event.indexableContent())
    }
}
