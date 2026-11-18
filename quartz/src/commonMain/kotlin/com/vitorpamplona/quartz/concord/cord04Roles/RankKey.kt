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
package com.vitorpamplona.quartz.concord.cord04Roles

/**
 * Rank keys (CORD-04 §3): Role order as fractional keys over `0-9A-Za-z`, compared byte by byte,
 * 1 to 32 bytes and never ending in `0`, so a key strictly between any two always exists. Channel
 * and Category positions (CORD-03 §2, §4) use the same keys. Lower sorts first and ranks higher.
 */
object RankKey {
    const val MAX_BYTES = 32

    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun digit(c: Char): Int = DIGITS.indexOf(c)

    /** Whether [key] is a well-formed rank key. */
    fun isRankKey(key: String?): Boolean {
        if (key == null || key.isEmpty() || key.length > MAX_BYTES) return false
        for (c in key) if (digit(c) < 0) return false
        return key[key.length - 1] != '0'
    }

    /** Byte order of two rank keys (ASCII, so char order is byte order). */
    fun compare(
        a: String,
        b: String,
    ): Int = a.compareTo(b)

    /**
     * Order-preserving dense positions: each distinct key maps to 1 + its index in byte order, so
     * equal keys are peers and 0 stays the owner's. What a fold compares ranks by.
     */
    fun denseRanks(keys: Iterable<String>): Map<String, Long> {
        val sorted = keys.distinct().sorted()
        val out = HashMap<String, Long>(sorted.size)
        var i = 1L
        for (k in sorted) out[k] = i++
        return out
    }

    /**
     * A key strictly between [lo] and [hi] (null is the open end on that side), or null when the
     * keys are malformed, out of order, or the gap no longer fits in 32 bytes. Ported from
     * Vector's `community/rank.rs` (MIT) by way of Armada's `rankKey.ts`.
     */
    fun between(
        lo: String?,
        hi: String?,
    ): String? {
        val low = lo ?: ""
        if (low.isNotEmpty() && !isRankKey(low)) return null
        if (hi != null && (!isRankKey(hi) || low >= hi)) return null
        val key = if (hi == null && low.isNotEmpty()) after(low) else midpoint(low, hi)
        return if (key.length <= MAX_BYTES) key else null
    }

    private fun after(lo: String): String {
        if (lo.isEmpty()) return DIGITS[1].toString()
        val a = digit(lo[0])
        if (a + 1 < DIGITS.length) return DIGITS[a + 1].toString()
        return lo[0] + after(lo.substring(1))
    }

    private fun midpoint(
        lo: String,
        hi: String?,
    ): String {
        if (hi != null) {
            var n = 0
            while (n < hi.length && (lo.getOrNull(n) ?: '0') == hi[n]) n++
            if (n > 0) return hi.substring(0, n) + midpoint(if (n < lo.length) lo.substring(n) else "", hi.substring(n))
        }
        val a = lo.getOrNull(0)?.let { digit(it) } ?: 0
        val b = if (hi == null) DIGITS.length else digit(hi[0])
        if (b - a > 1) return DIGITS[(a + b + 1) / 2].toString()
        if (hi != null && hi.length > 1) return hi[0].toString()
        return DIGITS[a] + midpoint(if (lo.length > 1) lo.substring(1) else "", null)
    }
}
