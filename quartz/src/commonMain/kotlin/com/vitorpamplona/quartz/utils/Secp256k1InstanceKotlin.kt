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

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1

/**
 * Pure-Kotlin secp256k1 (schnorr256k1-kotlin), with Quartz's names. Invalid input throws
 * [IllegalArgumentException], as it always has; verification returns false.
 */
object Secp256k1InstanceKotlin {
    private fun invalid(what: String): Nothing = throw IllegalArgumentException("Invalid $what")

    fun compressedPubKeyFor(privKey: ByteArray): ByteArray = Schnorr256k1.pubkeyCreate(privKey)?.let { Schnorr256k1.pubkeyCompress(it) } ?: invalid("private key")

    fun isPrivateKeyValid(il: ByteArray): Boolean = Schnorr256k1.seckeyVerify(il)

    fun signSchnorr(
        data: ByteArray,
        privKey: ByteArray,
        nonce: ByteArray? = RandomInstance.bytes(32),
    ): ByteArray = Schnorr256k1.schnorrSign(data, privKey, nonce) ?: invalid("signing input")

    fun signSchnorr(
        data: ByteArray,
        privKey: ByteArray,
    ): ByteArray = Schnorr256k1.schnorrSign(data, privKey, null) ?: invalid("signing input")

    /** Fast signing with pre-computed compressed public key (skips G multiplication). */
    fun signSchnorrWithPubKey(
        data: ByteArray,
        privKey: ByteArray,
        compressedPubKey: ByteArray,
        nonce: ByteArray? = RandomInstance.bytes(32),
    ): ByteArray = Schnorr256k1.schnorrSignWithPubKey(data, privKey, compressedPubKey, nonce) ?: invalid("signing input")

    /**
     * Signing with the x-only public key known. No faster than [signSchnorr]: an x-only key does
     * not carry the y parity signing needs ([signSchnorrWithPubKey] does).
     */
    fun signSchnorrWithXOnlyPubKey(
        data: ByteArray,
        privKey: ByteArray,
        xOnlyPubKey: ByteArray,
        nonce: ByteArray? = RandomInstance.bytes(32),
    ): ByteArray = Schnorr256k1.schnorrSignXOnly(data, privKey, xOnlyPubKey, nonce) ?: invalid("signing input")

    fun verifySchnorr(
        signature: ByteArray,
        hash: ByteArray,
        pubKey: ByteArray,
    ): Boolean = Schnorr256k1.schnorrVerify(signature, hash, pubKey)

    /** Verification without BIP-340's y-parity check of R; safe for Nostr events (see the library). */
    fun verifySchnorrFast(
        signature: ByteArray,
        hash: ByteArray,
        pubKey: ByteArray,
    ): Boolean = Schnorr256k1.schnorrVerifyFast(signature, hash, pubKey)

    /** BIP-340 batch verification of signatures by the same key (weighted: invalid ones can't cancel). */
    fun verifySchnorrBatch(
        pubKey: ByteArray,
        signatures: List<ByteArray>,
        messages: List<ByteArray>,
    ): Boolean {
        require(signatures.size == messages.size) { "signatures and messages must have same size" }
        return Schnorr256k1.schnorrVerifyBatch(pubKey, signatures, messages)
    }

    fun privateKeyAdd(
        first: ByteArray,
        second: ByteArray,
    ): ByteArray = Schnorr256k1.privkeyTweakAdd(first, second) ?: invalid("private key or tweak")

    fun pubKeyTweakMulCompact(
        pubKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray = Schnorr256k1.ecdhXOnly(pubKey, privateKey) ?: invalid("public key or private key")
}
