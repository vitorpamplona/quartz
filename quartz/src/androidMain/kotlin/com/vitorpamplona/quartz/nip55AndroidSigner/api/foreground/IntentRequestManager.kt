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
package com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground

import android.content.ActivityNotFoundException
import android.content.Intent
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip55AndroidSigner.api.IResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.cache.LargeCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the lifecycle of foreground signing requests in a NIP-55 compliant Android signer flow.
 *
 * - Tracks pending signing requests by a unique call id via a per-request [Channel].
 * - Provides a way to launch foreground Intents (to request user approval) and wait for the response.
 * - Handles approval timeouts via [withTimeoutOrNull].
 * - Collects results via [newResponse] when the user responds to the foreground request.
 *
 * Key usage flow: request initiated -> store channel by id -> launch intent -> withTimeoutOrNull
 * receive -> finally cleanup. User responds -> atomic remove + trySend on the channel.
 *
 * Duplicate, unknown, or late deliveries (e.g. if the activity surfaces multiple results for the
 * same id) atomically read out as null and are dropped after a debug log line — no continuation
 * is ever resumed twice.
 *
 * With [payloadStreams], results the signer moved to handles (the NIP-55 large-payloads
 * extension: `result_uri`, `event_uri`, `results_uri`) are read on [Dispatchers.IO], never on the
 * thread that calls [newResponse].
 */
class IntentRequestManager(
    val foregroundApprovalTimeout: Long = 30000,
    val payloadStreams: Nip55PayloadStreams? = null,
) {
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(foregroundApprovalTimeout: Long = 30000) : this(foregroundApprovalTimeout, null)

    val activityNotFoundIntent = Intent()

    /** A result as delivered, before any streamed field is read. */
    private class Delivery(
        val result: IntentResult,
        val resultUri: String? = null,
        val eventUri: String? = null,
    )

    private val pending = LargeCache<String, Channel<Delivery>>()

    private val streamReads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appLauncher: ((Intent) -> Unit)? = null

    /** Call this function when the launcher becomes available on activity, fragment or compose */
    fun registerForegroundLauncher(launcher: ((Intent) -> Unit)) {
        this.appLauncher = launcher
    }

    /** Call this function when the activity is destroyed or is about to be replaced. */
    fun unregisterForegroundLauncher(launcher: ((Intent) -> Unit)) {
        if (this.appLauncher == launcher) {
            this.appLauncher = null
        }
    }

    fun newResponse(data: Intent) {
        val results = data.getStringExtra("results")
        if (results != null) {
            // This happens when the intent responds to many requests at the same time.
            dispatchBatch(results)
            return
        }

        val resultsUri = data.getStringExtra(Nip55PayloadStreams.EXTRA_RESULTS_URI)
        if (resultsUri != null) {
            val streams = payloadStreams
            if (streams == null) {
                Log.w("NIP55") { "dropping a batch result handle: no payload streams to read it with" }
                return
            }
            streamReads.launch {
                try {
                    dispatchBatch(streams.read(resultsUri))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("NIP55", "Could not read the signer's batch result handle", e)
                }
            }
            return
        }

        val result = IntentResult.fromIntent(data)
        if (result.id != null) {
            dispatch(
                result.id,
                Delivery(
                    result = result,
                    resultUri = data.getStringExtra(Nip55PayloadStreams.EXTRA_RESULT_URI),
                    eventUri = data.getStringExtra(Nip55PayloadStreams.EXTRA_EVENT_URI),
                ),
            )
        }
    }

    private fun dispatchBatch(results: String) {
        IntentResult.fromJsonArray(results).forEach { result ->
            if (result.id != null) dispatch(result.id, Delivery(result))
        }
    }

    private fun dispatch(
        id: String,
        delivery: Delivery,
    ) {
        val channel = pending.remove(id)
        if (channel == null) {
            Log.d("NIP55") { "no channel for intent result id=$id (duplicate, unknown, or late)" }
            return
        }
        channel.trySend(delivery)
    }

    /** Replaces streamed fields with their contents. Null when a handle can't be read. */
    private suspend fun resolve(delivery: Delivery): IntentResult? {
        if (delivery.resultUri == null && delivery.eventUri == null) return delivery.result
        val streams = payloadStreams ?: return null
        return withContext(Dispatchers.IO) {
            try {
                delivery.result.copy(
                    result = delivery.resultUri?.let { streams.read(it) } ?: delivery.result.result,
                    event = delivery.eventUri?.let { streams.read(it) } ?: delivery.result.event,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("NIP55", "Could not read the signer's result handle", e)
                null
            }
        }
    }

    fun hasForegroundActivity() = appLauncher != null

    /**
     * Launches the signer, waits and parses the result.
     *
     * @param requestIntentBuilder Builder for the Intent to be launched.
     * @param parser A function that parses the response Intent into a [SignerResult.RequestAddressed<T>].
     * @return The result after parsing the Intent using the provided parser.
     */
    suspend fun <T : IResult> launchWaitAndParse(
        requestIntentBuilder: () -> Intent,
        parser: (intent: IntentResult) -> SignerResult.RequestAddressed<T>,
    ): SignerResult.RequestAddressed<T> {
        val launcher = appLauncher ?: return SignerResult.RequestAddressed.NoActivityToLaunchFrom()

        val requestIntent = requestIntentBuilder()
        val callId = RandomInstance.randomChars(32)

        requestIntent.putExtra("id", callId)
        requestIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val channel = Channel<Delivery>(capacity = 1)
        pending.put(callId, channel)

        return try {
            try {
                launcher.invoke(requestIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("ExternalSigner", "Error launching intent: Signer not found", e)
                return SignerResult.RequestAddressed.SignerNotFound()
            }

            val delivery = withTimeoutOrNull(foregroundApprovalTimeout) { channel.receive() }
            when (delivery) {
                null -> SignerResult.RequestAddressed.TimedOut()
                else -> {
                    val resultIntent = resolve(delivery)
                    if (resultIntent == null) {
                        SignerResult.RequestAddressed.ReceivedButCouldNotPerform("Could not read the signer's streamed result")
                    } else {
                        parser(resultIntent)
                    }
                }
            }
        } finally {
            pending.remove(callId)
            channel.close()
        }
    }
}
