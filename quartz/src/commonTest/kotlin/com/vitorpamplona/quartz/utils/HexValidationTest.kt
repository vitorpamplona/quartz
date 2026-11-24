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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexValidationTest {
    private val key = "0123456789abcdefABCDEF".padEnd(64, 'a')

    @Test
    fun isHexRejectsWithoutThrowing() {
        assertTrue(Hex.isHex(key))
        assertFalse(Hex.isHex("zz"))
        assertFalse(Hex.isHex("😀😀"))
        assertFalse(Hex.isHex("Āa"))
        assertFalse(Hex.isHex("abc"))
    }

    @Test
    fun isHex64RejectsShortAndNonLatinInput() {
        assertTrue(Hex.isHex64(key))
        assertFalse(Hex.isHex64(key.substring(0, 63)))
        assertFalse(Hex.isHex64(""))
        assertFalse(Hex.isHex64("Ā" + key.substring(1)))
        assertFalse(Hex.isHex64(key.substring(0, 63) + "g"))
    }
}
