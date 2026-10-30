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
package com.vitorpamplona.quartz.nip01Core.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [addressTagOrNull] / [addressOrNull] dispatch on the KIND, not on the runtime class: a plain
 * [Event] read off the wire (or rebuilt by a store that does not run `EventFactory`) gets the same
 * address its typed subclass would.
 */
class EventAddressOrNullTest {
    private val pubKey = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"

    private fun event(
        kind: Int,
        tags: Array<Array<String>> = emptyArray(),
    ) = Event("a".repeat(64), pubKey, 1L, kind, tags, "", "b".repeat(128))

    @Test
    fun replaceableUsesTheFixedEmptyDTagEvenWithAStrayOne() {
        assertEquals("0:$pubKey:", event(0, arrayOf(arrayOf("d", "stray"))).addressTagOrNull())
        assertEquals("3:$pubKey:", event(3).addressTagOrNull())
        assertEquals("10002:$pubKey:", event(10002).addressTagOrNull())
    }

    @Test
    fun addressableUsesItsFirstDTag() {
        val tags = arrayOf(arrayOf("d", "first"), arrayOf("d", "second"))
        assertEquals("30023:$pubKey:first", event(30023, tags).addressTagOrNull())
        assertEquals(Address(30023, pubKey, "first"), event(30023, tags).addressOrNull())
    }

    @Test
    fun addressableWithoutADTagOrWithAnEmptyOneAddressesTheEmptyD() {
        assertEquals("30000:$pubKey:", event(30000).addressTagOrNull())
        // A one-element d tag has no value; it is skipped like a missing tag.
        assertEquals("30000:$pubKey:", event(30000, arrayOf(arrayOf("d"))).addressTagOrNull())
    }

    @Test
    fun regularAndEphemeralKindsHaveNoAddress() {
        assertNull(event(1, arrayOf(arrayOf("d", "x"))).addressTagOrNull())
        assertNull(event(5).addressOrNull())
        assertNull(event(20001).addressTagOrNull())
        assertNull(event(40000).addressTagOrNull())
    }
}
