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

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip60Cashu.bdhke.Bdhke
import com.vitorpamplona.quartz.nip60Cashu.token.CashuProof
import com.vitorpamplona.quartz.utils.secp256k1.Secp256k1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CashuMintOperations.dleqFailures]: which proofs fail NUT-12, for one `/v1/keys` fetch. */
class DleqFailuresTest {
    private val keysetId = "00ad268c4d1f5826"
    private val mintKey = Bdhke.randomScalar()
    private val mintPubHex = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(mintKey)).toHexKey()

    private inner class KeysOnlyMint : MintHttpTransport {
        var keyFetches = 0

        override suspend fun get(url: String): MintHttpResponse {
            keyFetches++
            return MintHttpResponse(200, """{"keysets":[{"id":"$keysetId","unit":"sat","keys":{"2":"$mintPubHex"}}]}""")
        }

        override suspend fun postJson(
            url: String,
            json: String,
        ): MintHttpResponse = MintHttpResponse(404, "{}")
    }

    /** A 2-sat proof the mint really signed, with a Carol DLEQ; [tamper] breaks its `e`. */
    private fun proof(tamper: Boolean = false): CashuProof {
        val secret = Bdhke.randomScalar().toHexKey()
        val r = Bdhke.randomScalar()
        val bPrime = Bdhke.blind(secret.encodeToByteArray(), r)
        val (cPrime, e, s) = Bdhke.signFull(bPrime, mintKey)
        val c = Bdhke.unblind(cPrime, r, mintPubHex.hexToByteArray())
        val eHex = if (tamper) Bdhke.randomScalar().toHexKey() else e.toHexKey()
        return CashuProof(id = keysetId, amount = 2, secret = secret, c = c.toHexKey(), dleq = DleqProofDto(e = eHex, s = s.toHexKey(), r = r.toHexKey()))
    }

    private fun ops(mint: KeysOnlyMint) = CashuMintOperations(MintHttpClient("https://mint.example.com", transport = mint))

    @Test
    fun `returns only the tampered proofs, with one keys fetch`() {
        val mint = KeysOnlyMint()
        val good = List(3) { proof() }
        val bad = List(2) { proof(tamper = true) }

        val failures = runBlocking { ops(mint).dleqFailures(good + bad) }

        assertEquals(bad.map { it.secret }.toSet(), failures.map { it.secret }.toSet())
        assertEquals(1, mint.keyFetches)
    }

    @Test
    fun `verifyTokenDleq agrees`() {
        val mint = KeysOnlyMint()
        assertTrue(runBlocking { ops(mint).verifyTokenDleq(listOf(proof(), proof())) })
        assertFalse(runBlocking { ops(mint).verifyTokenDleq(listOf(proof(), proof(tamper = true))) })
    }
}
