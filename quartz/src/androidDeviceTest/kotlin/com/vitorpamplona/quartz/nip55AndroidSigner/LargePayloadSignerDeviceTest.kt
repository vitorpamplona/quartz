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
package com.vitorpamplona.quartz.nip55AndroidSigner

import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.PubKeyResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads.Nip55PayloadStreams
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the NIP-55 large-payloads extension against a real signer on the device.
 * Interactive: approve the login (and choose to remember the permissions) in the signer.
 *
 * ```
 * ./gradlew :quartz:connectedAndroidDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.vitorpamplona.quartz.nip55AndroidSigner.LargePayloadSignerDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.signer=com.greenart7c3.nostrsigner.debug
 * ```
 *
 * Skipped when the signer isn't installed. Nothing is published.
 */
@RunWith(AndroidJUnit4::class)
class LargePayloadSignerDeviceTest {
    private val tag = "LargePayloadTest"

    @Test
    fun largePayloadsRoundTripThroughTheSigner() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val signerPackage = InstrumentationRegistry.getArguments().getString("signer") ?: "com.greenart7c3.nostrsigner.debug"

            val installed =
                try {
                    context.packageManager.getPackageInfo(signerPackage, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            assumeTrue("$signerPackage is not installed", installed)

            val streams = Nip55PayloadStreams(signerPackage, context.contentResolver)
            assertTrue(streams.isSupported(), "$signerPackage does not export ${streams.authority}")

            ActivityScenario.launch(SignerHostActivity::class.java).use { scenario ->
                val login = CompletableDeferred<SignerResult<PubKeyResult>>()
                SignerHostActivity.onResult = { login.complete(ExternalSignerLogin.parseResult(it)) }
                val permissions =
                    listOf(
                        Permission(CommandType.SIGN_EVENT, 1),
                        Permission(CommandType.NIP04_ENCRYPT),
                        Permission(CommandType.NIP04_DECRYPT),
                    )
                scenario.onActivity { it.launch(ExternalSignerLogin.createIntent(permissions, signerPackage)) }
                val loginResult = withTimeout(180_000) { login.await() }
                assertTrue(loginResult is SignerResult.RequestAddressed.Successful<PubKeyResult>, "login failed: $loginResult")
                val pubKey = loginResult.result.pubkey

                val signer = NostrSignerExternal(pubKey, signerPackage, context.contentResolver)
                SignerHostActivity.onResult = { signer.newResponse(it) }
                signer.registerForegroundLauncher { intent -> scenario.onActivity { it.launch(intent) } }

                // Inline, with the result-handle opt-in.
                checkSign(signer, "small note")

                // Over the inline threshold, under Binder's limit.
                checkSign(signer, "a".repeat(300_000))

                // Over Binder's 1 MB limit: impossible without the extension.
                checkSign(signer, "b".repeat(1_500_000))

                // NIP-04 has no size cap, so it exercises a large ciphertext and plaintext both ways.
                val plaintext = "c".repeat(300_000)
                val start = System.currentTimeMillis()
                val ciphertext = signer.nip04Encrypt(plaintext, pubKey)
                Log.i(tag) { "nip04_encrypt ${plaintext.length} -> ${ciphertext.length} chars" }
                assertTrue(ciphertext.length > Nip55PayloadStreams.INLINE_THRESHOLD_BYTES)
                assertEquals(plaintext, signer.nip04Decrypt(ciphertext, pubKey))
                Log.i(tag) { "nip04 round trip of ${plaintext.length} chars took ${System.currentTimeMillis() - start} ms" }
            }
        }

    private suspend fun checkSign(
        signer: NostrSignerExternal,
        content: String,
    ) {
        val start = System.currentTimeMillis()
        val event = signer.sign<Event>(1_700_000_000L, 1, emptyArray(), content)
        assertTrue(event.verify(), "signature does not verify")
        assertEquals(content, event.content)
        assertEquals(signer.pubKey, event.pubKey)
        Log.i(tag) { "signed a kind 1 with ${content.length} chars in ${System.currentTimeMillis() - start} ms" }
    }
}
