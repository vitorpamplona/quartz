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
package com.vitorpamplona.quartz.kinds

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ContentFormats.KINDS] against the classes, the same way `SearchableKindsTest` checks
 * `SearchableKinds.ALL`: every kind in the 16-bit space is built through the factory and asked
 * for its format. A class that starts or stops declaring one fails here, naming its kind.
 */
class ContentFormatsTest {
    private fun build(kind: Int): Event = EventFactory.probe(kind)

    @Test
    fun theRecordedMapIsExactlyWhatTheClassesDeclare() {
        val actual =
            (0..65535)
                .mapNotNull { kind -> ContentFormats.of(build(kind))?.let { kind to it } }
                .toMap()
        assertEquals(
            actual,
            ContentFormats.KINDS,
            "ContentFormats.KINDS is stale. Missing or changed: ${actual.entries - ContentFormats.KINDS.entries}; " +
                "no longer declared: ${ContentFormats.KINDS.entries - actual.entries}.",
        )
    }

    @Test
    fun theMapIsSortedByKind() {
        assertEquals(ContentFormats.KINDS.keys.sorted(), ContentFormats.KINDS.keys.toList())
    }

    @Test
    fun theFormatsTheNipsName() {
        // One per NIP, read from the spec texts on 2026-11-25.
        assertEquals(ContentFormat.PLAIN_TEXT, ContentFormats.of(1)) // NIP-10
        assertEquals(ContentFormat.PLAIN_TEXT, ContentFormats.of(14)) // NIP-17
        assertEquals(ContentFormat.PLAIN_TEXT, ContentFormats.of(24)) // NIP-A4
        assertEquals(ContentFormat.PLAIN_TEXT, ContentFormats.of(1111)) // NIP-22
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(30023)) // NIP-23
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(30024)) // NIP-23 drafts
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(1621)) // NIP-34 issues
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(30063)) // NIP-51 release notes
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(30402)) // NIP-99
        assertEquals(ContentFormat.MARKDOWN, ContentFormats.of(54)) // NIP-F4
        assertEquals(ContentFormat.DJOT, ContentFormats.of(30818)) // NIP-54
        assertEquals(ContentFormat.ASCIIDOC, ContentFormats.of(30041)) // NKBIP-01

        // Content that is not prose for a person: a git patch, a reaction, a profile's JSON.
        assertNull(ContentFormats.of(1617))
        assertNull(ContentFormats.of(7))
        assertNull(ContentFormats.of(0))
    }
}
