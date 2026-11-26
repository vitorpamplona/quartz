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
package com.vitorpamplona.quartz.nip30CustomEmoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NIP-30: `["emoji", <shortcode>, <image-url>, <emoji-set-address>]`, the shortcode made of only
 * alphanumeric characters, hyphens and underscores. The invalid shapes below were all seen on
 * nos.lol, relay.primal.net and nostr.mom (kinds 1, 7, 10030 and 30030, sampled 2026-11-25).
 */
class EmojiUrlTagParseTest {
    private val url = "https://gleasonator.com/emoji/Gleasonator/soapbox.png"

    @Test
    fun parsesWellFormedTags() {
        assertEquals(EmojiUrlTag("soapbox", url), EmojiUrlTag.parse(arrayOf("emoji", "soapbox", url)))
        assertEquals("Pepe_laugh-2", EmojiUrlTag.parse(arrayOf("emoji", "Pepe_laugh-2", url))?.code)
        assertEquals("http://example.com/a.png", EmojiUrlTag.parse(arrayOf("emoji", "a", "http://example.com/a.png"))?.url)
        assertEquals("data:image/webp;base64,UklGRrYBAABXRUJQ", EmojiUrlTag.parse(arrayOf("emoji", "a", "data:image/webp;base64,UklGRrYBAABXRUJQ"))?.url)
    }

    @Test
    fun rejectsShortcodesOutsideTheSpecAlphabet() {
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "pepe laugh", url)))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "A+", url)))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", ":glag:", url)))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "🐦", url)))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "", url)))
    }

    @Test
    fun rejectsTagsWithoutAnImageUrl() {
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "soapbox", "")))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "soapbox", "soapbox.png")))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "soapbox", " https://example.com/a.png")))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "soapbox", "javascript:alert(1)")))
        assertNull(EmojiUrlTag.parse(arrayOf("emoji", "soapbox")))
    }
}
