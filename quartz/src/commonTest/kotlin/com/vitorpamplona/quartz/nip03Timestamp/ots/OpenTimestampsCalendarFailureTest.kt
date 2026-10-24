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

import com.vitorpamplona.quartz.nip03Timestamp.ots.attestation.PendingAttestation
import com.vitorpamplona.quartz.nip03Timestamp.ots.exceptions.UrlException
import com.vitorpamplona.quartz.nip03Timestamp.ots.op.OpSHA256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A calendar that is down must not sink a stamp the other calendars answered. */
class OpenTimestampsCalendarFailureTest {
    private class FakeCalendar(
        private val down: Set<String>,
    ) : RemoteCalendar {
        val asked = mutableListOf<String>()

        override suspend fun submit(
            url: String,
            digest: ByteArray,
        ): Timestamp {
            asked.add(url)
            if (url in down) throw UrlException("$url is down")
            return Timestamp(digest).also { it.attestations.add(PendingAttestation(url.encodeToByteArray())) }
        }

        override suspend fun getTimestamp(
            url: String,
            commitment: ByteArray,
        ): Timestamp = throw UrlException("not used")
    }

    private fun file() = DetachedTimestampFile.from(OpSHA256(), "hello".encodeToByteArray())

    @Test
    fun aDownCalendarDoesNotFailTheStamp() =
        runTest {
            val calendar = FakeCalendar(down = setOf("https://down"))
            val urls = mutableListOf("https://up1", "https://down", "https://up2")
            val stamped = OpenTimestamps(EmptyBitcoinExplorer, calendar).stamp(file(), urls)

            assertEquals(urls.toSet(), calendar.asked.toSet())
            assertTrue(stamped.getAttestations().any { it is PendingAttestation }, "the calendars that answered must attest")
        }

    @Test
    fun everyCalendarDownFailsTheStamp() =
        runTest {
            val ots = OpenTimestamps(EmptyBitcoinExplorer, FakeCalendar(down = setOf("https://a", "https://b")))
            assertFailsWith<UrlException> { ots.stamp(file(), mutableListOf("https://a", "https://b")) }
        }
}
