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
package com.vitorpamplona.quartz.nip57Zaps.validate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LnurlEndpointInfoParseTest {
    private val key = "b0635d6a9851d3aed0cd6c495b282167acf761729078d975fc341b22650b07b9"

    @Test
    fun aZapEndpoint() {
        val json = """{"tag":"payRequest","callback":"https://w.example/cb","allowsNostr":true,"nostrPubkey":"${key.uppercase()}"}"""
        assertEquals(LnurlEndpointInfo(key, true), LnurlEndpointInfo.parse(json))
    }

    @Test
    fun allowsNostrMustBeTheJsonBooleanTrue() {
        assertEquals(false, LnurlEndpointInfo.parse("""{"allowsNostr":"true","nostrPubkey":"$key"}""")!!.allowsNostr)
        assertEquals(false, LnurlEndpointInfo.parse("""{"allowsNostr":false,"nostrPubkey":"$key"}""")!!.allowsNostr)
        assertEquals(false, LnurlEndpointInfo.parse("""{"nostrPubkey":"$key"}""")!!.allowsNostr)
    }

    @Test
    fun aKeyThatIsNotSixtyFourHexIsDropped() {
        assertNull(LnurlEndpointInfo.parse("""{"allowsNostr":true,"nostrPubkey":"npub1abc"}""")!!.nostrPubkey)
        assertNull(LnurlEndpointInfo.parse("""{"allowsNostr":true,"nostrPubkey":"${key}00"}""")!!.nostrPubkey)
        assertNull(LnurlEndpointInfo.parse("""{"allowsNostr":true,"nostrPubkey":"${key.take(63)}"}""")!!.nostrPubkey)
        assertNull(LnurlEndpointInfo.parse("""{"allowsNostr":true,"nostrPubkey":42}""")!!.nostrPubkey)
    }

    @Test
    fun anErrorOrNonObjectIsNull() {
        assertNull(LnurlEndpointInfo.parse("""{"status":"ERROR","reason":"no such user"}"""))
        assertNull(LnurlEndpointInfo.parse("[]"))
        assertNull(LnurlEndpointInfo.parse("<html>"))
    }
}
