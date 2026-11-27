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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CashuMintOperations.meltProofs] change, against a mint that follows NUT-08: the wallet sends
 * blank outputs and the mint imprints its refund of the unspent fees — split into power-of-two
 * denominations it picks itself — into the leading ones, returning those signatures in output
 * order and keeping what doesn't fit. That is what nutshell does, and what mint.minibits.cash
 * did when a 2-sat refund came back as two 1-sat signatures.
 *
 * The regression under test: the old matcher paired a signature with the first output whose
 * amount it echoed, and threw when there was none — after the melt had already paid, discarding
 * the refund. Paired by amount, a 17-sat refund imprinted `[16, 1]` also unblinds the 16-sat
 * signature with the 16-sat output's `r`: a garbage proof, and no error until it is spent.
 */
class MeltChangeMatchingTest {
    private val keysetId = "00e5d1f7c3a9b2f4"
    private val otherKeysetId = "00f6e2a8d4b0c5a7"

    private val amounts = listOf(1L, 2L, 4L, 8L, 16L)
    private val privKeys = amounts.associateWith { Bdhke.randomScalar() }
    private val otherPrivKeys = amounts.associateWith { Bdhke.randomScalar() }

    private fun publicKeys(priv: Map<Long, ByteArray>): Map<String, String> = amounts.associate { amount -> amount.toString() to Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(priv.getValue(amount))).toHexKey() }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The mint. [invoiceAmount] is what its quote is for and [lightningFee] what its LN backend
     * kept, so the refund is `inputs - invoiceAmount - lightningFee`. Where the spec leaves room
     * or a mint can stray: [ascending] imprints the refund smallest denomination first,
     * [emitDleq] attaches NUT-12 proofs, [reverseSignatures] answers out of the output order the
     * spec requires, [forgeDleq] attaches the proof of another output, [surplus] returns more
     * signatures than outputs, and [signatureKeysetId] signs under another keyset.
     */
    private inner class RefundingMint(
        private val invoiceAmount: Long,
        private val lightningFee: Long = 0L,
        private val ascending: Boolean = false,
        private val emitDleq: Boolean = false,
        private val reverseSignatures: Boolean = false,
        private val forgeDleq: Boolean = false,
        private val surplus: Int = 0,
        private val signatureKeysetId: String = keysetId,
    ) : MintHttpTransport {
        /** The `amount` of every output of each melt request, in order; null when none were sent. */
        val meltOutputs = mutableListOf<List<Long>?>()
        val gets = mutableListOf<String>()

        private val signingKeys get() = if (signatureKeysetId == otherKeysetId) otherPrivKeys else privKeys

        override suspend fun get(url: String): MintHttpResponse {
            val path = url.substringAfter("mint.example.com")
            gets += path
            return when {
                path == "/v1/keysets" -> MintHttpResponse(200, json.encodeToString(KeysetListResponseDto.serializer(), KeysetListResponseDto(listOf(KeysetSummaryDto(id = keysetId, unit = "sat")))))
                path == "/v1/keys" -> keysetResponse(keysetId, privKeys)
                path == "/v1/keys/$otherKeysetId" -> keysetResponse(otherKeysetId, otherPrivKeys)
                else -> MintHttpResponse(404, "{}")
            }
        }

        private fun keysetResponse(
            id: String,
            priv: Map<Long, ByteArray>,
        ): MintHttpResponse = MintHttpResponse(200, json.encodeToString(KeysResponseDto.serializer(), KeysResponseDto(listOf(KeysetDto(id = id, unit = "sat", keys = publicKeys(priv))))))

        override suspend fun postJson(
            url: String,
            body: String,
        ): MintHttpResponse {
            if (!url.endsWith("/v1/melt/bolt11")) return MintHttpResponse(404, "{}")
            val request = json.decodeFromString(MeltBolt11RequestDto.serializer(), body)
            val outputs = request.outputs.orEmpty()
            meltOutputs += request.outputs?.map { it.amount }
            // NUT-08: "Alice MUST set the amount of each blank output to 0."
            if (outputs.any { it.amount != 0L }) return MintHttpResponse(400, """{"code":0,"detail":"melt outputs must be blank"}""")

            val refund = request.inputs.sumOf { it.amount } - invoiceAmount - lightningFee
            val denominations = if (refund > 0L) CashuMintOperations.splitAmounts(refund).let { if (ascending) it else it.asReversed() } else emptyList()
            var signatures =
                denominations.mapIndexedNotNull { index, amount ->
                    // What doesn't fit into the outputs it was given, the mint keeps.
                    val output = outputs.getOrNull(index) ?: return@mapIndexedNotNull null
                    val bTick = output.bTick.hexToByteArray()
                    val cTick = Bdhke.sign(bTick, signingKeys.getValue(amount)).toHexKey()
                    if (!emitDleq) return@mapIndexedNotNull BlindSignatureDto(amount = amount, id = signatureKeysetId, cTick = cTick)
                    val dleqFor = if (forgeDleq) outputs[(index + 1) % outputs.size].bTick.hexToByteArray() else bTick
                    val (_, e, s) = Bdhke.signFull(dleqFor, signingKeys.getValue(amount))
                    BlindSignatureDto(amount = amount, id = signatureKeysetId, cTick = cTick, dleq = DleqProofDto(e = e.toHexKey(), s = s.toHexKey()))
                }
            signatures +=
                List(surplus) { index ->
                    val output = outputs[index % outputs.size]
                    BlindSignatureDto(amount = 1, id = signatureKeysetId, cTick = Bdhke.sign(output.bTick.hexToByteArray(), signingKeys.getValue(1L)).toHexKey())
                }
            if (reverseSignatures) signatures = signatures.asReversed()
            val response = MeltBolt11ResponseDto(quote = request.quote, state = "PAID", paid = true, payment_preimage = "00", change = signatures)
            return MintHttpResponse(200, json.encodeToString(MeltBolt11ResponseDto.serializer(), response))
        }
    }

    private fun ops(mint: RefundingMint) = CashuMintOperations(MintHttpClient("https://mint.example.com", transport = mint))

    /** A spendable input proof — the mint here doesn't check it, it only needs to sum. */
    private fun proof(amount: Long): CashuProof = CashuProof(id = keysetId, amount = amount, secret = Bdhke.randomScalar().toHexKey(), c = publicKeys(privKeys).getValue("1"))

    private fun quote(
        amount: Long,
        feeReserve: Long,
    ) = MeltQuoteBolt11ResponseDto(quote = "quote-id", amount = amount, feeReserve = feeReserve, state = "UNPAID")

    /** The proof unblinds with the key of its denomination AND the `r` of the output it was paired with. */
    private fun verifies(proof: CashuProof): Boolean =
        Bdhke.verify(
            proof.secret.encodeToByteArray(),
            proof.c.hexToByteArray(),
            (if (proof.id == otherKeysetId) otherPrivKeys else privKeys).getValue(proof.amount),
        )

    @Test
    fun `the melt sends one blank output per NUT-08 for the fee reserve`() {
        // 23 sats in for a 21-sat invoice and a 2-sat reserve: one output can carry any refund
        // of up to 2 back in full.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 1)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 2), listOf(proof(23))) }

        assertEquals(listOf(listOf(0L)), mint.meltOutputs)
        assertEquals(listOf(1L), result.changeProofs.map { it.amount })
    }

    @Test
    fun `blank outputs are sized for the most the mint can refund`() {
        // A 25-sat reserve is ceil(log2(25)) = 5 outputs, whether or not it is all refunded.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 25)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals(List(5) { 0L }, mint.meltOutputs.single())
        assertTrue(result.changeProofs.isEmpty())
    }

    @Test
    fun `a refund above the reserve still fits the outputs`() {
        // The inputs hold 23 sats beyond the invoice, more than the 2-sat reserve. All of it can
        // come back, so the outputs are sized for 23 (5), not for 2 (1): with one, the mint would
        // keep all but the largest denomination.
        val mint = RefundingMint(invoiceAmount = 21)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 2), listOf(proof(44))) }

        assertEquals(List(5) { 0L }, mint.meltOutputs.single())
        assertEquals(listOf(16L, 4L, 2L, 1L), result.changeProofs.map { it.amount })
        result.changeProofs.forEach { assertTrue(verifies(it)) }
    }

    @Test
    fun `a refund keeps the mint's denominations, each unblinded with its own output`() {
        // 17 refunded as [16, 1], the largest first, into two blank outputs.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals(listOf(16L, 1L), result.changeProofs.map { it.amount })
        result.changeProofs.forEach { proof ->
            assertTrue("change proof ${proof.secret} must verify against the mint key", verifies(proof))
            assertEquals(keysetId, proof.id)
        }
    }

    @Test
    fun `a refund imprinted smallest denomination first pairs by position`() {
        // The same 17 as [1, 16]. Read as amounts, the 16 would be sought in a later output.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8, ascending = true)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals(listOf(1L, 16L), result.changeProofs.map { it.amount })
        result.changeProofs.forEach { proof -> assertTrue(verifies(proof)) }
    }

    @Test
    fun `dleq proofs pair signatures to outputs even out of order`() {
        // The 17 is imprinted [16, 1] but the 1-sat signature comes first. The mint is out of
        // order, which NUT-08 forbids; the DLEQ proof names the blinded message each signature
        // covers, so the pairing survives it.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8, emitDleq = true, reverseSignatures = true)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals(listOf(1L, 16L), result.changeProofs.map { it.amount })
        result.changeProofs.forEach { proof -> assertTrue(verifies(proof)) }
        // The proof keeps the DLEQ tuple, as every unblinded signature does.
        assertTrue(result.changeProofs.all { it.dleq?.r != null })
    }

    @Test
    fun `a forged dleq proof drops its signature instead of failing the paid melt`() {
        // Each DLEQ proof belongs to another output, so no pairing can verify. Position still
        // holds, but an unverifiable signature is worth less than no signature.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8, emitDleq = true, forgeDleq = true)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals("00", result.preimage)
        assertTrue(result.changeProofs.isEmpty())
    }

    @Test
    fun `more signatures than outputs drops the surplus instead of failing the melt`() {
        // One blank output, and the mint answers two signatures for it. The second has no
        // output left to pair with: dropped with a log line, since the melt has already paid.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 1, surplus = 1)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 2), listOf(proof(23))) }

        assertEquals(listOf(listOf(0L)), mint.meltOutputs)
        assertEquals(listOf(1L), result.changeProofs.map { it.amount })
        result.changeProofs.forEach { proof -> assertTrue(verifies(proof)) }
    }

    @Test
    fun `a signature naming another keyset is unblinded with that keyset`() {
        // NUT-02 has the mint sign with the outputs' keyset. Should it name another one, NUT-08
        // still says to unblind with the keyset the signature names.
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8, signatureKeysetId = otherKeysetId)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        // One lookup for both signatures, and the proof is filed under the keyset that signed it.
        assertEquals(listOf("/v1/keys", "/v1/keysets", "/v1/keys/$otherKeysetId"), mint.gets)
        result.changeProofs.forEach { proof ->
            assertEquals(otherKeysetId, proof.id)
            assertTrue(verifies(proof))
        }
        assertEquals(17L, result.changeProofs.sumOf { it.amount })
    }

    @Test
    fun `a signature naming a keyset the mint doesn't expose is dropped, not thrown`() {
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 8, signatureKeysetId = "00deadbeefdeadbe")

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertEquals("00", result.preimage)
        assertTrue(result.changeProofs.isEmpty())
    }

    @Test
    fun `without change requested the melt sends no outputs`() {
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 2)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 2), listOf(proof(23)), requestChange = false) }

        assertEquals(listOf<List<Long>?>(null), mint.meltOutputs)
        assertTrue(result.changeProofs.isEmpty())
    }

    @Test
    fun `no refund is the plain no-change path`() {
        val mint = RefundingMint(invoiceAmount = 21, lightningFee = 25)

        val result = runBlocking { ops(mint).meltProofs(quote(21, feeReserve = 25), listOf(proof(46))) }

        assertTrue(result.changeProofs.isEmpty())
        assertEquals(List(5) { 0L }, mint.meltOutputs.single())
    }

    @Test
    fun `blank outputs follow the NUT-08 formula`() {
        // max(ceil(log2(fee_reserve)), 1), and none without a fee.
        assertEquals(0, CashuMintOperations.blankOutputsFor(0))
        assertEquals(1, CashuMintOperations.blankOutputsFor(1))
        assertEquals(1, CashuMintOperations.blankOutputsFor(2))
        assertEquals(2, CashuMintOperations.blankOutputsFor(3))
        assertEquals(2, CashuMintOperations.blankOutputsFor(4))
        assertEquals(3, CashuMintOperations.blankOutputsFor(7))
        assertEquals(3, CashuMintOperations.blankOutputsFor(8))
        assertEquals(4, CashuMintOperations.blankOutputsFor(9))
        // The spec's example: ceil(log2(1000)) = 10.
        assertEquals(10, CashuMintOperations.blankOutputsFor(1000))
        // Exact bit lengths, which a floating-point log2 would round past 2^53.
        assertEquals(10, CashuMintOperations.blankOutputsFor(1024))
        assertEquals(11, CashuMintOperations.blankOutputsFor(1025))
        assertEquals(54, CashuMintOperations.blankOutputsFor((1L shl 53) + 1))
    }

    @Test
    fun `blank outputs are enough for every refund up to the amount they were sized for`() {
        // The mint picks the split, so the count has to cover the worst case: a refund whose
        // binary form uses every denomination below it.
        for (amount in 1L..512L) {
            val outputs = CashuMintOperations.blankOutputsFor(amount)
            for (refund in 1L..amount) {
                assertTrue("$outputs outputs can't carry back $refund of $amount", CashuMintOperations.splitAmounts(refund).size <= outputs)
            }
        }
    }
}
