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
package com.vitorpamplona.quartz.nip55AndroidSigner.payloads

import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams.Companion.INLINE_THRESHOLD_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Nip55PayloadStreamsTest {
    private val signer = "com.greenart7c3.nostrsigner"
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun payloadsAtTheThresholdStayInline() {
        assertFalse(Nip55PayloadStreams.needsStaging("a".repeat(INLINE_THRESHOLD_BYTES)))
        assertTrue(Nip55PayloadStreams.needsStaging("a".repeat(INLINE_THRESHOLD_BYTES + 1)))
    }

    @Test
    fun theThresholdCountsUtf8BytesNotChars() {
        // 3 bytes each in UTF-8: a third of the threshold in chars is already over it.
        val cjk = "中".repeat(INLINE_THRESHOLD_BYTES / 3 + 1)
        assertTrue(cjk.length < INLINE_THRESHOLD_BYTES)
        assertTrue(Nip55PayloadStreams.needsStaging(cjk))
    }

    @Test
    fun utf8SizeMatchesTheEncoder() {
        val text = "aé中😀z"
        assertEquals(text.encodeToByteArray().size, Nip55PayloadStreams.utf8Size(text))
    }

    @Test
    fun acceptsOnlyTheSignersPayloadHandles() {
        assertTrue(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/$token", signer))

        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://other.app.PAYLOADS/$token", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.debug.PAYLOADS/$token", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("file:///data/data/app/shared_prefs/keys.xml", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.SIGN_EVENT/$token", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/${token.uppercase()}", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/$token/extra", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/../$token", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/$token?x=1", signer))
        assertFalse(Nip55PayloadStreams.isSignerPayloadUri("content://$signer.PAYLOADS/", signer))
    }

    @Test
    fun inlineQueriesOnlyOptIntoResultHandles() {
        assertEquals(
            "content://$signer.NIP44_DECRYPT?accept_result_uri=1",
            Nip55PayloadStreams.extendQueryUri("content://$signer.NIP44_DECRYPT", staged = false),
        )
    }

    @Test
    fun stagedQueriesFlagTheHandle() {
        assertEquals(
            "content://$signer.NIP44_DECRYPT?payload_in_uri=1&accept_result_uri=1",
            Nip55PayloadStreams.extendQueryUri("content://$signer.NIP44_DECRYPT", staged = true),
        )
    }

    @Test
    fun stagedSignaturesAskForTheSignatureAlone() {
        assertEquals(
            "content://$signer.SIGN_EVENT?payload_in_uri=1&accept_result_uri=1&return_type=signature",
            Nip55PayloadStreams.extendQueryUri("content://$signer.SIGN_EVENT", staged = true, signatureOnly = true),
        )
    }
}
