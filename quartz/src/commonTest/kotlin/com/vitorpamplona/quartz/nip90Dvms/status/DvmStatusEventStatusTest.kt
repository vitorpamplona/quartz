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
package com.vitorpamplona.quartz.nip90Dvms.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** NIP-90 job feedback: `["status", <status>, <extra-info>]`, with `content` a separate payload. */
class DvmStatusEventStatusTest {
    private val pk = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val eventId = "43575072239da152afe3d7b5c70ed2beb48db2b10e60c60da45229c09c877d2a"
    private val id = "00".repeat(32)
    private val sig = "00".repeat(64)

    private fun feedback(
        tags: Array<Array<String>>,
        content: String,
    ) = DvmStatusEvent(id, pk, 1L, tags + arrayOf(arrayOf("e", eventId)), content, sig)

    @Test
    fun extraInfoIsTheTagsThirdSlotEvenWithAPartialResultInTheContent() {
        val event = feedback(arrayOf(arrayOf("status", "partial", "Halfway there")), "first 10 results")

        assertEquals("partial", event.status()?.code)
        assertEquals("Halfway there", event.status()?.description)
    }

    @Test
    fun encryptedContentIsNeverTheExtraInfo() {
        val event = feedback(arrayOf(arrayOf("status", "processing"), arrayOf("encrypted")), "BE2Y4xvS6HIY7TozIgbEl3sAHkdZoXyLRRkZv4fLPh3R?iv=abc")

        assertEquals("processing", event.status()?.code)
        assertEquals("", event.status()?.description)
    }

    @Test
    fun extraInfoIsNullWhenTheTagHasNone() {
        assertEquals("Halfway there", feedback(arrayOf(arrayOf("status", "partial", "Halfway there")), "").status()?.extraInfo)
        assertNull(feedback(arrayOf(arrayOf("status", "processing")), "some content").status()?.extraInfo)
        assertNull(feedback(arrayOf(arrayOf("status", "processing", "")), "").status()?.extraInfo)
        assertNull(feedback(arrayOf(arrayOf("status", "")), "").status())
    }

    /** The deprecated reader keeps its old content-as-description behaviour for callers that rely on it. */
    @Suppress("DEPRECATION")
    @Test
    fun deprecatedContentReaderStillPrefersTheContent() {
        val tags = arrayOf(arrayOf("status", "partial", "Halfway there"))

        assertEquals("first 10 results", tags.status("first 10 results")?.description)
        assertEquals("Halfway there", tags.status("")?.description)
    }
}
