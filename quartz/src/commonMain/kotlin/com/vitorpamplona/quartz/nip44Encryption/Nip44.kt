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
package com.vitorpamplona.quartz.nip44Encryption

import com.vitorpamplona.quartz.nip01Core.core.JsonMapper
import com.vitorpamplona.quartz.nip04Dm.crypto.EncryptedInfo
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

object Nip44 {
    val v1 = Nip44v1()
    val v2 = Nip44v2()

    fun clearCache() {
        v1.clearCache()
        v2.clearCache()
    }

    fun encrypt(
        msg: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): Nip44v2.EncryptedInfo {
        // current version should be used.
        return v2.encrypt(msg, privateKey, pubKey)
    }

    /**
     * The encryption version [payload] is written in, read from its form alone without decrypting:
     * [EncryptedInfo.V] (0, NIP-04 ciphertext), [Nip44v1.EncryptedInfo.V] (1) or
     * [Nip44v2.EncryptedInfo.V] (2), from the `{"v":…}` JSON form or the base64 form's first byte.
     * -1 when it is neither. A NIP-04 `?iv=` string is not a NIP-44 payload and reads as -1 too.
     *
     * Lets a channel refuse a form before handing the payload to any signer, local or remote:
     * [decrypt] accepts every version, which is right for old DMs but not for a channel whose
     * authenticity rests on the cipher (a seal, a ContextVM wrap, a NIP-46 request), since NIP-04
     * and NIP-44 v1 carry no MAC and can be reshaped by anyone holding an old ciphertext.
     */
    fun payloadVersion(payload: String): Int {
        if (payload.isEmpty()) return -1
        if (payload[0] == '{') {
            return try {
                JsonMapper.fromJson<EncryptedInfoString>(payload).v
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                -1
            }
        }
        if (payload.length < 4 || payload[0] == '#') return -1
        return try {
            when (val version = Base64.decode(payload, 0, 4)[0].toInt()) {
                EncryptedInfo.V, Nip44v1.EncryptedInfo.V, Nip44v2.EncryptedInfo.V -> version
                else -> -1
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            -1
        }
    }

    /** True when [payload] is in NIP-44 v2's base64 form, the only one an authenticated channel accepts. */
    fun isV2Payload(payload: String): Boolean = payload.isNotEmpty() && payload[0] != '{' && payloadVersion(payload) == Nip44v2.EncryptedInfo.V

    /**
     * [decrypt] for an authenticated channel: NIP-44 v2 in its base64 form only. Throws
     * [IllegalArgumentException] for NIP-04 and NIP-44 v1 payloads, and for the JSON forms, which
     * [decrypt] still opens for old direct messages.
     */
    fun decryptV2Only(
        payload: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): String {
        require(isV2Payload(payload)) { "Not a NIP-44 v2 payload" }
        return v2.decrypt(payload, privateKey, pubKey)
    }

    /**
     * [decrypt] from a key used once, such as a gift wrap's throwaway key: a v2 payload's
     * conversation key is computed without entering the shared cache, where it could only evict a
     * real peer's. Other versions go through [decrypt].
     */
    fun decryptOnce(
        payload: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): String =
        if (isV2Payload(payload)) {
            v2.decrypt(payload, v2.computeConversationKey(privateKey, pubKey))
        } else {
            decrypt(payload, privateKey, pubKey)
        }

    /** [encrypt] to or from a key used once: the conversation key skips the shared cache (see [decryptOnce]). */
    fun encryptOnce(
        msg: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): Nip44v2.EncryptedInfo = v2.encrypt(msg, v2.computeConversationKey(privateKey, pubKey))

    // Always decrypt from any version/any encoding
    fun decrypt(
        payload: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): String {
        require(payload.isNotBlank()) { "Payload must not be blank" }
        return if (payload[0] == '{') {
            decryptNIP44FromJson(payload, privateKey, pubKey)
        } else {
            decryptNIP44FromBase64(payload, privateKey, pubKey)
        }
    }

    private fun decryptNIP44FromJson(
        json: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): String {
        // Ignores if it is not a valid json
        val info =
            try {
                JsonMapper.fromJson<EncryptedInfoString>(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unable to parse NIP-44 JSON: $json", e)
            }

        return when (info.v) {
            EncryptedInfo.V -> {
                val encryptedInfo =
                    EncryptedInfo(
                        ciphertext = Base64.decode(info.ciphertext),
                        nonce = Base64.decode(info.nonce),
                    )
                Nip04.decrypt(encryptedInfo, privateKey, pubKey)
            }

            Nip44v1.EncryptedInfo.V -> {
                val encryptedInfo =
                    Nip44v1.EncryptedInfo(
                        ciphertext = Base64.decode(info.ciphertext),
                        nonce = Base64.decode(info.nonce),
                    )
                v1.decrypt(encryptedInfo, privateKey, pubKey)
            }

            Nip44v2.EncryptedInfo.V -> {
                if (info.mac == null) {
                    throw IllegalArgumentException("Invalid NIP-44v2 JSON. Missing MAC: $json")
                }
                val encryptedInfo =
                    Nip44v2.EncryptedInfo(
                        ciphertext = Base64.decode(info.ciphertext),
                        nonce = Base64.decode(info.nonce),
                        mac = Base64.decode(info.mac),
                    )
                v2.decrypt(encryptedInfo, privateKey, pubKey)
            }

            else -> {
                throw IllegalArgumentException("Invalid or unsupported NIP-44 version code ${info.v}")
            }
        }
    }

    private fun decryptNIP44FromBase64(
        ciphertext: String,
        privateKey: ByteArray,
        pubKey: ByteArray,
    ): String {
        require(ciphertext.isNotBlank()) { "ciphertext must not be blank" }

        require(ciphertext.length >= 4) { "ciphertext is too short" }

        // Only the version byte is needed here: the first base64 quantum (4 chars) holds it.
        // Decoding the whole payload would allocate its full size just to be thrown away.
        val version = Base64.decode(ciphertext, 0, 4)[0].toInt()

        return when (version) {
            EncryptedInfo.V -> Nip04.decrypt(ciphertext, privateKey, pubKey)
            Nip44v1.EncryptedInfo.V -> v1.decrypt(ciphertext, privateKey, pubKey)
            Nip44v2.EncryptedInfo.V -> v2.decrypt(ciphertext, privateKey, pubKey)
            else -> throw IllegalArgumentException("Invalid or unsupported NIP-44 version code $version")
        }
    }
}
