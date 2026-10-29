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
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** [Ipv4.parseInetAton] reads every spelling a resolver dials, as `inet_aton` does. */
class Ipv4InetAtonTest {
    private fun parse(s: String) = Ipv4.parseInetAton(s, 0, s.length)

    private val loopback = byteArrayOf(127, 0, 0, 1)

    @Test
    fun everySpellingOfLoopbackIsLoopback() {
        listOf("127.0.0.1", "2130706433", "0x7f000001", "0X7F000001", "0x7f.0.0.1", "0177.0.0.1", "127.1", "127.0.1", "017700000001")
            .forEach { assertContentEquals(loopback, parse(it), it) }
    }

    @Test
    fun theLastPartFillsTheRemainingBytes() {
        assertContentEquals(byteArrayOf(10, 0, 1, 2), parse("10.258"))
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 5), parse("192.168.261"))
        assertContentEquals(byteArrayOf(100, 100, 10, 10), parse("100.100.2570"))
    }

    @Test
    fun namesAndOutOfRangeValuesAreNotLiterals() {
        listOf(
            "nos.lol",
            "0x10.host",
            "0x-nostr-relay.fly.dev",
            "1.2.3.4.nip.io",
            "256.0.0.1",
            "1.2.3.4.5",
            "4294967296",
            "08.1.1.1", // 8 is not an octal digit
            "0x",
            "1..2",
            "1.2.3.",
            "",
            "١٢٧.0.0.1", // Arabic-Indic digits: no resolver reads them
        ).forEach { assertNull(parse(it), it) }
    }
}
