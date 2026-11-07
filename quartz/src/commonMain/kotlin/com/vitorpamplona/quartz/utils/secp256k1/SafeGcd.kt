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
 * A port of libsecp256k1's `secp256k1_modinv64_var` (src/modinv64_impl.h), which see for the
 * derivation. MIT license; src/modinv64_impl.h: "Copyright (c) 2020 Peter Dettman"; the
 * library's COPYING: "Copyright (c) 2013 Pieter Wuille". Numbers are
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
    ) {
        val limbs = longArrayOf(m0, m1, m2, m3, m4)
    }

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

    // Signed 128-bit accumulators are kept as (hi, lo) pairs of locals, not objects: ART has no
    // escape analysis to remove a per-round allocation. hiAdd(hi, lo, p, ph) is the new high word
    // of (hi, lo) + (ph, p); the caller then sets lo += p.

    /** Carry out of lo + p, branch-free (no unsigned compare: see uLt for why that is per platform). */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun hiAdd(
        hi: Long,
        lo: Long,
        p: Long,
        ph: Long,
    ): Long {
        val s = lo + p
        return hi + ph + (((lo and p) or ((lo or p) and s.inv())) ushr 63)
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
        val e0 = e[0]
        val d4 = d[4]
        val e4 = e[4]
        var p: Long

        val sd = d4 shr 63
        val se = e4 shr 63
        var md = (u and sd) + (v and se)
        var me = (q and sd) + (r and se)

        // limb 0: cd = u*d0 + v*e0, ce = q*d0 + r*e0
        var cdLo = u * d0
        var cdHi = multiplyHigh(u, d0)
        p = v * e0
        cdHi = hiAdd(cdHi, cdLo, p, multiplyHigh(v, e0))
        cdLo += p
        var ceLo = q * d0
        var ceHi = multiplyHigh(q, d0)
        p = r * e0
        ceHi = hiAdd(ceHi, ceLo, p, multiplyHigh(r, e0))
        ceLo += p
        // choose md, me so the bottom 62 bits cancel
        md -= (m.inv62 * cdLo + md) and M62
        me -= (m.inv62 * ceLo + me) and M62
        p = m.m0 * md
        cdHi = hiAdd(cdHi, cdLo, p, multiplyHigh(m.m0, md))
        cdLo += p
        p = m.m0 * me
        ceHi = hiAdd(ceHi, ceLo, p, multiplyHigh(m.m0, me))
        ceLo += p
        cdLo = (cdLo ushr 62) or (cdHi shl 2)
        cdHi = cdHi shr 62
        ceLo = (ceLo ushr 62) or (ceHi shl 2)
        ceHi = ceHi shr 62

        // limbs 1..4, each stored one limb down (iteration i reads d[i] before writing d[i - 1])
        val ml = m.limbs
        for (i in 1..4) {
            val di = d[i]
            val ei = e[i]
            val mi = ml[i]
            p = u * di
            cdHi = hiAdd(cdHi, cdLo, p, multiplyHigh(u, di))
            cdLo += p
            p = v * ei
            cdHi = hiAdd(cdHi, cdLo, p, multiplyHigh(v, ei))
            cdLo += p
            p = q * di
            ceHi = hiAdd(ceHi, ceLo, p, multiplyHigh(q, di))
            ceLo += p
            p = r * ei
            ceHi = hiAdd(ceHi, ceLo, p, multiplyHigh(r, ei))
            ceLo += p
            if (mi != 0L) { // p's limbs 1..3 are zero; n's limb 3 is
                p = mi * md
                cdHi = hiAdd(cdHi, cdLo, p, multiplyHigh(mi, md))
                cdLo += p
                p = mi * me
                ceHi = hiAdd(ceHi, ceLo, p, multiplyHigh(mi, me))
                ceLo += p
            }
            d[i - 1] = cdLo and M62
            e[i - 1] = ceLo and M62
            cdLo = (cdLo ushr 62) or (cdHi shl 2)
            cdHi = cdHi shr 62
            ceLo = (ceLo ushr 62) or (ceHi shl 2)
            ceHi = ceHi shr 62
        }
        d[4] = cdLo
        e[4] = ceLo
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
        var p: Long
        var fi = f[0]
        var gi = g[0]
        var cfLo = u * fi
        var cfHi = multiplyHigh(u, fi)
        p = v * gi
        cfHi = hiAdd(cfHi, cfLo, p, multiplyHigh(v, gi))
        cfLo += p
        var cgLo = q * fi
        var cgHi = multiplyHigh(q, fi)
        p = r * gi
        cgHi = hiAdd(cgHi, cgLo, p, multiplyHigh(r, gi))
        cgLo += p
        cfLo = (cfLo ushr 62) or (cfHi shl 2)
        cfHi = cfHi shr 62
        cgLo = (cgLo ushr 62) or (cgHi shl 2)
        cgHi = cgHi shr 62
        for (i in 1 until len) {
            fi = f[i]
            gi = g[i]
            p = u * fi
            cfHi = hiAdd(cfHi, cfLo, p, multiplyHigh(u, fi))
            cfLo += p
            p = v * gi
            cfHi = hiAdd(cfHi, cfLo, p, multiplyHigh(v, gi))
            cfLo += p
            p = q * fi
            cgHi = hiAdd(cgHi, cgLo, p, multiplyHigh(q, fi))
            cgLo += p
            p = r * gi
            cgHi = hiAdd(cgHi, cgLo, p, multiplyHigh(r, gi))
            cgLo += p
            f[i - 1] = cfLo and M62
            g[i - 1] = cgLo and M62
            cfLo = (cfLo ushr 62) or (cfHi shl 2)
            cfHi = cfHi shr 62
            cgLo = (cgLo ushr 62) or (cgHi shl 2)
            cgHi = cgHi shr 62
        }
        f[len - 1] = cfLo
        g[len - 1] = cgLo
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
