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
package com.vitorpamplona.quartz.nipFAAgentKeys

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A fixed event other implementations (Brainstorm's vitest) check against: signed and
 * encrypted by the test key `0x…01` (pubkey `79be667e…`), never a real secret. Any
 * implementation must verify it, decrypt it with that key, and read the same two keys.
 */
class AgentKeyVectorTest {
    @Test
    fun readsTheSharedVector() =
        runTest {
            val signer = NostrSignerInternal(KeyPair(TEST_PRIVATE_KEY.hexToByteArray()))
            val event = assertIs<AgentKeyEvent>(Event.fromJson(VECTOR))

            assertTrue(event.verify())
            assertEquals(signer.pubKey, event.pubKey)
            assertEquals(
                listOf(
                    AgentKeyTag("sk-or-v1-test-vector-not-a-real-key", "https://openrouter.ai/api/v1"),
                    AgentKeyTag("", "http://localhost:11434/v1"),
                ),
                event.agentKeys(signer),
            )
        }

    companion object {
        const val TEST_PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001"

        const val VECTOR = """{"id":"b58135caffa23430671f695d85268ac5a2c0858e431f0143aa42d64edf6eea96","pubkey":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","created_at":1794268800,"kind":12000,"tags":[["alt","AI agent keys (encrypted)"]],"content":"AjqAxU3ZRggimbtgKdX9GpN4Vmyxzoaqqp7Psxc7uG2OGDXb2wSGdS/ei2TaHSBmrepE9X3eTB5mIlOPK//4MLyVAdgWDbzb8OSoEfLZ7dzFx9eKtvVKEP07VxLf2RSGA6fipPPSOUclij1tWAy+6qsKaEa59udZ3eqYahHXjX1yMsCS50Bz17AgJ440o2X385+mfT9AT6es6FaNHtrv70FIihIlp4CtsARuW2Nk1qO+4W717CZpRrdTlCc9ieH1FG8A","sig":"acf3db20b3b0c5b8f19dfbda35e11b9f16f903aac806fc7be7d14d27484a324b1d539f6715ea31db59be6d50e95dd9933ab06c98cf3b9b54e628f24d5e43a756"}"""
    }
}
