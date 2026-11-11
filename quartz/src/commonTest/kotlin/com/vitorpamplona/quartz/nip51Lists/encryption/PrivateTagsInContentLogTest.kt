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
package com.vitorpamplona.quartz.nip51Lists.encryption

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.LogLevel
import com.vitorpamplona.quartz.utils.LogSink
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateTagsInContentLogTest {
    private val originalSink = Log.sink
    private val originalMinLevel = Log.minLevel

    @AfterTest
    fun restore() {
        Log.sink = originalSink
        Log.minLevel = originalMinLevel
    }

    @Test
    fun anUnparseablePlaintextIsNeverLogged() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val secret = "sk-or-v1-supersecret"
            val ciphertext = signer.nip44Encrypt("""[["key","$secret","https://openrouter.ai/api/v1"]""", signer.pubKey)
            val logged = StringBuilder()
            Log.minLevel = LogLevel.DEBUG
            Log.sink = LogSink { _, _, message, throwable -> logged.append(message).append(throwable?.message.orEmpty()) }

            assertFailsWith<Exception> { PrivateTagsInContent.decrypt(ciphertext, signer) }

            assertTrue(logged.isNotEmpty())
            assertFalse(logged.contains(secret))
        }
}
