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
package com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads

import com.vitorpamplona.quartz.nip55AndroidSigner.api.IResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException

/**
 * How a NIP-55 request carries its payload under the large-payloads extension. The background
 * query and the foreground Intent both follow it, so they can't drift apart.
 *
 * @param H the staged handle's type (a `Uri` in production).
 */
internal sealed interface PayloadPlan<out H> {
    /** The signer doesn't export `PAYLOADS`: send the request exactly as before the extension. */
    object Unsupported : PayloadPlan<Nothing>

    /** The payload goes inline, and the request opts into streamed results. */
    object Inline : PayloadPlan<Nothing>

    /** The payload was staged at [handle], which the request carries instead. */
    class Staged<H>(
        val handle: H,
    ) : PayloadPlan<H>

    /** The payload had to be staged, couldn't be, and is too big to go inline. */
    class Failed(
        val error: Exception,
    ) : PayloadPlan<Nothing> {
        fun <T : IResult> toResult(): SignerResult.RequestAddressed<T> = SignerResult.RequestAddressed.ReceivedButCouldNotPerform("Could not stage the payload on the signer: ${error.message}")
    }

    companion object {
        private const val TAG = "NIP55"

        /**
         * Picks the plan for [payload]. [isSupported] probes the signer (and must not throw, see
         * `isSupportedOrFalse`); [stage] uploads the payload and throws when it can't.
         *
         * A payload whose staging fails still goes inline when it fits
         * ([Nip55PayloadStreams.fitsInline]). Staging also fails when the signer stopped exporting
         * `PAYLOADS`, so that fallback probes again and drops to [Unsupported] if it's gone: an old
         * signer wouldn't match the extended request.
         */
        fun <H> plan(
            payload: String,
            isSupported: () -> Boolean,
            stage: (String) -> H,
        ): PayloadPlan<H> {
            if (!isSupported()) return Unsupported
            if (!Nip55PayloadStreams.needsStaging(payload)) return Inline

            return try {
                Staged(stage(payload))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (Nip55PayloadStreams.fitsInline(payload)) {
                    Log.w(TAG, "Failed to stage a large payload on the Signer app; sending it inline", e)
                    if (isSupported()) Inline else Unsupported
                } else {
                    // Too big for a query projection or an Intent: Binder would throw.
                    Log.w(TAG, "Failed to stage a large payload on the Signer app", e)
                    Failed(e)
                }
            }
        }

        /**
         * Wraps a cursor or Intent [map] so that a streamed result it can't read is final
         * ([SignerResult.RequestAddressed.ReceivedButCouldNotPerform]) instead of an IPC error: the
         * signer already performed the request, so falling back to the foreground would only ask
         * the user again.
         */
        fun <C, T : IResult> readFailuresAreFinal(map: (C) -> SignerResult<T>): (C) -> SignerResult<T> =
            { source ->
                try {
                    map(source)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Could not read the signer's streamed result", e)
                    SignerResult.RequestAddressed.ReceivedButCouldNotPerform("Could not read the signer's streamed result")
                }
            }
    }
}
