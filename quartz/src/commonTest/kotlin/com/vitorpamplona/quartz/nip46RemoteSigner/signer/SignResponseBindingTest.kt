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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponseEvent
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponsePublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A bunker's `sign_event` answer must be the template we sent, signed by the user's key. */
class SignResponseBindingTest {
    private val user = NostrSignerSync(KeyPair())
    private val asked = TextNoteEvent.build("asked", createdAt = 1_700_000_000L)

    private fun answer(event: Event) = SignResponse.parse(BunkerResponseEvent("r1", event), user.pubKey, asked)

    @Test
    fun theSignedTemplateIsAccepted() {
        assertIs<SignerResult.RequestAddressed.Successful<*>>(answer(user.sign(asked)))
    }

    @Test
    fun aValidlySignedDifferentEventIsRefused() {
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent<*>>(answer(user.sign(TextNoteEvent.build("else"))))
    }

    @Test
    fun theTemplateSignedByAnotherKeyIsRefused() {
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent<*>>(answer(NostrSignerSync(KeyPair()).sign(asked)))
    }

    @Test
    fun beforeTheUserKeyIsKnownTheTemplateStillBinds() {
        val other = NostrSignerSync(KeyPair())
        assertIs<SignerResult.RequestAddressed.Successful<*>>(SignResponse.parse(BunkerResponseEvent("r1", other.sign(asked)), null, asked))
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent<*>>(
            SignResponse.parse(BunkerResponseEvent("r1", other.sign(TextNoteEvent.build("else"))), null, asked),
        )
    }

    // Hex is case-insensitive: a bunker answering get_public_key in upper case still names the key
    // its events are signed with (lower case, as NIP-01 serializes it).
    @Test
    fun anUpperCasePublicKeyAnswerStillBindsTheSignature() {
        val answered = PubKeyResponse.parse(BunkerResponsePublicKey("k1", user.pubKey.uppercase()))
        assertIs<SignerResult.RequestAddressed.Successful<PublicKeyResult>>(answered)
        assertEquals(user.pubKey, answered.result.pubkey)

        val signed = user.sign(asked)
        assertIs<SignerResult.RequestAddressed.Successful<*>>(SignResponse.parse(BunkerResponseEvent("r1", signed), user.pubKey.uppercase(), asked))
    }

    // The binding is exact: a bunker that adds a tag or moves created_at before signing (some
    // NDK-based ones do) returns another event, and sign refuses it.
    @Test
    fun aTemplateTheBunkerChangedBeforeSigningIsRefused() {
        val retimed = TextNoteEvent.build("asked", createdAt = 1_700_000_001L)
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent<*>>(answer(user.sign(retimed)))
    }
}
