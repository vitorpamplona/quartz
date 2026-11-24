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
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CashuMintOperations] with proofs under a NUT-00 short keyset id: the first 8 bytes of the
 * 33-byte id, which a v4 token may carry. The mint never sees the short form. As modelled here,
 * after mint.minibits.cash/Bitcoin, it lists only the full id in `/v1/keysets` and refuses the
 * short one on `/v1/swap` and `/v1/melt/bolt11` inputs ("NUT02: ID length invalid").
 */
class ShortKeysetIdTest {
    private val shortId = "01fc0ec0e59cd6fa"
    private val fullId = "01fc0ec0e59cd6fa01b7a88f8cd77fce81fd1e64bca67d752e984992b7a3c3a821"

    /** Another keyset extending the same short id: a collision NUT-00 says to fail on. */
    private val collidingId = shortId + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

    /** A v1 id is 16 hex characters too, and complete: listed as it is, never a prefix of a v2 id. */
    private val v1Id = "0088d5f7c3a9b2f4"

    private val amounts = listOf(1L, 2L, 4L, 8L, 16L)
    private val privKeys = amounts.associateWith { Bdhke.randomScalar() }
    private val pubKeys = privKeys.mapValues { (_, k) -> Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(k)).toHexKey() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun keyset(
        id: String,
        inputFeePpk: Long = 0L,
    ) = KeysetSummaryDto(id = id, unit = "sat", active = true, inputFeePpk = inputFeePpk)

    /**
     * The mint, listing [keysets]. Its inputs are validated the way the real one validates
     * them: every id must be a keyset it lists, spelled in full.
     */
    private inner class Mint(
        vararg keysets: KeysetSummaryDto,
    ) : MintHttpTransport {
        var listed: List<KeysetSummaryDto> = keysets.toList()
        val gets = mutableListOf<String>()
        val postedInputIds = mutableListOf<List<String>>()

        override suspend fun get(url: String): MintHttpResponse {
            val path = url.substringAfter("mint.example.com")
            gets += path
            return when (path) {
                "/v1/keysets" -> MintHttpResponse(200, json.encodeToString(KeysetListResponseDto.serializer(), KeysetListResponseDto(listed)))
                "/v1/keys" -> keys(fullId)
                // The parameter is not NUT-01. Left in to show that nothing relies on it.
                else -> if (path.startsWith("/v1/keys")) keys(fullId) else MintHttpResponse(404, "{}")
            }
        }

        private fun keys(id: String): MintHttpResponse = MintHttpResponse(200, json.encodeToString(KeysResponseDto.serializer(), KeysResponseDto(listOf(KeysetDto(id = id, unit = "sat", keys = pubKeys.mapKeys { it.key.toString() })))))

        override suspend fun postJson(
            url: String,
            body: String,
        ): MintHttpResponse {
            val path = url.substringAfter("mint.example.com")
            val listedIds = listed.map { it.id }
            return when (path) {
                "/v1/swap" -> {
                    val request = json.decodeFromString(SwapRequestDto.serializer(), body)
                    postedInputIds += request.inputs.map { it.id }
                    request.inputs.firstOrNull { it.id !in listedIds }?.let { return refused() }
                    val signatures =
                        request.outputs.map { out ->
                            BlindSignatureDto(amount = out.amount, id = out.id, cTick = Bdhke.sign(out.bTick.hexToByteArray(), privKeys.getValue(out.amount)).toHexKey())
                        }
                    MintHttpResponse(200, json.encodeToString(SwapResponseDto.serializer(), SwapResponseDto(signatures)))
                }

                "/v1/melt/bolt11" -> {
                    val request = json.decodeFromString(MeltBolt11RequestDto.serializer(), body)
                    postedInputIds += request.inputs.map { it.id }
                    request.inputs.firstOrNull { it.id !in listedIds }?.let { return refused() }
                    MintHttpResponse(200, json.encodeToString(MeltBolt11ResponseDto.serializer(), MeltBolt11ResponseDto(state = "PAID", paid = true)))
                }

                else -> MintHttpResponse(404, "{}")
            }
        }

        private fun refused() = MintHttpResponse(422, """{"code":20003,"detail":"NUT02: ID length invalid"}""")
    }

    private fun ops(mint: Mint) = CashuMintOperations(MintHttpClient("https://mint.example.com", transport = mint))

    /** A real proof the mint signed under its sat keyset, carried under [id]. */
    private fun proof(
        amount: Long,
        id: String = shortId,
    ): CashuProof {
        val secret = Bdhke.randomScalar().toHexKey()
        val r = Bdhke.randomScalar()
        val cPrime = Bdhke.sign(Bdhke.blind(secret.encodeToByteArray(), r), privKeys.getValue(amount))
        val c = Bdhke.unblind(cPrime, r, pubKeys.getValue(amount).hexToByteArray())
        return CashuProof(id = id, amount = amount, secret = secret, c = c.toHexKey())
    }

    private fun quote(
        amount: Long,
        feeReserve: Long,
    ) = MeltQuoteBolt11ResponseDto(quote = "quote-id", amount = amount, feeReserve = feeReserve, state = "UNPAID")

    @Test
    fun `swap posts a short id under the full keyset id it prefixes`() {
        val mint = Mint(keyset(fullId))

        val result = runBlocking { ops(mint).swap(listOf(proof(4))) }

        assertEquals(listOf(listOf(fullId)), mint.postedInputIds)
        // The swap went through end to end: its outputs unblinded.
        assertEquals(4L, result.keep.sumOf { it.amount })
        // One keyset list for the fee, which is what resolved the id; no other lookup.
        assertEquals(listOf("/v1/keysets", "/v1/keys"), mint.gets)
    }

    @Test
    fun `the fee follows the keyset a short id resolves to`() {
        val mint = Mint(keyset(fullId, inputFeePpk = 100))

        val result = runBlocking { ops(mint).swap(listOf(proof(8))) }

        // One input at 100 ppk: 0.1 sat, rounded up. Priced under the unresolved short id,
        // the proof would have matched no keyset and been charged nothing.
        assertEquals(7L, result.keep.sumOf { it.amount })
    }

    @Test
    fun `proofs under mixed id forms all go out under their full ids`() {
        val mint = Mint(keyset(fullId), keyset(v1Id))

        runBlocking { ops(mint).swap(listOf(proof(1), proof(2, fullId), proof(4, v1Id))) }

        assertEquals(listOf(listOf(fullId, fullId, v1Id)), mint.postedInputIds)
    }

    @Test
    fun `melt posts a short id under the full keyset id it prefixes`() {
        val mint = Mint(keyset(fullId))

        runBlocking { ops(mint).meltProofs(quote(1, feeReserve = 1), listOf(proof(2)), requestChange = false) }

        assertEquals(listOf(listOf(fullId)), mint.postedInputIds)
    }

    @Test
    fun `an ambiguous short id fails before anything is sent`() {
        val mint = Mint(keyset(fullId), keyset(collidingId))

        val error =
            assertThrows(AmbiguousKeysetIdException::class.java) {
                runBlocking { ops(mint).swap(listOf(proof(1))) }
            }

        assertEquals(shortId, error.keysetId)
        assertEquals(setOf(fullId, collidingId), error.candidates.toSet())
        assertTrue(error.message.orEmpty().contains(shortId))
        assertTrue(mint.postedInputIds.isEmpty())
    }

    @Test
    fun `a short id the mint doesn't extend is left for the mint to refuse`() {
        val mint = Mint(keyset(fullId))
        val unknown = "deadbeefdeadbeef"

        val error =
            assertThrows(MintHttpException::class.java) {
                runBlocking { ops(mint).swap(listOf(proof(1, unknown))) }
            }

        // Sent as it came, so the refusal is the mint's own.
        assertEquals(422, error.httpStatus)
        assertEquals(listOf(listOf(unknown)), mint.postedInputIds)
    }

    @Test
    fun `nothing sticks between swaps`() {
        val mint = Mint(keyset(fullId))

        runBlocking {
            val operations = ops(mint)
            operations.swap(listOf(proof(2)))
            // The mint rotates to another keyset extending the same short id.
            mint.listed = listOf(keyset(collidingId))
            operations.swap(listOf(proof(2)))
        }

        assertEquals(listOf(listOf(fullId), listOf(collidingId)), mint.postedInputIds)
    }
}
