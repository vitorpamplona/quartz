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
package com.vitorpamplona.quartz.nip60Cashu.mintApi

import com.vitorpamplona.quartz.nip60Cashu.token.CashuProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CashuMintOperations.swap] when the NUT-02 fee takes everything: it must refuse before the
 * mint sees an empty `outputs` list, which mints reject as "no outputs provided".
 */
class SwapFeeExceedsInputsTest {
    private val keyset = "01182e5f5e35aecd"

    /** A mint whose only keyset charges [feePpk]; records every request it gets. */
    private class FakeMint(
        private val keysetId: String,
        private val feePpk: Long,
    ) : MintHttpTransport {
        val posts = mutableListOf<String>()
        val gets = mutableListOf<String>()

        override suspend fun get(url: String): MintHttpResponse {
            gets += url.substringAfter("mint.example.com")
            return when {
                url.endsWith("/v1/keysets") -> {
                    MintHttpResponse(200, """{"keysets":[{"id":"$keysetId","unit":"sat","active":true,"input_fee_ppk":$feePpk}]}""")
                }

                url.endsWith("/v1/keys") -> {
                    MintHttpResponse(200, """{"keysets":[{"id":"$keysetId","unit":"sat","keys":{}}]}""")
                }

                else -> {
                    MintHttpResponse(404, "{}")
                }
            }
        }

        override suspend fun postJson(
            url: String,
            json: String,
        ): MintHttpResponse {
            posts += url
            return MintHttpResponse(400, """{"detail":"no outputs provided","code":11000}""")
        }
    }

    private fun ops(mint: FakeMint) = CashuMintOperations(MintHttpClient("https://mint.example.com", transport = mint))

    private fun proofs(vararg amounts: Long) = amounts.mapIndexed { i, a -> CashuProof(id = keyset, amount = a, secret = "s$i", c = "c$i") }

    @Test
    fun `a 1-sat proof on a 100 ppk keyset never reaches the mint`() {
        val mint = FakeMint(keyset, feePpk = 100)

        val error =
            assertThrows(SwapFeeExceedsInputsException::class.java) {
                runBlocking { ops(mint).swap(proofs(1)) }
            }

        assertEquals(1L, error.inputTotal)
        assertEquals(1L, error.fee)
        assertTrue("no swap may be posted, got ${mint.posts}", mint.posts.isEmpty())
        // The refusal needs only the fee table, not the active keyset's keys.
        assertEquals(listOf("/v1/keysets"), mint.gets)
    }

    @Test
    fun `a fee larger than the inputs is the same refusal`() {
        // 1 sat at 2000 ppk: the fee (2) exceeds the input (1).
        val mint = FakeMint(keyset, feePpk = 2000)

        val error =
            assertThrows(SwapFeeExceedsInputsException::class.java) {
                runBlocking { ops(mint).swap(proofs(1)) }
            }

        assertEquals(2L, error.fee)
        assertTrue(mint.posts.isEmpty())
    }

    @Test
    fun `it is still an IllegalArgumentException for existing callers`() {
        assertTrue(IllegalArgumentException::class.java.isAssignableFrom(SwapFeeExceedsInputsException::class.java))
    }

    @Test
    fun `batched 1-sat proofs clear the fee and do reach the mint`() {
        // Ten 1-sat proofs at 100 ppk: fee is ceil(1000/1000) = 1, so 9 sats are left to blind.
        // The fake mint has no keys, so building outputs fails after the fee check, which is
        // past the point under test; the fee refusal must not fire.
        val mint = FakeMint(keyset, feePpk = 100)

        val error = runCatching { runBlocking { ops(mint).swap(proofs(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)) } }.exceptionOrNull()

        assertFalse("batched proofs must clear the fee check, got $error", error is SwapFeeExceedsInputsException)
    }

    @Test
    fun `DLEQ verification makes no request when no proof carries a DLEQ`() {
        val mint = FakeMint(keyset, feePpk = 100)

        val ok = runBlocking { ops(mint).verifyTokenDleq(proofs(1, 2, 4)) }

        assertTrue(ok)
        assertTrue("nothing to verify, so nothing to fetch, got ${mint.gets}", mint.gets.isEmpty())
    }
}
