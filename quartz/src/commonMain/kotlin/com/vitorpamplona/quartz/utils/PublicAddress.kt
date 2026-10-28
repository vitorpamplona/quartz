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

/**
 * Whether an address is on the public internet: the question to ask before fetching a URL that
 * came from someone else's event (a NIP-05 domain, a lightning address, an image). Without it, a
 * profile can point a client at the user's router or a server at its own cloud metadata service.
 *
 * Deny-by-default: IPv6 counts only in global unicast `2000::/3`, minus its special-purpose
 * blocks; an address that embeds an IPv4 one (mapped, NAT64, 6to4, Teredo) is judged by that one.
 */
object PublicAddress {
    /** [bytes] is 4 (IPv4) or 16 (IPv6) bytes, as `InetAddress.getAddress()` returns them. */
    fun isPublic(bytes: ByteArray): Boolean =
        when (bytes.size) {
            4 -> isPublicV4(bytes, 0)
            16 -> isPublicV6(bytes)
            else -> false
        }

    private fun isPublicV4(
        b: ByteArray,
        at: Int,
    ): Boolean {
        val o0 = octet(b, at)
        val o1 = octet(b, at + 1)
        val o2 = octet(b, at + 2)
        return !(
            o0 == 0 || // "this" network
                o0 == 10 || // private
                o0 == 127 || // loopback
                o0 >= 224 || // multicast, reserved, broadcast
                (o0 == 100 && o1 in 64..127) || // carrier-grade NAT
                (o0 == 169 && o1 == 254) || // link-local
                (o0 == 172 && o1 in 16..31) || // private
                (o0 == 192 && o1 == 168) || // private
                (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) || // IETF assignments, documentation
                (o0 == 192 && o1 == 88 && o2 == 99) || // deprecated 6to4 relay anycast
                (o0 == 198 && o1 in 18..19) || // benchmarking
                (o0 == 198 && o1 == 51 && o2 == 100) || // documentation
                (o0 == 203 && o1 == 0 && o2 == 113) // documentation
        )
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        // ::ffff:a.b.c.d (IPv4-mapped) and 64:ff9b::a.b.c.d (NAT64) reach that IPv4 address.
        if (allZero(b, 0, 10) && octet(b, 10) == 0xFF && octet(b, 11) == 0xFF) return isPublicV4(b, 12)
        if (group(b, 0) == 0x0064 && group(b, 2) == 0xFF9B && allZero(b, 4, 12)) return isPublicV4(b, 12)
        // 2002:a.b.c.d::/48 (6to4) carries its IPv4 address in bytes 2..5.
        if (group(b, 0) == 0x2002) return isPublicV4(b, 2)
        // 2001:0::/32 (Teredo) carries the client's IPv4 address inverted in its last 4 bytes.
        if (group(b, 0) == 0x2001 && group(b, 2) == 0x0000) {
            val client = ByteArray(4) { (b[12 + it].toInt() xor 0xFF).toByte() }
            return isPublicV4(client, 0)
        }
        if ((octet(b, 0) and 0xE0) != 0x20) return false // outside global unicast 2000::/3
        if (group(b, 0) == 0x2001 && octet(b, 2) < 0x02) return false // 2001::/23 protocol assignments
        if (group(b, 0) == 0x2001 && group(b, 2) == 0x0DB8) return false // documentation
        return true
    }

    private fun allZero(
        b: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (i in from until to) if (b[i].toInt() != 0) return false
        return true
    }

    private fun group(
        b: ByteArray,
        at: Int,
    ) = (octet(b, at) shl 8) or octet(b, at + 1)

    private fun octet(
        b: ByteArray,
        at: Int,
    ) = b[at].toInt() and 0xFF
}
