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
package com.vitorpamplona.quartz.nip46RemoteSigner.signer

/**
 * What a bunker's `error` to `nip04_decrypt` or `nip44_decrypt` means. NIP-46 gives errors no
 * codes, so the text decides:
 *
 * - a refusal (blank, or words such as `unauthorized`, `rejected`, `denied`, `not allowed`,
 *   `not connected`, which Quartz's own bunker and others answer when the user or a policy says
 *   no) is [SignerResult.RequestAddressed.Rejected]: final, the caller doesn't ask again;
 * - anything else is the bunker trying and failing (a wrong peer's MAC, bad padding, a
 *   ciphertext that isn't for this key), [SignerResult.RequestAddressed.ReceivedButCouldNotPerform]:
 *   the same as a local key's failed decryption, so a caller that holds several possible peers
 *   (a NIP-FB task's private tags) tries the next one.
 */
internal object BunkerDecryptError {
    private val REFUSALS =
        listOf(
            "unauthori",
            "not authori",
            "reject",
            "denied",
            "deny",
            "disallow",
            "not allowed",
            "forbidden",
            "permission",
            "refuse",
            "declin",
            "not connected",
            "unavailable",
        )

    fun isRefusal(error: String?): Boolean {
        if (error.isNullOrBlank()) return true
        val lower = error.lowercase()
        return REFUSALS.any { it in lower }
    }

    fun parse(error: String?): SignerResult.RequestAddressed<DecryptionResult> =
        if (isRefusal(error)) {
            SignerResult.RequestAddressed.Rejected(error)
        } else {
            SignerResult.RequestAddressed.ReceivedButCouldNotPerform(error)
        }
}
