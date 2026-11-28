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
package com.vitorpamplona.quartz.nip55AndroidSigner.client.handlers

import android.content.ContentResolver
import android.content.Intent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip55AndroidSigner.api.IResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.background.utils.isSupportedOrFalse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.IntentRequestManager
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.DecryptZapRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.DeriveKeyRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.Nip04DecryptRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.Nip04EncryptRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.Nip44DecryptRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.Nip44EncryptRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.SignPsbtRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.SignRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.DecryptZapResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.DeriveKeyResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.Nip04DecryptResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.Nip04EncryptResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.Nip44DecryptResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.Nip44EncryptResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.SignPsbtResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.SignResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.PayloadPlan
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Sends NIP-55 requests to the signer as Intents.
 *
 * With a [contentResolver], requests follow the NIP-55 large-payloads extension when the signer
 * supports it: a payload over [Nip55PayloadStreams.INLINE_THRESHOLD_BYTES] is staged on the
 * signer and the Intent carries its handle, and streamed results are read back. Without one,
 * every payload goes inline in the Intent data, as before.
 */
class ForegroundRequestHandler(
    val loggedInUser: HexKey,
    val packageName: String,
    foregroundApprovalTimeout: Long = 30000,
    contentResolver: ContentResolver? = null,
) {
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        loggedInUser: HexKey,
        packageName: String,
        foregroundApprovalTimeout: Long = 30000,
    ) : this(loggedInUser, packageName, foregroundApprovalTimeout, null)

    val payloadStreams = contentResolver?.let { Nip55PayloadStreams(packageName, it) }

    val launcher = IntentRequestManager(foregroundApprovalTimeout, payloadStreams)

    /**
     * Launches [assemble]'s Intent, rewritten for the large-payloads extension when the signer
     * supports it. [payload] is what [assemble] puts in the Intent data.
     */
    private suspend fun <T : IResult> launch(
        payload: String,
        assemble: () -> Intent,
        parser: (intent: IntentResult) -> SignerResult.RequestAddressed<T>,
        signatureOnly: Boolean = false,
    ): SignerResult.RequestAddressed<T> {
        val streams = payloadStreams
        if (streams == null || !launcher.hasForegroundActivity()) {
            return launcher.launchWaitAndParse(assemble, parser)
        }

        // A fresh handle when staged: the background query, if it ran, already consumed its own.
        val plan = withContext(Dispatchers.IO) { PayloadPlan.plan(payload, streams::isSupportedOrFalse, streams::stage) }

        return when (plan) {
            is PayloadPlan.Unsupported -> {
                launcher.launchWaitAndParse(assemble, parser)
            }

            is PayloadPlan.Failed -> {
                plan.toResult()
            }

            is PayloadPlan.Inline -> {
                launcher.launchWaitAndParse({ streams.extendIntent(assemble(), null) }, parser)
            }

            is PayloadPlan.Staged -> {
                try {
                    launcher.launchWaitAndParse({ streams.extendIntent(assemble(), plan.handle, signatureOnly) }, parser)
                } finally {
                    // The signer consumes the handle when it opens the request; this frees it if it never did.
                    withContext(NonCancellable + Dispatchers.IO) { streams.release(plan.handle) }
                }
            }
        }
    }

    suspend fun sign(unsignedEvent: Event) =
        launch(
            payload = unsignedEvent.toJson(),
            assemble = { SignRequest.assemble(unsignedEvent, loggedInUser, packageName) },
            parser = { intent -> SignResponse.parse(intent, unsignedEvent) },
            // A staged event is big; the signature alone rebuilds it, so don't ask for it back.
            signatureOnly = true,
        )

    suspend fun nip04Encrypt(
        plaintext: String,
        toPubKey: HexKey,
    ) = launch(
        payload = plaintext,
        assemble = { Nip04EncryptRequest.assemble(plaintext, toPubKey, loggedInUser, packageName) },
        parser = Nip04EncryptResponse::parse,
    )

    suspend fun nip04Decrypt(
        ciphertext: String,
        fromPubKey: HexKey,
    ) = launch(
        payload = ciphertext,
        assemble = { Nip04DecryptRequest.assemble(ciphertext, fromPubKey, loggedInUser, packageName) },
        parser = Nip04DecryptResponse::parse,
    )

    suspend fun nip44Encrypt(
        plaintext: String,
        toPubKey: HexKey,
    ) = launch(
        payload = plaintext,
        assemble = { Nip44EncryptRequest.assemble(plaintext, toPubKey, loggedInUser, packageName) },
        parser = Nip44EncryptResponse::parse,
    )

    suspend fun nip44Decrypt(
        ciphertext: String,
        fromPubKey: HexKey,
    ) = launch(
        payload = ciphertext,
        assemble = { Nip44DecryptRequest.assemble(ciphertext, fromPubKey, loggedInUser, packageName) },
        parser = Nip44DecryptResponse::parse,
    )

    suspend fun decryptZapEvent(event: ZapRequestEvent) =
        launch(
            payload = event.toJson(),
            assemble = { DecryptZapRequest.assemble(event, loggedInUser, packageName) },
            parser = DecryptZapResponse::parse,
        )

    suspend fun deriveKey(nonce: HexKey) =
        launcher.launchWaitAndParse(
            requestIntentBuilder = { DeriveKeyRequest.assemble(nonce, loggedInUser, packageName) },
            parser = DeriveKeyResponse::parse,
        )

    suspend fun signPsbt(psbtHex: String) =
        launch(
            payload = psbtHex,
            assemble = { SignPsbtRequest.assemble(psbtHex, loggedInUser, packageName) },
            parser = SignPsbtResponse::parse,
        )
}
