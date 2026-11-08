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

import com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1

/**
 * The pure-Kotlin secp256k1 that lived here, now its own library:
 * [libschnorr256k1-kotlin](https://github.com/vitorpamplona/libschnorr256k1-kotlin)
 * (com.vitorpamplona.schnorr256k1:schnorr256k1-kotlin), which Quartz depends on.
 *
 * Kept, delegating to it, so code compiled against Quartz 2.x still links. Its behaviour is the
 * old one: invalid input throws [IllegalArgumentException] (the library returns null or false).
 * Two answers are fixed: [verifySchnorrBatch] is BIP-340's weighted batch verification (the old
 * one accepted two invalid signatures that cancel), and [pubKeyCompress] validates its input.
 */
@Deprecated(
    "Use com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1 (schnorr256k1-kotlin), which returns " +
        "null or false on invalid input instead of throwing. Removed in 3.0.0.",
)
object Secp256k1 {
    private fun invalid(what: String): Nothing = throw IllegalArgumentException("Invalid $what")

    fun pubkeyCreate(seckey: ByteArray): ByteArray = Schnorr256k1.pubkeyCreate(seckey) ?: invalid("secret key")

    fun pubKeyCompress(pubkey: ByteArray): ByteArray = Schnorr256k1.pubkeyCompress(pubkey) ?: invalid("public key: size=${pubkey.size}")

    fun secKeyVerify(seckey: ByteArray): Boolean = Schnorr256k1.seckeyVerify(seckey)

    fun signSchnorr(
        data: ByteArray,
        seckey: ByteArray,
        auxrand: ByteArray?,
    ): ByteArray = Schnorr256k1.schnorrSign(data, seckey, auxrand) ?: invalid("signing input")

    fun signSchnorrWithPubKey(
        data: ByteArray,
        seckey: ByteArray,
        compressedPub: ByteArray,
        auxrand: ByteArray?,
    ): ByteArray = Schnorr256k1.schnorrSignWithPubKey(data, seckey, compressedPub, auxrand) ?: invalid("signing input")

    fun signSchnorrWithXOnlyPubKey(
        data: ByteArray,
        seckey: ByteArray,
        xOnlyPub: ByteArray,
        auxrand: ByteArray?,
    ): ByteArray = Schnorr256k1.schnorrSignXOnly(data, seckey, xOnlyPub, auxrand) ?: invalid("signing input")

    fun verifySchnorr(
        signature: ByteArray,
        data: ByteArray,
        pub: ByteArray,
    ): Boolean = Schnorr256k1.schnorrVerify(signature, data, pub)

    fun verifySchnorrFast(
        signature: ByteArray,
        data: ByteArray,
        pub: ByteArray,
    ): Boolean = Schnorr256k1.schnorrVerifyFast(signature, data, pub)

    fun privKeyTweakAdd(
        seckey: ByteArray,
        tweak: ByteArray,
    ): ByteArray = Schnorr256k1.privkeyTweakAdd(seckey, tweak) ?: invalid("secret key or tweak")

    fun pubKeyTweakMul(
        pubkey: ByteArray,
        tweak: ByteArray,
    ): ByteArray = Schnorr256k1.pubkeyTweakMul(pubkey, tweak) ?: invalid("public key or tweak")

    fun ecdhXOnly(
        xOnlyPub: ByteArray,
        scalar: ByteArray,
    ): ByteArray = Schnorr256k1.ecdhXOnly(xOnlyPub, scalar) ?: invalid("public key or scalar")

    fun verifySchnorrBatch(
        pub: ByteArray,
        signatures: List<ByteArray>,
        messages: List<ByteArray>,
    ): Boolean {
        require(signatures.size == messages.size) { "signatures and messages must have same size" }
        return Schnorr256k1.schnorrVerifyBatch(pub, signatures, messages)
    }
}
