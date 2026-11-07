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
package com.vitorpamplona.quartz.utils.secp256k1

/**
 * Modular inversion by safegcd (Bernstein-Yang divsteps), variable time.
 *
 * A port of libsecp256k1's `secp256k1_modinv64_var` (src/modinv64_impl.h, MIT license,
 * Copyright (c) 2020 Peter Dettman, Pieter Wuille), which see for the derivation. Numbers are
 * held as five signed 62-bit limbs; each round does 62 divsteps on the bottom limbs, then applies
 * the resulting 2x2 matrix to the full numbers. A 256-bit inverse takes about 10 rounds, against
 * 270 field multiplies for Fermat's a^(p-2).
 *
 * Variable time: the number of rounds and the work per round depend on the input. libsecp256k1
 * uses this variant only for public data.
 */
internal object SafeGcd {
    private const val M62 = 0x3FFFFFFFFFFFFFFFL

    /** A modulus in signed-62 form, with its inverse mod 2^62. */
    class Modulus(
        val m0: Long,
        val m1: Long,
        val m2: Long,
        val m3: Long,
        val m4: Long,
        val inv62: Long,
    )

    /** p = 2^256 - 2^32 - 977. */
    val P = Modulus(-0x1000003D1L, 0L, 0L, 0L, 256L, 0x27C7F6E22DDACACFL)

    /** n, the group order. */
    val N = Modulus(0x3FD25E8CD0364141L, 0x2ABB739ABD2280EEL, -0x15L, 0L, 256L, 0x34F20099AA774EC1L)

    /** Scratch: d, e, f, g as five limbs each. */
    class Scratch {
        val d = LongArray(5)
        val e = LongArray(5)
        val f = LongArray(5)
        val g = LongArray(5)
        val t = LongArray(4) // u, v, q, r
    }

    private val scratch = ScratchLocal { Scratch() }

    /**
     * out = x^-1 mod m, for x in [0, m) (x = 0 gives 0). [out] may be [x].
     */
    fun inverse(
        out: Fe4,
        x: Fe4,
        m: Modulus,
    ) {
        val s = scratch.get()
        val d = s.d
        val e = s.e
        val f = s.f
        val g = s.g
        val t = s.t

        d.fill(0L)
        e.fill(0L)
        e[0] = 1L
        f[0] = m.m0
        f[1] = m.m1
        f[2] = m.m2
        f[3] = m.m3
        f[4] = m.m4
        // to signed62
        g[0] = x.l0 and M62
        g[1] = ((x.l0 ushr 62) or (x.l1 shl 2)) and M62
        g[2] = ((x.l1 ushr 60) or (x.l2 shl 4)) and M62
        g[3] = ((x.l2 ushr 58) or (x.l3 shl 6)) and M62
        g[4] = x.l3 ushr 56

        var len = 5
        var eta = -1L
        while (true) {
            eta = divsteps62Var(eta, f[0], g[0], t)
            updateDe(d, e, t, m)
            updateFgVar(len, f, g, t)
            if (g[0] == 0L) {
                var cond = 0L
                for (j in 1 until len) cond = cond or g[j]
                if (cond == 0L) break
            }
            val fn = f[len - 1]
            val gn = g[len - 1]
            var cond = (len.toLong() - 2) shr 63
            cond = cond or (fn xor (fn shr 63))
            cond = cond or (gn xor (gn shr 63))
            if (cond == 0L) {
                f[len - 2] = f[len - 2] or (fn shl 62)
                g[len - 2] = g[len - 2] or (gn shl 62)
                len--
            }
        }

        normalize(d, f[len - 1], m)
        // from signed62
        out.l0 = d[0] or (d[1] shl 62)
        out.l1 = (d[1] ushr 2) or (d[2] shl 60)
        out.l2 = (d[2] ushr 4) or (d[3] shl 58)
        out.l3 = (d[3] ushr 6) or (d[4] shl 56)
    }

    /** 62 divsteps on the bottom limbs; writes the transition matrix (scaled by 2^62) to t. */
    private fun divsteps62Var(
        eta0: Long,
        f0: Long,
        g0: Long,
        t: LongArray,
    ): Long {
        var eta = eta0
        var u = 1L
        var v = 0L
        var q = 0L
        var r = 1L
        var f = f0
        var g = g0
        var i = 62
        while (true) {
            // Sentinel bit so at most i zeros are counted.
            val zeros = (g or (-1L shl i)).countTrailingZeroBits()
            g = g ushr zeros
            u = u shl zeros
            v = v shl zeros
            eta -= zeros
            i -= zeros
            if (i == 0) break
            val w: Long
            if (eta < 0) {
                eta = -eta
                var tmp = f
                f = g
                g = -tmp
                tmp = u
                u = q
                q = -tmp
                tmp = v
                v = r
                r = -tmp
                // Cancel up to 6 bits of g.
                val limit = if (eta.toInt() + 1 > i) i else eta.toInt() + 1
                val mask = (-1L ushr (64 - limit)) and 63L
                w = (f * g * (f * f - 2)) and mask
            } else {
                // Cancel up to 4 bits of g.
                val limit = if (eta.toInt() + 1 > i) i else eta.toInt() + 1
                val mask = (-1L ushr (64 - limit)) and 15L
                val w0 = f + (((f + 1) and 4L) shl 1)
                w = (-w0 * g) and mask
            }
            g += f * w
            q += u * w
            r += v * w
        }
        t[0] = u
        t[1] = v
        t[2] = q
        t[3] = r
        return eta
    }

    /** Signed 128-bit accumulator. */
    private class Acc {
        var hi = 0L
        var lo = 0L

        fun set(
            a: Long,
            b: Long,
        ) {
            lo = a * b
            hi = multiplyHigh(a, b)
        }

        fun add(
            a: Long,
            b: Long,
        ) {
            val p = a * b
            val l = lo + p
            hi += multiplyHigh(a, b) + (if (l.toULong() < p.toULong()) 1L else 0L)
            lo = l
        }

        /** Arithmetic shift right by 62. */
        fun shr62() {
            lo = (lo ushr 62) or (hi shl 2)
            hi = hi shr 62
        }
    }

    /** [d, e] = (t * [d, e] + m * [md, me]) / 2^62, keeping both in (-2m, m). */
    private fun updateDe(
        d: LongArray,
        e: LongArray,
        t: LongArray,
        m: Modulus,
    ) {
        val u = t[0]
        val v = t[1]
        val q = t[2]
        val r = t[3]
        val d0 = d[0]
        val d1 = d[1]
        val d2 = d[2]
        val d3 = d[3]
        val d4 = d[4]
        val e0 = e[0]
        val e1 = e[1]
        val e2 = e[2]
        val e3 = e[3]
        val e4 = e[4]
        val cd = Acc()
        val ce = Acc()

        val sd = d4 shr 63
        val se = e4 shr 63
        var md = (u and sd) + (v and se)
        var me = (q and sd) + (r and se)
        cd.set(u, d0)
        cd.add(v, e0)
        ce.set(q, d0)
        ce.add(r, e0)
        md -= (m.inv62 * cd.lo + md) and M62
        me -= (m.inv62 * ce.lo + me) and M62
        cd.add(m.m0, md)
        ce.add(m.m0, me)
        cd.shr62()
        ce.shr62()

        cd.add(u, d1)
        cd.add(v, e1)
        ce.add(q, d1)
        ce.add(r, e1)
        if (m.m1 != 0L) {
            cd.add(m.m1, md)
            ce.add(m.m1, me)
        }
        d[0] = cd.lo and M62
        cd.shr62()
        e[0] = ce.lo and M62
        ce.shr62()

        cd.add(u, d2)
        cd.add(v, e2)
        ce.add(q, d2)
        ce.add(r, e2)
        if (m.m2 != 0L) {
            cd.add(m.m2, md)
            ce.add(m.m2, me)
        }
        d[1] = cd.lo and M62
        cd.shr62()
        e[1] = ce.lo and M62
        ce.shr62()

        cd.add(u, d3)
        cd.add(v, e3)
        ce.add(q, d3)
        ce.add(r, e3)
        if (m.m3 != 0L) {
            cd.add(m.m3, md)
            ce.add(m.m3, me)
        }
        d[2] = cd.lo and M62
        cd.shr62()
        e[2] = ce.lo and M62
        ce.shr62()

        cd.add(u, d4)
        cd.add(v, e4)
        ce.add(q, d4)
        ce.add(r, e4)
        cd.add(m.m4, md)
        ce.add(m.m4, me)
        d[3] = cd.lo and M62
        cd.shr62()
        e[3] = ce.lo and M62
        ce.shr62()

        d[4] = cd.lo
        e[4] = ce.lo
    }

    /** [f, g] = t * [f, g] / 2^62 over the bottom [len] limbs. */
    private fun updateFgVar(
        len: Int,
        f: LongArray,
        g: LongArray,
        t: LongArray,
    ) {
        val u = t[0]
        val v = t[1]
        val q = t[2]
        val r = t[3]
        val cf = Acc()
        val cg = Acc()
        var fi = f[0]
        var gi = g[0]
        cf.set(u, fi)
        cf.add(v, gi)
        cg.set(q, fi)
        cg.add(r, gi)
        cf.shr62()
        cg.shr62()
        for (i in 1 until len) {
            fi = f[i]
            gi = g[i]
            cf.add(u, fi)
            cf.add(v, gi)
            cg.add(q, fi)
            cg.add(r, gi)
            f[i - 1] = cf.lo and M62
            cf.shr62()
            g[i - 1] = cg.lo and M62
            cg.shr62()
        }
        f[len - 1] = cf.lo
        g[len - 1] = cg.lo
    }

    /** Bring d from (-2m, m) to [0, m), negating first if sign < 0. */
    private fun normalize(
        d: LongArray,
        sign: Long,
        m: Modulus,
    ) {
        var r0 = d[0]
        var r1 = d[1]
        var r2 = d[2]
        var r3 = d[3]
        var r4 = d[4]

        var condAdd = r4 shr 63
        r0 += m.m0 and condAdd
        r1 += m.m1 and condAdd
        r2 += m.m2 and condAdd
        r3 += m.m3 and condAdd
        r4 += m.m4 and condAdd
        val condNegate = sign shr 63
        r0 = (r0 xor condNegate) - condNegate
        r1 = (r1 xor condNegate) - condNegate
        r2 = (r2 xor condNegate) - condNegate
        r3 = (r3 xor condNegate) - condNegate
        r4 = (r4 xor condNegate) - condNegate
        r1 += r0 shr 62
        r0 = r0 and M62
        r2 += r1 shr 62
        r1 = r1 and M62
        r3 += r2 shr 62
        r2 = r2 and M62
        r4 += r3 shr 62
        r3 = r3 and M62

        condAdd = r4 shr 63
        r0 += m.m0 and condAdd
        r1 += m.m1 and condAdd
        r2 += m.m2 and condAdd
        r3 += m.m3 and condAdd
        r4 += m.m4 and condAdd
        r1 += r0 shr 62
        r0 = r0 and M62
        r2 += r1 shr 62
        r1 = r1 and M62
        r3 += r2 shr 62
        r2 = r2 and M62
        r4 += r3 shr 62
        r3 = r3 and M62

        d[0] = r0
        d[1] = r1
        d[2] = r2
        d[3] = r3
        d[4] = r4
    }
}
