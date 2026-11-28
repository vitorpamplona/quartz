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

import com.vitorpamplona.quartz.nip55AndroidSigner.api.DecryptionResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams.Companion.INLINE_THRESHOLD_BYTES
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams.Companion.MAX_INLINE_FALLBACK_BYTES
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.PayloadPlan
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The decisions the background query and the foreground Intent share under the NIP-55
 * large-payloads extension. The probe and the upload are fakes, so every branch runs here,
 * including the fallbacks a real signer only takes when its quota is full or it was downgraded.
 */
class PayloadPlanTest {
    private val small = "a".repeat(1_000)

    /** Over the staging threshold, under the inline fallback cap. */
    private val medium = "a".repeat(200_000)

    /** Over the inline fallback cap: can't go inline at all. */
    private val huge = "a".repeat(MAX_INLINE_FALLBACK_BYTES + 1)

    private val handle = "content://com.example.signer.PAYLOADS/0123456789abcdef0123456789abcdef"

    /** A probe that answers [answers] in order, and counts how often it was asked. */
    private class Probe(
        vararg val answers: Boolean,
    ) {
        var calls = 0

        fun isSupported(): Boolean = answers[minOf(calls++, answers.size - 1)]
    }

    /** An upload that returns [handle] or throws [error], and counts how often it ran. */
    private class Upload(
        val handle: String? = null,
        val error: Exception? = null,
    ) {
        var calls = 0

        fun stage(payload: String): String {
            calls++
            error?.let { throw it }
            return handle!!
        }
    }

    @Test
    fun aSignerWithoutTheExtensionGetsTheOldRequest() {
        val upload = Upload(handle)
        assertSame(PayloadPlan.Unsupported, PayloadPlan.plan(huge, Probe(false)::isSupported, upload::stage))
        assertEquals(0, upload.calls)
    }

    @Test
    fun aSmallPayloadGoesInlineWithoutStaging() {
        val upload = Upload(handle)
        assertSame(PayloadPlan.Inline, PayloadPlan.plan(small, Probe(true)::isSupported, upload::stage))
        assertSame(PayloadPlan.Inline, PayloadPlan.plan("a".repeat(INLINE_THRESHOLD_BYTES), Probe(true)::isSupported, upload::stage))
        assertEquals(0, upload.calls)
    }

    @Test
    fun aPayloadOverTheThresholdIsStaged() {
        val upload = Upload(handle)
        val plan = PayloadPlan.plan(medium, Probe(true)::isSupported, upload::stage)
        assertIs<PayloadPlan.Staged<String>>(plan)
        assertEquals(handle, plan.handle)
        assertEquals(1, upload.calls)
    }

    @Test
    fun theThresholdCountsUtf8Bytes() {
        // A third of the threshold in chars, but over it in bytes.
        val cjk = "中".repeat(INLINE_THRESHOLD_BYTES / 3 + 1)
        assertIs<PayloadPlan.Staged<String>>(PayloadPlan.plan(cjk, Probe(true)::isSupported, Upload(handle)::stage))
    }

    @Test
    fun aFailedStageGoesInlineWhenThePayloadFits() {
        val probe = Probe(true, true)
        val plan = PayloadPlan.plan(medium, probe::isSupported, Upload(error = IOException("quota full"))::stage)
        assertSame(PayloadPlan.Inline, plan)
        assertEquals(2, probe.calls, "the fallback probes again")
    }

    @Test
    fun aFailedStageOnASignerThatDroppedTheExtensionSendsTheOldRequest() {
        // The first probe was a cached yes; the signer was downgraded, so insert threw and the
        // second probe says no: an old signer wouldn't match the extended request.
        val plan = PayloadPlan.plan(medium, Probe(true, false)::isSupported, Upload(error = IOException("no longer exports PAYLOADS"))::stage)
        assertSame(PayloadPlan.Unsupported, plan)
    }

    @Test
    fun aFailedStageOfAPayloadTooBigToGoInlineFails() {
        val error = IOException("quota full")
        val probe = Probe(true)
        val plan = PayloadPlan.plan(huge, probe::isSupported, Upload(error = error)::stage)
        assertIs<PayloadPlan.Failed>(plan)
        assertSame(error, plan.error)
        assertEquals(1, probe.calls, "no fallback, so no second probe")

        val result = plan.toResult<DecryptionResult>()
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotPerform<DecryptionResult>>(result)
        assertEquals("Could not stage the payload on the signer: quota full", result.message)
    }

    @Test
    fun aCancelledStageIsNotTurnedIntoAFallback() {
        assertFailsWith<CancellationException> {
            PayloadPlan.plan(medium, Probe(true)::isSupported, Upload(error = CancellationException("cancelled"))::stage)
        }
    }

    @Test
    fun aResultThatCanBeReadPassesThrough() {
        val success = SignerResult.RequestAddressed.Successful(DecryptionResult("hi"))
        val map = PayloadPlan.readFailuresAreFinal<String, DecryptionResult> { success }
        assertSame(success, map("cursor"))
    }

    @Test
    fun aStreamedResultThatCantBeReadIsFinal() {
        val map = PayloadPlan.readFailuresAreFinal<String, DecryptionResult> { throw IOException("handle expired") }
        // RequestAddressed, not RequestIncomplete: NostrSignerExternal must not ask the user again.
        assertIs<SignerResult.RequestAddressed.ReceivedButCouldNotPerform<DecryptionResult>>(map("cursor"))
    }

    @Test
    fun aCancelledReadIsNotTurnedIntoAResult() {
        val map = PayloadPlan.readFailuresAreFinal<String, DecryptionResult> { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { map("cursor") }
    }
}
