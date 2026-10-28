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
package com.vitorpamplona.quartz.nip03Timestamp.ots

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip03Timestamp.EmptyOtsResolverBuilder
import com.vitorpamplona.quartz.nip03Timestamp.OtsEvent
import com.vitorpamplona.quartz.nip03Timestamp.VerificationState
import com.vitorpamplona.quartz.nip03Timestamp.ots.attestation.BitcoinBlockHeaderAttestation
import com.vitorpamplona.quartz.nip03Timestamp.ots.attestation.PendingAttestation
import com.vitorpamplona.quartz.nip03Timestamp.ots.exceptions.DeserializationException
import com.vitorpamplona.quartz.nip03Timestamp.ots.op.Op
import com.vitorpamplona.quartz.nip03Timestamp.ots.op.OpAppend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OtsMalformedInputTest {
    private val resolver = EmptyOtsResolverBuilder.build()

    private val otsEvent2 = "{\"content\":\"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\",\"created_at\":1706324334,\"id\":\"2ad074ddb7724eb13b4244b49cf2321b1057f37fdf8ce102e6329b839cf763a9\",\"kind\":1040,\"pubkey\":\"82fbb08c8ef45c4d71c88368d0ae805bc62fb92f166ab04a0b7a0c83d8cbc29a\",\"sig\":\"ad7274bb32ba9e9cfdbd52f4887e8a2fda1047c75a7185b2ab7ff254ebac14ed48a2b60737494d655e24c9400eeeec7e29293a77bfcaafaecd94b350c9a2c22b\",\"tags\":[[\"e\",\"a8634f5368e17789e8fb3836cad0e4b9fe4f2288afafc28723c04bebc68a18d6\"],[\"p\",\"c31e22c3715c1bde5608b7e0d04904f22f5fc453ba1806d21c9f2382e1e58c6c\"],[\"alt\",\"NIP-03 time stamp\"]]}"

    private fun withContent(content: String): OtsEvent {
        val ots = Event.fromJson(otsEvent2) as OtsEvent
        return OtsEvent(ots.id, ots.pubKey, ots.createdAt, ots.tags, content, ots.sig)
    }

    @Test
    fun contentThatIsNotBase64IsAnError() =
        runTest {
            assertIs<VerificationState.Error>(withContent("not base64 !!!").verifyState(resolver))
        }

    @Test
    fun contentThatIsNotAnOtsFileIsAnError() =
        runTest {
            assertIs<VerificationState.Error>(withContent("AAECAwQF").verifyState(resolver))
        }

    @Test
    fun unknownFileHashOpIsAnError() =
        runTest {
            // Valid magic and version, then 0x99: not an op tag.
            val bytes = DetachedTimestampFile.HEADER_MAGIC + byteArrayOf(1, 0x99.toByte())
            assertIs<VerificationState.Error>(resolver.verify(bytes, ByteArray(32)))
            assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(bytes) }
        }

    @Test
    fun binaryOpInFileHashPositionIsADeserializationError() {
        // 0xf0 is OpAppend: valid, but not a hash op.
        val bytes = DetachedTimestampFile.HEADER_MAGIC + byteArrayOf(1, 0xf0.toByte(), 1, 0)
        assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(bytes) }
    }

    /** A file whose timestamp is [opCount] chained SHA-256 ops (one byte each) ending in [tail]. */
    private fun chainedSha256File(
        opCount: Int,
        tail: ByteArray = byteArrayOf(0x00, 0x05, 0x88.toByte(), 0x96.toByte(), 0x0d, 0x73, 0xd7.toByte(), 0x19, 0x01, 0x01, 0x00),
    ): ByteArray = DetachedTimestampFile.HEADER_MAGIC + byteArrayOf(1, 0x08) + ByteArray(32) + ByteArray(opCount) { 0x08 } + tail

    @Test
    fun deeplyNestedProofIsAnErrorNotAStackOverflow() =
        runTest {
            val bytes = chainedSha256File(200_000)
            assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(bytes) }
            assertIs<VerificationState.Error>(resolver.verify(bytes, ByteArray(32)))
            assertIs<VerificationState.Error>(withContent(OtsEvent.encodeOtsState(bytes)).verifyState(resolver))
        }

    @Test
    fun realProofStillParses() {
        val ots = Event.fromJson(otsEvent2) as OtsEvent
        val ctx = StreamDeserializationContext(ots.otsByteArray())
        val file = DetachedTimestampFile.deserialize(ctx)
        assertTrue(file.timestamp.getAttestations().any { it is BitcoinBlockHeaderAttestation })
        assertEquals(ctx.output.size, ctx.counter)
    }

    @Test
    fun proofAtTheDepthLimitStillParses() {
        val file = DetachedTimestampFile.deserialize(chainedSha256File(Timestamp.MAX_DEPTH - 1))
        assertEquals(1, file.timestamp.getAttestations().size)
        assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(chainedSha256File(Timestamp.MAX_DEPTH)) }
    }

    /** A 4000-byte append, then [siblings] 2-byte appends: each sibling keeps its own ~4 KB message. */
    private fun wideProof(siblings: Int): ByteArray {
        val children = mutableListOf<Byte>()
        repeat(siblings) { child ->
            if (child < siblings - 1) children += 0xff.toByte()
            children += listOf(0xf0.toByte(), 2, (child shr 8).toByte(), child.toByte())
            children += 0x00 // attestation: an unknown 8-byte tag and an empty payload
            children += ByteArray(8) { 1 }.toList()
            children += 0x00
        }
        return DetachedTimestampFile.HEADER_MAGIC + byteArrayOf(1, 0x08) + ByteArray(32) +
            byteArrayOf(0xf0.toByte(), 0xa0.toByte(), 0x1f) + ByteArray(4000) + children.toByteArray()
    }

    @Test
    fun wideProofOfLargeMessagesIsAnError() {
        val e = assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(wideProof(300)) }
        assertTrue(e.message!!.contains("bytes of messages"), e.message)

        // ~800 KB of messages is within the budget.
        assertEquals(
            200,
            DetachedTimestampFile
                .deserialize(wideProof(200))
                .timestamp.ops.values
                .single()
                .ops.size,
        )
    }

    @Test
    fun overlongVaruintIsADeserializationError() {
        // Six bytes would shift past 32 bits: without the check this decodes as 8.
        val sixBytes = ByteArray(5) { 0x80.toByte() } + byteArrayOf(0x01)
        assertFailsWith<DeserializationException> { StreamDeserializationContext(sixBytes).readVaruint() }

        val maxInt = ByteArray(4) { 0xff.toByte() } + byteArrayOf(0x07)
        assertEquals(Int.MAX_VALUE, StreamDeserializationContext(maxInt).readVaruint())
    }

    @Test
    fun truncatedProofIsADeserializationError() {
        assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(chainedSha256File(3, tail = byteArrayOf())) }
        assertFailsWith<DeserializationException> { DetachedTimestampFile.deserialize(DetachedTimestampFile.HEADER_MAGIC) }
    }

    @Test
    fun unknownOpTagThrowsDeserializationException() {
        assertFailsWith<DeserializationException> {
            Op.deserialize(StreamDeserializationContext(byteArrayOf(0x99.toByte())))
        }
    }

    @Test
    fun resolverWithoutAnExplorerReportsAnErrorInsteadOfCrashing() =
        runTest {
            val ots = Event.fromJson(otsEvent2) as OtsEvent
            val state = ots.verifyState(resolver)
            assertTrue(state is VerificationState.Error || state is VerificationState.NetworkError, "was $state")
        }

    @Test
    fun opsEnforceTheMessageAndResultLimits() {
        assertFailsWith<IllegalArgumentException> { OpAppend(ByteArray(10)).call(ByteArray(Op.MAX_MSG_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { OpAppend(ByteArray(100)).call(ByteArray(Op.MAX_MSG_LENGTH)) }
        assertEquals(12, OpAppend(ByteArray(2)).call(ByteArray(10)).size)
    }

    @Test
    fun attestationsOfDifferentTypesCompareByTag() {
        val pending = PendingAttestation("https://alice.btc.calendar.opentimestamps.org".encodeToByteArray())
        val bitcoin = BitcoinBlockHeaderAttestation(800000)
        val forward = pending.compareTo(bitcoin)
        val backward = bitcoin.compareTo(pending)
        assertTrue(forward != 0)
        assertEquals(-forward.coerceIn(-1, 1), backward.coerceIn(-1, 1))
    }

    @Test
    fun attestationsOfTheSameTypeCompareByValue() {
        assertTrue(BitcoinBlockHeaderAttestation(1).compareTo(BitcoinBlockHeaderAttestation(2)) < 0)
        assertEquals(0, BitcoinBlockHeaderAttestation(2).compareTo(BitcoinBlockHeaderAttestation(2)))
    }
}
