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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The hex fallbacks decoded non-hex chars as 0xF.., importing a key the user never typed. */
class Nip19KeyDecodeValidationTest {
    private val hex = "0".repeat(63) + "1"

    @Test
    fun aTypoInAHexPrivateKeyIsNotAKey() {
        assertEquals(hex, decodePrivateKeyAsHexOrNull(hex))
        assertNull(decodePrivateKeyAsHexOrNull("O" + hex.substring(1)), "letter O for zero imported f000…0001")
    }

    @Test
    fun nonHexIsNotAPublicKeyOrEventId() {
        assertNull(decodePublicKeyAsHexOrNull("z".repeat(64)))
        assertNull(decodeEventIdAsHexOrNull("g".repeat(64)))
        assertEquals(hex, decodeEventIdAsHexOrNull(hex))
        assertFailsWith<IllegalArgumentException> { decodePublicKey("z".repeat(64)) }
    }

    @Test
    fun aFollowOfANonHexKeyIsDropped() {
        assertNull(ContactTag.parseValid(arrayOf("p", "z".repeat(64))))
    }
}
