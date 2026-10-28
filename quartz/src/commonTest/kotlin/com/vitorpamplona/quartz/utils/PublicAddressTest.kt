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

class PublicAddressTest {
    private fun v4(vararg o: Int) = ByteArray(4) { o[it].toByte() }

    private fun v6(text: String) = Ipv6.parse(text)!!

    @Test
    fun publicIpv4() {
        assertTrue(PublicAddress.isPublic(v4(1, 1, 1, 1)))
        assertTrue(PublicAddress.isPublic(v4(8, 8, 8, 8)))
        assertTrue(PublicAddress.isPublic(v4(172, 32, 0, 1)))
        assertTrue(PublicAddress.isPublic(v4(100, 128, 0, 1)))
    }

    @Test
    fun internalIpv4() {
        listOf(
            v4(0, 0, 0, 0),
            v4(10, 1, 2, 3),
            v4(100, 64, 0, 1),
            v4(127, 0, 0, 1),
            v4(127, 255, 255, 254),
            v4(169, 254, 169, 254),
            v4(172, 16, 0, 1),
            v4(172, 31, 255, 255),
            v4(192, 0, 0, 1),
            v4(192, 0, 2, 1),
            v4(192, 168, 1, 1),
            v4(198, 18, 0, 1),
            v4(198, 51, 100, 1),
            v4(203, 0, 113, 1),
            v4(224, 0, 0, 1),
            v4(255, 255, 255, 255),
        ).forEach { assertFalse(PublicAddress.isPublic(it), it.joinToString(".") { b -> (b.toInt() and 0xFF).toString() }) }
    }

    @Test
    fun publicIpv6() {
        assertTrue(PublicAddress.isPublic(v6("2606:4700:4700::1111")))
        assertTrue(PublicAddress.isPublic(v6("2a00:1450:4001:80b::200e")))
    }

    @Test
    fun internalIpv6() {
        listOf("::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1", "2001:db8::1", "200::1", "::127.0.0.1")
            .forEach { assertFalse(PublicAddress.isPublic(v6(it)), it) }
    }

    @Test
    fun ipv6ThatCarriesAnIpv4AddressIsJudgedByIt() {
        assertFalse(PublicAddress.isPublic(v6("::ffff:127.0.0.1")), "mapped")
        assertFalse(PublicAddress.isPublic(v6("::ffff:169.254.169.254")), "mapped")
        assertTrue(PublicAddress.isPublic(v6("::ffff:1.1.1.1")), "mapped")
        assertFalse(PublicAddress.isPublic(v6("64:ff9b::10.0.0.1")), "NAT64")
        assertTrue(PublicAddress.isPublic(v6("64:ff9b::1.1.1.1")), "NAT64")
        assertFalse(PublicAddress.isPublic(v6("2002:7f00:1::1")), "6to4 of 127.0.0.1")
        assertTrue(PublicAddress.isPublic(v6("2002:0101:0101::1")), "6to4 of 1.1.1.1")
        // Teredo: the client address is stored inverted. ~127.0.0.1 = 80ff:fffe.
        assertFalse(PublicAddress.isPublic(v6("2001:0:4136:e378:8000:63bf:80ff:fffe")), "Teredo of 127.0.0.1")
        assertTrue(PublicAddress.isPublic(v6("2001:0:4136:e378:8000:63bf:fefe:fefe")), "Teredo of 1.1.1.1")
    }

    @Test
    fun otherLengthsAreNotPublic() {
        assertFalse(PublicAddress.isPublic(ByteArray(0)))
        assertFalse(PublicAddress.isPublic(ByteArray(5)))
    }
}
