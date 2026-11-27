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
package com.vitorpamplona.quartz.nip55AndroidSigner.api.background.utils

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import com.vitorpamplona.quartz.nip55AndroidSigner.api.IResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException

fun <T : IResult> ContentResolver.query(
    uri: Uri,
    projection: Array<out String>,
    map: (cursor: Cursor) -> SignerResult<T>,
): SignerResult<T> =
    try {
        query(
            uri,
            projection,
            null,
            null,
            null,
        ).use {
            if (it != null && it.moveToFirst()) {
                if (it.getColumnIndex("rejected") > -1) {
                    SignerResult.RequestAddressed.AutomaticallyRejected()
                } else {
                    map(it)
                }
            } else {
                SignerResult.RequestIncomplete.RequiresManualApproval()
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("ExternalSignerLauncher", "Failed to query the Signer app in the background", e)
        SignerResult.RequestIncomplete.ErrorExceptionCallingContentResolver(e)
    }

/**
 * [query] with the NIP-55 large-payloads extension: when the signer supports it, a [payload]
 * over the inline threshold is staged through [streams] and the projection carries its handle,
 * and the query opts into result handles, which [map] reads with [Nip55PayloadStreams.readColumn].
 * Signers without the extension get the plain query, [payload] inline.
 *
 * A payload that has to be staged but can't be is addressed as
 * [SignerResult.RequestAddressed.ReceivedButCouldNotPerform], not retried in the foreground: the
 * Intent would carry the same oversized payload.
 *
 * @param arguments the projection slots after the payload.
 * @param signatureOnly asks a staged `sign_event` to answer with the signature alone.
 */
fun <T : IResult> ContentResolver.query(
    streams: Nip55PayloadStreams,
    uri: Uri,
    payload: String,
    arguments: Array<out String>,
    signatureOnly: Boolean = false,
    map: (cursor: Cursor) -> SignerResult<T>,
): SignerResult<T> {
    val supported =
        try {
            streams.isSupported()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("ExternalSignerLauncher", "Failed to probe the Signer app for payload streams", e)
            false
        }

    if (!supported) return query(uri, arrayOf(payload, *arguments), map)

    if (!Nip55PayloadStreams.needsStaging(payload)) {
        return query(streams.extendQueryUri(uri, staged = false), arrayOf(payload, *arguments), map)
    }

    val handle =
        try {
            streams.stage(payload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("ExternalSignerLauncher", "Failed to stage a large payload on the Signer app", e)
            return SignerResult.RequestAddressed.ReceivedButCouldNotPerform("Could not stage the payload on the signer: ${e.message}")
        }

    return try {
        query(
            streams.extendQueryUri(uri, staged = true, signatureOnly = signatureOnly),
            arrayOf(handle.toString(), *arguments),
            map,
        )
    } finally {
        // The signer consumes the handle when it reads the query; this frees it if it didn't.
        streams.release(handle)
    }
}
