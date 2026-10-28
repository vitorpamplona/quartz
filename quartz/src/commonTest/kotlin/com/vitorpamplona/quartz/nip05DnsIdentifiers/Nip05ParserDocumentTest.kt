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
package com.vitorpamplona.quartz.nip05DnsIdentifiers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Nip05Parser.parse] reads the documents domains actually serve. */
class Nip05ParserDocumentTest {
    private val parser = Nip05Parser()
    private val bob = "b0635d6a9851d3aed0cd6c495b282167acf761729078d975fc341b22650b07b9"

    @Test
    fun aDocumentWithoutRelaysParses() {
        // Most domains serve only `names`; `relays` is optional in NIP-05.
        val set = parser.parse("""{"names":{"bob":"$bob"}}""")
        assertEquals(mapOf("bob" to bob), set.names)
        assertTrue(set.relays.isEmpty())
    }

    @Test
    fun fieldsBesideNamesAndRelaysAreSkipped() {
        val json = """{"names":{"bob":"$bob"},"relays":{},"nip46":{"relays":["wss://r.example"]},"clink_offer":"noffer1x"}"""
        assertEquals(mapOf("bob" to bob), parser.parse(json).names)
    }

    @Test
    fun anEmptyDocumentHasNoNames() {
        assertTrue(parser.parse("{}").names.isEmpty())
    }

    @Test
    fun toJsonStillWritesEmptyRelays() {
        assertEquals("""{"names":{"bob":"$bob"},"relays":{}}""", parser.toJson(KeyInfoSet(mapOf("bob" to bob))))
    }
}
