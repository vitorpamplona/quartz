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
package com.vitorpamplona.quartz.nip92IMeta

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IMetaTagRoundTripTest {
    private val tag = arrayOf("imeta", "url https://x.com/a.png", "m image/png", "dim 10x20")

    @Test
    fun theTagNameIsNotAProperty() {
        val parsed = IMetaTag.parse(tag)!!.single()
        assertEquals("https://x.com/a.png", parsed.url)
        assertEquals(mapOf("m" to listOf("image/png"), "dim" to listOf("10x20")), parsed.properties)
    }

    @Test
    fun parsingAndWritingGivesTheSameTag() {
        assertContentEquals(tag, IMetaTag.parse(tag)!!.single().toTagArray())
    }
}
