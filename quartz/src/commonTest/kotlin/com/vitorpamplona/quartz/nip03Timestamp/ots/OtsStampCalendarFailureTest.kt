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

import com.vitorpamplona.quartz.nip03Timestamp.OtsResolver
import com.vitorpamplona.quartz.nip03Timestamp.ots.attestation.PendingAttestation
import com.vitorpamplona.quartz.nip03Timestamp.ots.exceptions.UrlException
import com.vitorpamplona.quartz.nip03Timestamp.ots.op.OpSHA256
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Stamping asks several calendars at once. A calendar that is down (or a Tor proxy that is not
 * running) must not throw away the answers of the ones that replied, and when none replies the
 * failure is the [UrlException] [RemoteCalendar] documents.
 */
class OtsStampCalendarFailureTest {
    private val up = "https://up.example"
    private val down = "https://down.example"

    private class FakeCalendar(
        val reachable: Set<String>,
        val hanging: Set<String> = emptySet(),
    ) : RemoteCalendar {
        override suspend fun submit(
            url: String,
            digest: ByteArray,
        ): Timestamp {
            if (url in hanging) delay(10 * 60_000L)
            if (url !in reachable) throw UrlException("Failed to open $url")
            return Timestamp(digest).apply { attestations.add(PendingAttestation(url.encodeToByteArray())) }
        }

        override suspend fun getTimestamp(
            url: String,
            commitment: ByteArray,
        ): Timestamp = throw UrlException("not used")
    }

    // The calendars attest the merkle tip, under the nonce ops, so walk the whole tree.
    private fun Timestamp.pending(): List<PendingAttestation> = attestations.filterIsInstance<PendingAttestation>() + ops.values.flatMap { it.pending() }

    private suspend fun stamp(calendar: RemoteCalendar): DetachedTimestampFile {
        val file = DetachedTimestampFile.from(Hash(ByteArray(32) { it.toByte() }, OpSHA256.TAG))
        OpenTimestamps(EmptyBitcoinExplorer, calendar).stamp(file, mutableListOf(down, up))
        return file
    }

    @Test
    fun keepsTheCalendarsThatAnswered() =
        runTest {
            val file = stamp(FakeCalendar(reachable = setOf(up)))
            val pending = file.timestamp.pending()
            assertEquals(listOf(up), pending.map { it.uri.decodeToString() })
        }

    @Test
    fun failsWithUrlExceptionWhenNoCalendarAnswers() =
        runTest {
            assertFailsWith<UrlException> { stamp(FakeCalendar(reachable = emptySet())) }
        }

    @Test
    fun aHangingCalendarIsDroppedAtTheTimeout() =
        runTest {
            val file = stamp(FakeCalendar(reachable = setOf(up, down), hanging = setOf(down)))
            assertEquals(listOf(up), file.timestamp.pending().map { it.uri.decodeToString() })
        }

    @Test
    fun allCalendarsHangingFailsWithUrlException() =
        runTest {
            val e = assertFailsWith<UrlException> { stamp(FakeCalendar(reachable = setOf(up, down), hanging = setOf(up, down))) }
            assertTrue(e.message!!.contains("in time"), e.message)
        }

    @Test
    fun resolverStampSurvivesOneCalendarDown() =
        runTest {
            // The path the app uses: OtsEvent.stamp -> OtsResolver.stamp, with the default calendars.
            val defaults =
                setOf(
                    "https://alice.btc.calendar.opentimestamps.org",
                    "https://finney.calendar.eternitywall.com",
                )
            val bytes = OtsResolver(EmptyBitcoinExplorer, FakeCalendar(defaults)).stamp(ByteArray(32))
            val pending = DetachedTimestampFile.deserialize(bytes).timestamp.pending()
            assertEquals(2, pending.size)
        }
}
