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
package com.vitorpamplona.quartz.nip55AndroidSigner.api.background.queries

import android.content.ContentResolver
import androidx.core.net.toUri
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignPsbtResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.background.utils.query
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams

/**
 * NIP-BC `sign_psbt` background (ContentResolver) query.
 *
 * Passes the unsigned/partially-signed PSBT (lowercase hex) to the external
 * signer app and expects the updated PSBT back in the `result` column. The
 * signer signs each input whose `tapInternalKey` matches the user's pubkey;
 * it does NOT finalize the PSBT.
 */
class SignPsbtQuery(
    val loggedInUser: HexKey,
    val packageName: String,
    val contentResolver: ContentResolver,
) {
    val streams = Nip55PayloadStreams(packageName, contentResolver)

    val uri = "content://$packageName.${CommandType.SIGN_PSBT}".toUri()

    fun query(psbtHex: String): SignerResult<SignPsbtResult> =
        contentResolver.query(
            streams,
            uri,
            psbtHex,
            arrayOf(loggedInUser),
        ) { cursor ->
            val signedPsbtHex = streams.readColumn(cursor, "result", Nip55PayloadStreams.COLUMN_RESULT_URI)
            if (!signedPsbtHex.isNullOrBlank()) {
                SignerResult.RequestAddressed.Successful(SignPsbtResult(signedPsbtHex))
            } else {
                SignerResult.RequestAddressed.ReceivedButCouldNotPerform()
            }
        }
}
