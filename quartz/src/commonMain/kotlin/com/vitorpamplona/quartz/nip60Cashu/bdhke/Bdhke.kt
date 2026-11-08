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
package com.vitorpamplona.quartz.nip60Cashu.bdhke

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1
import fr.acinq.secp256k1.Secp256k1 as LibSecp256k1

/**
 * Blind Diffie-Hellman Key Exchange (BDHKE) — the cryptographic primitive
 * that powers Cashu ecash. See https://github.com/cashubtc/nuts/blob/main/00.md.
 *
 * The protocol — three participants implied (Alice = user, Bob = mint):
 *   1. Alice picks a random `secret` and blinding factor `r`. Computes
 *      `Y = hash_to_curve(secret)` and the blinded message `B_ = Y + r·G`.
 *   2. Bob signs the blinded point with private key `a`: `C_ = a·B_`.
 *      Bob's public key for this amount is `K = a·G`.
 *   3. Alice unblinds: `C = C_ - r·K`. The triple `(secret, C, id)` is the
 *      proof — Bob can verify it by checking `C == a·Y`.
 *
 * Bob cannot link `B_` to `(secret, C)` because the blinding factor `r` is
 * private to Alice — hence "blind" signatures and the privacy property of
 * ecash. See NUT-00 for the full security argument.
 *
 * Every multiplication by a secret scalar (the blinding factor `r`, the mint
 * key `a`, the DLEQ nonce) runs on libsecp256k1, which is constant-time. The
 * pure-Kotlin curve code (schnorr256k1-kotlin) is variable-time by design, so it
 * only handles public values here: hash-to-curve, point checks and DLEQ verification.
 */
object Bdhke {
    private val libsecp = LibSecp256k1.get()

    /** Cashu hash-to-curve domain separator (NUT-00). */
    private val DOMAIN_SEPARATOR = "Secp256k1_HashToCurve_Cashu_".encodeToByteArray()

    /**
     * Map an arbitrary byte string to a secp256k1 point using the NUT-00
     * try-and-increment algorithm.
     *
     *   msg_to_hash = SHA256(domain_separator || x)
     *   for counter in 0..2^32:
     *     y_candidate = SHA256(msg_to_hash || counter as little-endian uint32)
     *     try lift_x(y_candidate); if it's on the curve, return that point.
     *
     * Always returns the point with even Y (lift_x convention), as 33 compressed bytes.
     */
    internal fun hashToCurve(x: ByteArray): ByteArray {
        val msgToHash = sha256(DOMAIN_SEPARATOR + x)
        val buf = ByteArray(36)
        msgToHash.copyInto(buf, 0)

        var counter = 0
        while (counter < MAX_HASH_TO_CURVE_ITERATIONS) {
            buf[32] = (counter and 0xFF).toByte()
            buf[33] = ((counter ushr 8) and 0xFF).toByte()
            buf[34] = ((counter ushr 16) and 0xFF).toByte()
            buf[35] = ((counter ushr 24) and 0xFF).toByte()

            // lift_x(candidate): 02 || candidate is a point iff candidate is an x on the curve.
            val point = EVEN_Y + sha256(buf)
            if (Schnorr256k1.pubkeyParse(point) != null) return point
            counter++
        }
        error("hash_to_curve failed to find a valid point in $MAX_HASH_TO_CURVE_ITERATIONS iterations")
    }

    /**
     * Public form of [hashToCurve] that returns a 33-byte compressed point.
     */
    fun hashToCurveCompressed(x: ByteArray): ByteArray = hashToCurve(x)

    /**
     * Step 1 of BDHKE — Alice creates a blinded message.
     *
     * @param secret 32-byte (or arbitrary length) message to be later unblinded.
     * @param r 32-byte blinding factor (must be a valid scalar < n).
     * @return 33-byte compressed point `B_ = hash_to_curve(secret) + r·G`.
     */
    fun blind(
        secret: ByteArray,
        r: ByteArray,
    ): ByteArray {
        require(r.size == 32) { "Blinding factor must be 32 bytes" }
        require(Schnorr256k1.seckeyVerify(r)) { "Invalid blinding factor" }

        val y = hashToCurveCompressed(secret)
        val rg = libsecp.pubkeyCreate(r)
        return libsecp.pubKeyCompress(libsecp.pubKeyCombine(arrayOf(y, rg)))
    }

    /**
     * Step 3 of BDHKE — Alice unblinds the mint's signature.
     *
     *   C = C_ - r·K
     *
     * Implemented as `C_ + (-(r·K))`: libsecp256k1 has no point subtraction.
     *
     * @param blindSignature 33-byte compressed `C_` returned by the mint.
     * @param r 32-byte blinding factor used in [blind].
     * @param mintPubKey 33-byte compressed `K = a·G` (the mint's public key
     *                   for this amount and keyset).
     * @return 33-byte compressed `C`, the unblinded signature on `secret`.
     */
    fun unblind(
        blindSignature: ByteArray,
        r: ByteArray,
        mintPubKey: ByteArray,
    ): ByteArray {
        require(r.size == 32) { "Blinding factor must be 32 bytes" }
        require(isValidPoint(blindSignature)) { "Invalid blind signature" }
        require(isValidPoint(mintPubKey)) { "Invalid mint public key" }

        val negRk = libsecp.pubKeyNegate(libsecp.pubKeyTweakMul(mintPubKey, r))
        return libsecp.pubKeyCompress(libsecp.pubKeyCombine(arrayOf(blindSignature, negRk)))
    }

    /**
     * Mint-side BDHKE — Bob signs the blinded message with his private key.
     * Used only by tests and the optional mint-emulation path.
     *
     *   C_ = a·B_
     *
     * @param blindedMessage 33-byte compressed `B_`.
     * @param mintPrivKey 32-byte mint private key `a` for this amount.
     * @return 33-byte compressed `C_`.
     */
    fun sign(
        blindedMessage: ByteArray,
        mintPrivKey: ByteArray,
    ): ByteArray = libsecp.pubKeyCompress(libsecp.pubKeyTweakMul(blindedMessage, mintPrivKey))

    /**
     * Mint-side proof verification — Bob checks `C == a·Y`.
     * Used by tests and (optionally) by clients that want to verify the mint's
     * signature locally before publishing a kind:7375 token event.
     *
     * @param secret The proof's secret.
     * @param unblindedSignature 33-byte compressed `C`.
     * @param mintPrivKey 32-byte mint private key `a`.
     */
    fun verify(
        secret: ByteArray,
        unblindedSignature: ByteArray,
        mintPrivKey: ByteArray,
    ): Boolean {
        val expected = sign(hashToCurveCompressed(secret), mintPrivKey)
        return expected.contentEquals(unblindedSignature)
    }

    /**
     * NUT-12 Alice-side DLEQ verification.
     *
     * Without this, the mint can return a junk `C'` and we won't notice
     * until we try to spend the proof — by which point any sender has
     * already considered the payment done. DLEQ proves the mint used the
     * private key whose pubkey is the [mintPubKey] we read from
     * `/v1/keys` — i.e. the same key for both [blindSignature] and
     * [mintPubKey].
     *
     * Math (from NUT-12):
     * ```
     *   R1 = sG - eA          where A = mintPubKey, e and s are the
     *   R2 = sB' - eC'        scalars the mint returns alongside C'.
     *   e' = SHA256(R1 || R2 || A || C')   (compressed, 33-byte each)
     *   return e' == e
     * ```
     *
     * Returns false on any malformed input (wrong byte length, scalars
     * outside [1, n), points not on curve) or DLEQ mismatch. Callers
     * should treat false as a protocol violation and abort the swap/mint.
     *
     * @param e 32-byte challenge scalar from the mint.
     * @param s 32-byte response scalar from the mint.
     * @param blindedMessage 33-byte compressed `B_` we sent.
     * @param blindSignature 33-byte compressed `C_` the mint returned.
     * @param mintPubKey 33-byte compressed `A = k·G` for this amount.
     */
    fun verifyDleq(
        e: ByteArray,
        s: ByteArray,
        blindedMessage: ByteArray,
        blindSignature: ByteArray,
        mintPubKey: ByteArray,
    ): Boolean {
        if (e.size != 32 || s.size != 32) return false
        if (blindedMessage.size != 33 || blindSignature.size != 33 || mintPubKey.size != 33) return false

        // `s` is `r' + e*k mod n` by construction (NUT-12 §2), so it must
        // be a canonical scalar in [1, n); reject if not.
        if (!Schnorr256k1.seckeyVerify(s)) return false
        // `e` is a SHA-256 hash output that NUT-12 treats as raw bytes,
        // NOT a scalar mod n — there's no requirement that `e < n`. With
        // probability ~2^-128 a valid proof has `e >= n`; rejecting it would
        // spuriously fail those, so e·P is computed as (e mod n)·P. A zero
        // `e` (mod n) would let any junk signature pass, because R1 / R2
        // would collapse to `sG` / `sB'` independent of the mint's key.
        val eModN = reduceModN(e) ?: return false

        // R1 = sG - eA, R2 = sB' - eC'. Infinity anywhere (or an invalid
        // point) makes the library return null, and the proof false.
        val sG = Schnorr256k1.pubkeyCreate(s) ?: return false
        val eA = Schnorr256k1.pubkeyTweakMul(mintPubKey, eModN) ?: return false
        val r1Uncompressed = Schnorr256k1.pubkeyCombine(listOf(sG, Schnorr256k1.pubkeyNegate(eA) ?: return false)) ?: return false

        val sB = Schnorr256k1.pubkeyTweakMul(blindedMessage, s) ?: return false
        val eC = Schnorr256k1.pubkeyTweakMul(blindSignature, eModN) ?: return false
        val r2Uncompressed = Schnorr256k1.pubkeyCombine(listOf(sB, Schnorr256k1.pubkeyNegate(eC) ?: return false)) ?: return false

        val aUncompressed = compressedToUncompressed(mintPubKey) ?: return false
        val cUncompressed = compressedToUncompressed(blindSignature) ?: return false

        // NUT-12 §2 hash input. The spec text says `sha256(R1 || R2 || A || C')`
        // but the normative behaviour established by every reference impl
        // (cashu-ts, nutshell, CDK) is:
        //   1. Serialize each point as 65-byte UNCOMPRESSED form (04 || X || Y)
        //   2. Hex-encode each (130 chars per point)
        //   3. Concatenate the four hex strings (520 chars)
        //   4. Hash the UTF-8 bytes of that concatenation
        // Real mints emit `e` computed this way. Earlier versions of this
        // code used raw 33-byte compressed bytes — round-tripping our own
        // [signFull] hid the mismatch but every real mint rejected the
        // proof. CDK's `hash_e` in crates/cashu/src/dhke.rs is the
        // authoritative reference.
        val hashInput =
            (r1Uncompressed.toHexKey() + r2Uncompressed.toHexKey() + aUncompressed.toHexKey() + cUncompressed.toHexKey())
                .encodeToByteArray()

        val computed = sha256(hashInput)
        return computed.contentEquals(e)
    }

    /**
     * NUT-12 §3 Carol-side DLEQ verification.
     *
     * Carol is the recipient of a proof that was minted by some other
     * wallet ("Alice"). The proof carries the UNBLINDED `C` and `(e, s, r)`,
     * where `r` is Alice's original blinding factor. To verify, Carol
     * has to reconstruct BOTH points that the DLEQ was proven over:
     *
     *   `B' = hashToCurve(secret) + r·G`   (the blinded message Alice sent)
     *   `C' = C + r·A`                     (the blinded signature the mint returned)
     *
     * Then she runs the same checks Alice did at mint-receive time.
     * Without `r` she couldn't compute either, which is exactly why the
     * NUT-12 dleq tuple carries `r` between wallets but never between
     * mint and wallet.
     *
     * Common pitfall (and the source of a real DLEQ-rejected-by-mint
     * production report): an earlier version of this code passed the
     * UNBLINDED `C` straight to [verifyDleq] as if it were `C'`. That
     * always failed because the math is over the blinded form. The
     * function name's `blindSignature` parameter is misleading — it's
     * actually the unblinded `C` from the proof; [verifyDleq] gets the
     * reconstructed `C'`.
     *
     * Returns false on any malformed input (wrong sizes, off-curve
     * points, infinity arithmetic) or mismatch.
     *
     * @param secret The proof's secret bytes (UTF-8 of the hex string
     *               the mint hashed in hash-to-curve, NOT raw 32 bytes).
     *               Same form as [CashuProof.secret].
     * @param r 32-byte blinding factor Alice used; carried in the proof's
     *          dleq tuple by spec.
     * @param e 32-byte challenge scalar from the dleq tuple.
     * @param s 32-byte response scalar from the dleq tuple.
     * @param unblindedC 33-byte `C` from the proof — the unblinded
     *                   signature the wallet stores after Alice's
     *                   [unblind].
     * @param mintPubKey 33-byte compressed `A = k·G` for this amount,
     *                   looked up from the mint's keyset.
     */
    fun verifyDleqCarol(
        secret: ByteArray,
        r: ByteArray,
        e: ByteArray,
        s: ByteArray,
        unblindedC: ByteArray,
        mintPubKey: ByteArray,
    ): Boolean {
        if (r.size != 32) return false
        if (unblindedC.size != 33) return false
        if (mintPubKey.size != 33) return false
        // Reconstruct B' the way Alice did at mint time.
        val bTick = runCatching { blind(secret, r) }.getOrNull() ?: return false
        // Reconstruct C' = C + r·A. Without this, verifyDleq is computing
        // the DLEQ check against the wrong point and always returns false
        // — this was a real production bug.
        val cTick = runCatching { addRTimesA(unblindedC, r, mintPubKey) }.getOrNull() ?: return false
        return verifyDleq(
            e = e,
            s = s,
            blindedMessage = bTick,
            blindSignature = cTick,
            mintPubKey = mintPubKey,
        )
    }

    /**
     * Returns `C + r·A` as a 33-byte compressed point. Inverse of the
     * `C' - r·A` step inside [unblind]; used by [verifyDleqCarol] to
     * reconstruct the blinded signature from its unblinded form plus
     * the blinding factor.
     */
    private fun addRTimesA(
        c: ByteArray,
        r: ByteArray,
        mintPubKey: ByteArray,
    ): ByteArray {
        require(isValidPoint(c)) { "Invalid C" }
        require(isValidPoint(mintPubKey)) { "Invalid mint public key" }

        val rk = libsecp.pubKeyTweakMul(mintPubKey, r)
        return libsecp.pubKeyCompress(libsecp.pubKeyCombine(arrayOf(c, rk)))
    }

    /** Whether [compressed] parses as a point on the curve; public data, so the Kotlin parser is fine. */
    private fun isValidPoint(compressed: ByteArray): Boolean = Schnorr256k1.pubkeyParse(compressed) != null

    /**
     * Mint-side NUT-12 DLEQ-signing — produces `(C', e, s)` for the given
     * `B'`. Test-only; real mints have their own signers. Useful for
     * round-tripping verification in unit tests without standing up a mint.
     *
     * Returns `Triple(cTick, e, s)` where each is 33 / 32 / 32 bytes.
     */
    fun signFull(
        blindedMessage: ByteArray,
        mintPrivKey: ByteArray,
        rPrime: ByteArray = randomScalar(),
    ): Triple<ByteArray, ByteArray, ByteArray> {
        require(rPrime.size == 32) { "DLEQ nonce must be 32 bytes" }
        require(Schnorr256k1.seckeyVerify(rPrime)) { "Invalid DLEQ nonce" }

        require(isValidPoint(blindedMessage)) { "Invalid B'" }
        val cTick = sign(blindedMessage, mintPrivKey)

        // libsecp256k1 returns 65-byte uncompressed points — exactly what
        // NUT-12 wants in the hash input. Don't compress.
        val r1Uncompressed = libsecp.pubkeyCreate(rPrime)
        val r2Uncompressed = libsecp.pubKeyTweakMul(blindedMessage, rPrime)
        val aUncompressed = libsecp.pubkeyCreate(mintPrivKey)
        val cUncompressed = compressedToUncompressed(cTick) ?: error("Invalid C' from sign()")

        // See [verifyDleq] for the hash format. Uncompressed (65-byte)
        // points serialized as UTF-8 hex strings concatenated, NOT raw
        // bytes. Real mints emit `e` this way; CDK / cashu-ts / nutshell
        // all agree.
        val hashInput =
            (r1Uncompressed.toHexKey() + r2Uncompressed.toHexKey() + aUncompressed.toHexKey() + cUncompressed.toHexKey())
                .encodeToByteArray()
        val e = sha256(hashInput)

        // s = r' + e*k mod n
        val s = libsecp.privKeyTweakAdd(libsecp.privKeyTweakMul(mintPrivKey, e), rPrime)

        return Triple(cTick, e, s)
    }

    /**
     * Generate a uniformly random 32-byte scalar suitable as a BDHKE blinding
     * factor or wallet P2PK private key. Rejects values >= n; in practice this
     * succeeds on the first sample with overwhelming probability.
     */
    fun randomScalar(): ByteArray {
        while (true) {
            val candidate = RandomInstance.bytes(32)
            if (Schnorr256k1.seckeyVerify(candidate)) return candidate
        }
    }

    /**
     * Generate a uniformly random 32-byte "secret" for a Cashu proof.
     * Currently the same as [randomScalar] but kept as a separate symbol so
     * the caller's intent is explicit: this is not a private key.
     */
    fun randomSecret(): ByteArray = randomScalar()

    /**
     * 65-byte uncompressed form `04 || X || Y` of a compressed point, for the
     * NUT-12 hash input, where the spec requires uncompressed bytes rather than
     * the wire form (`02/03 || X`). Null on parse failure.
     */
    private fun compressedToUncompressed(compressed: ByteArray): ByteArray? = Schnorr256k1.pubkeyParse(compressed)

    /** e mod n for a 32-byte e, or null if it is 0 mod n. e < 2^256 < 2n, so it is e or e - n. */
    private fun reduceModN(e: ByteArray): ByteArray? {
        var cmp = 0
        for (i in 0 until 32) {
            cmp = (e[i].toInt() and 0xFF) - (ORDER[i].toInt() and 0xFF)
            if (cmp != 0) break
        }
        val r =
            if (cmp < 0) {
                e
            } else {
                val out = ByteArray(32)
                var borrow = 0
                for (i in 31 downTo 0) {
                    val d = (e[i].toInt() and 0xFF) - (ORDER[i].toInt() and 0xFF) - borrow
                    out[i] = d.toByte()
                    borrow = if (d < 0) 1 else 0
                }
                out
            }
        return if (r.all { it == 0.toByte() }) null else r
    }

    private val EVEN_Y = byteArrayOf(0x02)

    /** n, the group order, big-endian. */
    private val ORDER = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141".hexToByteArray()

    // Spec doesn't bound this; in practice the first iteration succeeds with
    // probability ~1/2. We cap at 2^16 — astronomically unlikely to hit.
    private const val MAX_HASH_TO_CURVE_ITERATIONS = 65536
}
