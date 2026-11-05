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
package com.vitorpamplona.quartz.nip01Core.relay.commands.toClient

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The first copy of an id wins, unverified, as before; a copy the app reports forged is
 * recovered from, and the relay that sent it is verified on arrival from then on.
 */
class CachingEventDecoderForgeryTest {
    private val signer = NostrSignerSync()
    private val rogue = NormalizedRelayUrl("wss://rogue.example/")
    private val honest = NormalizedRelayUrl("wss://honest.example/")
    private val other = NormalizedRelayUrl("wss://other.example/")

    private fun note(content: String): Event = signer.sign(1_700_000_000L, 1, arrayOf(arrayOf("t", "x")), content)

    private fun Event.withContent(content: String) = Event(id, pubKey, createdAt, kind, tags, content, sig)

    private fun Event.withSig(sig: String) = Event(id, pubKey, createdAt, kind, tags, content, sig)

    private fun randomSig() = Random.nextBytes(64).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun frame(
        event: Event,
        subId: String = "sub",
    ) = "[\"EVENT\",\"$subId\",${event.toJson()}]"

    private fun CachingEventDecoder.read(
        event: Event,
        relay: NormalizedRelayUrl,
    ): Event? = (decodeFrom(frame(event), relay) as EventMessage?)?.event

    private class Recorder {
        val forgeries = mutableListOf<CachingEventDecoder.Forgery>()
    }

    private fun decoder(recorder: Recorder = Recorder()) = CachingEventDecoder().also { d -> d.onForgery = { recorder.forgeries += it } }

    @Test
    fun withoutReportsNothingIsVerifiedAndTheFirstCopyWins() {
        val d = decoder()
        val genuine = note("genuine")
        val forged = genuine.withContent("forged")

        assertSame(d.read(forged, rogue), d.read(genuine, honest), "optimistic: the duplicate gets the first copy")
        repeat(10) { d.read(note("n$it"), honest) }
        assertEquals(0, d.verifiedCount)
        assertEquals(0, d.skippedCount)
    }

    @Test
    fun aForgedContentCopySeenFirstGivesWayToTheGenuineOnceReported() {
        val recorder = Recorder()
        val d = decoder(recorder)
        val genuine = note("genuine")
        val forged = genuine.withContent("forged")

        d.read(forged, rogue)
        val handedOut = d.read(genuine, honest)!!
        assertFalse(handedOut.verify(), "the app's lazy verify fails")
        assertEquals(rogue, d.reportForged(handedOut), "attributed to the relay whose copy was handed out")

        val again = d.read(genuine, honest)!!
        assertEquals("genuine", again.content)
        assertTrue(again.verify())

        val verified = d.verifiedCount
        assertSame(again, d.read(genuine, other), "the genuine copy is cached again")
        assertSame(again, d.read(forged, other), "a forged copy now gets the genuine one")
        assertEquals(verified, d.verifiedCount)

        assertEquals(1, d.offenses(rogue))
        assertEquals(0, d.offenses(honest))
        assertEquals(setOf(rogue), d.offendingRelays())
        assertEquals(listOf(1), recorder.forgeries.map { it.offenses })
        assertTrue(recorder.forgeries.single().badId)
    }

    @Test
    fun aKnownBadSignatureIsDroppedWithoutParsing() {
        val recorder = Recorder()
        val d = decoder(recorder)
        val genuine = note("genuine")
        val forged = genuine.withSig(note("other").sig)

        d.read(forged, rogue)
        assertEquals(rogue, d.reportForged(d.read(forged, honest)!!))
        assertFalse(recorder.forgeries.single().badId, "the id matched: only the signature was bad")

        val parsed = d.parsedCount
        assertNull(d.decodeFrom(frame(forged), honest), "the same bad signature again")
        assertEquals(parsed, d.parsedCount, "dropped before parsing")

        assertTrue(d.read(genuine, honest)!!.verify())
    }

    @Test
    fun theNextForgeryFromAnOffendingRelayIsCaughtOnArrival() {
        val recorder = Recorder()
        val d = decoder(recorder)
        val first = note("first")
        d.read(first.withContent("forged"), rogue)
        d.reportForged(d.read(first, honest)!!)

        val second = note("second")
        assertNull(d.decodeFrom(frame(second.withContent("forged too")), rogue), "caught before it can win the dedup")
        assertEquals(listOf(1, 2), recorder.forgeries.map { it.offenses })
        val fromHonest = d.read(second, honest)!!
        assertEquals("second", fromHonest.content)

        // The offender's valid events still come through, verified; clean relays' are not verified.
        val valid = note("valid from rogue")
        assertEquals("valid from rogue", d.read(valid, rogue)!!.content)
        val verified = d.verifiedCount
        d.read(note("clean"), honest)
        assertEquals(verified, d.verifiedCount)
    }

    @Test
    fun anIdTakesALimitedNumberOfBadCopiesThenWaitsOutItsScrutiny() {
        val d = decoder()
        var now = 1_000L
        d.nowInSeconds = { now }
        val genuine = note("genuine")
        d.read(genuine.withSig(randomSig()), rogue)
        d.reportForged(d.read(genuine, honest)!!)

        repeat(CachingEventDecoder.MAX_FORGED_COPIES) { assertNull(d.decodeFrom(frame(genuine.withSig(randomSig())), other)) }
        assertNull(d.decodeFrom(frame(genuine), honest), "past the cap, even the genuine copy waits")

        now += CachingEventDecoder.SCRUTINY_SECONDS + 1
        assertEquals("genuine", d.read(genuine, honest)!!.content)
    }

    @Test
    fun aValidEventReportedChangesNothing() {
        val d = decoder()
        val e = note("fine")
        val first = d.read(e, honest)!!
        assertNull(d.reportForged(first))
        assertSame(first, d.read(e, other))
        assertTrue(d.offendingRelays().isEmpty())
    }

    @Test
    fun aForgivenRelayIsTrustedAgain() {
        val d = decoder()
        val e = note("x")
        d.read(e.withContent("forged"), rogue)
        d.reportForged(d.read(e, honest)!!)
        d.forgive(rogue)

        assertEquals(0, d.offenses(rogue))
        val verified = d.verifiedCount
        d.read(note("after"), rogue)
        assertEquals(verified, d.verifiedCount)
    }

    @Test
    fun withoutARelayADroppedFrameFails() {
        val d = decoder()
        val e = note("x")
        val forged = e.withSig(randomSig())
        d.decode(frame(forged))
        d.reportForged((d.decode(frame(forged)) as EventMessage).event)
        val outcome = runCatching { d.decode(frame(forged)) }.exceptionOrNull()
        assertTrue(outcome is IllegalArgumentException)
    }
}
