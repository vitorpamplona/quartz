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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

/** One relay must normalize to one url, whatever port spelling or trailing junk it arrives with. */
class RelayUrlNormalizerPortTest {
    private fun norm(url: String) = RelayUrlNormalizer.normalizeOrNull(url)?.url

    @Test
    fun defaultPortsNormalizeToTheSameRelay() {
        assertEquals("wss://nos.lol/", norm("wss://nos.lol:443"))
        assertEquals("wss://nos.lol/", norm("wss://nos.lol:443/"))
        assertEquals("ws://nos.lol/", norm("ws://nos.lol:80"))
        assertEquals("wss://nos.lol/", norm("https://nos.lol:443"))
        assertEquals(norm("wss://nos.lol"), norm("wss://nos.lol:443"))
    }

    @Test
    fun nonDefaultPortsAreKept() {
        assertEquals("ws://nos.lol:443/", norm("ws://nos.lol:443"))
        assertEquals("wss://nos.lol:80/", norm("wss://nos.lol:80"))
        assertEquals("wss://nos.lol:7777/", norm("wss://nos.lol:7777"))
    }

    @Test
    fun trailingEncodedSpacesKeepPortAndPathDigits() {
        assertEquals("wss://relay.example.com:8080/", norm("wss://relay.example.com:8080%20"))
        assertEquals("ws://localhost:3000/", norm("ws://localhost:3000%20"))
        assertEquals("wss://host.com/v2", norm("wss://host.com/v2%20"))
        assertEquals("wss://nostr.mom/", norm("wss://nostr.mom%20%20"))
    }
}
