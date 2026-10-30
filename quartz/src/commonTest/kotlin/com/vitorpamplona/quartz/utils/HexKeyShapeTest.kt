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

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.toHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Hex.isHex64] reads the first 64 characters and nothing else, so every caller that means "is
 * this a pubkey / event id" wrote `length == 64 && isHex64(…)` beside it. [Hex.isHexKey] is that
 * whole question.
 */
class HexKeyShapeTest {
    private val key = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"

    @Test
    fun isHex64ReadsOnlyThePrefix() {
        assertTrue(Hex.isHex64(key + "zz"), "documented: the total length is not checked")
        assertFalse(Hex.isHex64(key.take(63)))
    }

    @Test
    fun isHexKeyIsExactlySixtyFourHexChars() {
        assertTrue(Hex.isHexKey(key))
        assertTrue(Hex.isHexKey(key.uppercase()))
        assertFalse(Hex.isHexKey(key + "00"))
        assertFalse(Hex.isHexKey(key + "zz"))
        assertFalse(Hex.isHexKey(key.take(63)))
        assertFalse(Hex.isHexKey(key.take(62) + "g0"))
        assertFalse(Hex.isHexKey(key.take(62) + "😀"))
        assertFalse(Hex.isHexKey(null))
        assertFalse(Hex.isHexKey(""))
    }

    @Test
    fun aRelayUrlsHttpAddressSwapsTheSchemeAndKeepsTheRest() {
        assertEquals("https://relay.example.com/", "wss://relay.example.com".normalizeRelayUrl().toHttp())
        assertEquals("http://relay.example.com/", "ws://relay.example.com".normalizeRelayUrl().toHttp())
        assertEquals("https://relay.example.com:4443/path?q=1", "wss://relay.example.com:4443/path?q=1".normalizeRelayUrl().toHttp())
        assertEquals("http://[2001:db8::1]:7777/", "ws://[2001:db8::1]:7777".normalizeRelayUrl().toHttp())
        // The default port is gone from the relay url, so it is gone from the http one too.
        assertEquals("https://relay.example.com/", "wss://relay.example.com:443".normalizeRelayUrl().toHttp())
    }
}
