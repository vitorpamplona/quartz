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
package com.vitorpamplona.quartz.utils

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.UnrecognizedShape
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every kind `EventFactory` splits by tags builds an [UnrecognizedShape] for a shape it does not
 * type, so a client can tell that the kind's name is not this event's. A bare `d` and no content is
 * no typed shape on any of them.
 */
class UnrecognizedShapeTest {
    private val sharedKinds = listOf(30054, 30100, 30101, 30102, 30104, 30301, 30302, 30405, 30406, 31985, 31986, 38000, 38384, 38385, 38386)

    private fun bare(kind: Int): Event = EventFactory.create("11".repeat(32), "22".repeat(32), 1_700_000_000L, kind, arrayOf(arrayOf("d", "x")), "", "00".repeat(64))

    @Test
    fun aSharedKindsUntypedShapeIsAnUnrecognizedShape() {
        sharedKinds.forEach { kind ->
            val event = bare(kind)
            assertTrue(event is UnrecognizedShape, "kind $kind built ${event::class.simpleName}")
            assertFalse(event is SearchableEvent, "kind $kind")
        }
    }

    @Test
    fun theTypedShapeOfASharedKindIsNot() {
        sharedKinds.forEach { kind -> assertFalse(EventFactory.probe(kind) is UnrecognizedShape, "kind $kind") }
    }
}
