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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The first copy of an id wins, unverified, as before; a copy the app reports forged is
 * recovered from; and a relay is charged only once a valid copy proves its copy was altered,
 * then verified on arrival from then on.
 */
class CachingEventDecoderForgeryTest {
    private val signer = NostrSignerSync()
    private val rogue = NormalizedRelayUrl("wss://rogue.example/")
    private val rogue2 = NormalizedRelayUrl("wss://rogue2.example/")
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

    /** The same frame with whitespace, which the decoder's quick scan does not read. */
    private fun looseFrame(event: Event) = "[ \"EVENT\", \"sub\", ${event.toJson()} ]"

    private fun CachingEventDecoder.read(
        event: Event,
        relay: NormalizedRelayUrl,
    ): Event? = (decodeFrom(frame(event), relay) as EventMessage?)?.event

    private val forgeries = mutableListOf<CachingEventDecoder.Forgery>()

    private fun decoder() = CachingEventDecoder().also { d -> d.onForgery = { forgeries += it } }

    /** [genuine]'s forged copy handed out by [from], then reported: the usual start. */
    private fun CachingEventDecoder.poisonAndReport(
        genuine: Event,
        forged: Event = genuine.withContent("forged"),
        from: NormalizedRelayUrl = rogue,
    ): NormalizedRelayUrl? {
        read(forged, from)
        val handedOut = read(genuine, honest)!!
        assertFalse(handedOut.verify(), "the duplicate got the forged copy: the app's lazy verify fails")
        return reportForged(handedOut)
    }

    @Test
    fun withoutReportsNothingIsVerifiedAndTheFirstCopyWins() {
        val d = decoder()
        val genuine = note("genuine")
        assertSame(d.read(genuine.withContent("forged"), rogue), d.read(genuine, honest), "optimistic: the duplicate gets the first copy")
        repeat(10) { d.read(note("n$it"), honest) }
        assertEquals(0, d.verifiedCount)
        assertEquals(0, d.skippedCount)
    }

    @Test
    fun aForgedContentCopyGivesWayToTheGenuineAndItsRelayIsChargedThen() {
        val d = decoder()
        val genuine = note("genuine")
        val forged = genuine.withContent("forged")
        assertEquals(rogue, d.poisonAndReport(genuine, forged), "attributed to the relay whose copy was handed out")
        assertEquals(0, d.offenses(rogue), "not charged on a report alone")
        assertTrue(forgeries.isEmpty())

        val again = d.read(genuine, honest)!!
        assertEquals("genuine", again.content)
        assertTrue(again.verify())
        assertEquals(1, d.offenses(rogue), "charged once the valid copy proved its copy altered")
        assertEquals(0, d.offenses(honest))
        assertEquals(setOf(rogue), d.offendingRelays())
        assertEquals(listOf(rogue to 1), forgeries.map { it.relay to it.offenses })
        assertTrue(forgeries.single().badId)

        val verified = d.verifiedCount
        assertSame(again, d.read(genuine, other), "the genuine copy is cached again")
        assertSame(again, d.read(forged, other), "a forged copy now gets the genuine one")
        assertEquals(verified, d.verifiedCount)
    }

    @Test
    fun aKnownBadSignatureIsDroppedWithoutParsing() {
        val d = decoder()
        val genuine = note("genuine")
        val forged = genuine.withSig(note("other").sig)
        assertEquals(rogue, d.poisonAndReport(genuine, forged))

        val parsed = d.parsedCount
        assertNull(d.decodeFrom(frame(forged), honest), "the same bad signature again")
        assertEquals(parsed, d.parsedCount, "dropped before parsing")

        assertTrue(d.read(genuine, honest)!!.verify())
        assertFalse(forgeries.single().badId, "the id matched: only the signature was bad")
    }

    @Test
    fun anEventBrokenEverywhereChargesNoOne() {
        val d = decoder()
        var now = 1_000L
        d.nowInSeconds = { now }
        // A client published a bad signature; every relay serves the same broken event.
        val broken = note("broken").withSig(randomSig())
        d.read(broken, honest)
        assertEquals(honest, d.reportForged(d.read(broken, other)!!))
        assertNull(d.decodeFrom(frame(broken), other))
        assertNull(d.decodeFrom(frame(broken), rogue))

        now += CachingEventDecoder.SCRUTINY_SECONDS + 1
        d.ageOutCache()
        assertTrue(d.offendingRelays().isEmpty())
        assertTrue(forgeries.isEmpty())
    }

    @Test
    fun aChargedRelaysNextForgeryIsCaughtOnArrival() {
        val d = decoder()
        val first = note("first")
        d.poisonAndReport(first)
        d.read(first, honest)
        assertEquals(1, d.offenses(rogue))

        val second = note("second")
        assertNull(d.decodeFrom(frame(second.withContent("forged too")), rogue), "caught before it can win the dedup")
        assertEquals(1, d.offenses(rogue), "charged only when the valid copy arrives")
        assertEquals("second", d.read(second, honest)!!.content)
        assertEquals(listOf(1, 2), forgeries.map { it.offenses })

        // The charged relay's valid events still come through, verified; clean relays' are not verified.
        assertEquals("valid from rogue", d.read(note("valid from rogue"), rogue)!!.content)
        val verified = d.verifiedCount
        d.read(note("clean"), honest)
        assertEquals(verified, d.verifiedCount)
    }

    @Test
    fun everyRelayThatSentABadCopyIsChargedOnce() {
        val d = decoder()
        val genuine = note("genuine")
        d.poisonAndReport(genuine)
        assertNull(d.decodeFrom(frame(genuine.withContent("another forgery")), rogue2))
        assertNull(d.decodeFrom(frame(genuine.withSig(randomSig())), rogue2))

        d.read(genuine, honest)
        d.read(genuine, other)
        assertEquals(1, d.offenses(rogue))
        assertEquals(1, d.offenses(rogue2), "one charge per relay per id")
        assertEquals(setOf(rogue, rogue2), forgeries.map { it.relay }.toSet())
    }

    @Test
    fun anIdTakesALimitedNumberOfBadCopiesThenWaitsOutItsScrutiny() {
        val d = decoder()
        var now = 1_000L
        d.nowInSeconds = { now }
        val genuine = note("genuine")
        d.poisonAndReport(genuine, genuine.withSig(randomSig()))

        repeat(CachingEventDecoder.MAX_FORGED_COPIES) { assertNull(d.decodeFrom(frame(genuine.withSig(randomSig())), other)) }
        assertNull(d.decodeFrom(frame(genuine), honest), "past the cap, even the genuine copy waits")

        now += CachingEventDecoder.SCRUTINY_SECONDS + 1
        assertEquals("genuine", d.read(genuine, honest)!!.content)
        assertTrue(d.offendingRelays().isEmpty(), "expired: no valid copy came while it was open")
    }

    @Test
    fun aReportFindsTheCopyInTheOlderGeneration() {
        val d = decoder()
        val genuine = note("genuine")
        d.read(genuine.withContent("forged"), rogue)
        d.ageOutCache() // the forged copy moves to the older generation
        val handedOut = d.read(genuine, honest)!!
        assertEquals("forged", handedOut.content)
        assertEquals(rogue, d.reportForged(handedOut))

        assertEquals("genuine", d.read(genuine, honest)!!.content)
        assertEquals(1, d.offenses(rogue))
    }

    @Test
    fun afterTheScrutinyExpiresTheRevokedIdStillHidesTheOlderCopy() {
        val d = decoder()
        var now = 1_000L
        d.nowInSeconds = { now }
        val genuine = note("genuine")
        d.read(genuine.withContent("forged"), rogue)
        d.ageOutCache()
        d.reportForged(d.read(genuine, honest)!!)

        now += CachingEventDecoder.SCRUTINY_SECONDS + 1
        val verified = d.verifiedCount
        assertEquals("genuine", d.read(genuine, other)!!.content, "parsed from its own frame, not the forged copy")
        assertEquals(verified, d.verifiedCount, "plain dedup again: nothing verified")
    }

    @Test
    fun aReportAfterTheCopyAgedOutStillRecovers() {
        val d = decoder()
        val genuine = note("genuine")
        d.read(genuine.withContent("forged"), rogue)
        val handedOut = d.read(genuine, honest)!!
        d.ageOutCache()
        d.ageOutCache() // gone from both generations
        assertNull(d.reportForged(handedOut), "no relay left to attribute it to")

        assertEquals("genuine", d.read(genuine, honest)!!.content)
        assertTrue(d.offendingRelays().isEmpty())
    }

    @Test
    fun framesTheScanCannotReadAnswerToTheScrutinyToo() {
        val d = decoder()
        val genuine = note("genuine")
        d.poisonAndReport(genuine)

        val forgedAgain = d.decodeFrom(looseFrame(genuine.withContent("forged again")), rogue2)
        assertNull(forgedAgain)
        val fromLoose = assertNotNull(d.decodeFrom(looseFrame(genuine), honest) as EventMessage?).event
        assertEquals("genuine", fromLoose.content)
        assertEquals(setOf(rogue, rogue2), d.offendingRelays())
    }

    @Test
    fun pastTheScrutinyCapReportsFallBackToPlainDedupUntilEntriesExpire() {
        val d = decoder()
        var now = 1_000L
        d.nowInSeconds = { now }
        val events = List(CachingEventDecoder.MAX_SCRUTINIZED_IDS + 1) { note("e$it") }
        for (e in events) {
            d.read(e.withSig(randomSig()), rogue)
            d.reportForged(d.read(e, honest)!!)
        }
        val last = events.last()
        val verified = d.verifiedCount
        assertEquals("e${events.size - 1}", d.read(last, honest)!!.content, "the revoked id still hides the forged copy")
        assertEquals(verified, d.verifiedCount, "but the over-cap id is not scrutinized: no verification")

        now += CachingEventDecoder.SCRUTINY_SECONDS + 1
        val late = note("late")
        d.read(late.withSig(randomSig()), rogue)
        d.reportForged(d.read(late, honest)!!)
        val before = d.verifiedCount
        d.read(late, honest)
        assertEquals(before + 1, d.verifiedCount, "expired entries make room again")
    }

    @Test
    fun clearCacheDropsTheScrutinyButKeepsTheRelaysRecords() {
        val d = decoder()
        val first = note("first")
        d.poisonAndReport(first)
        d.read(first, honest)
        val second = note("second")
        d.poisonAndReport(second, from = rogue2)

        d.clearCache()
        assertEquals(1, d.offenses(rogue), "a charged relay stays charged")
        val forged = second.withContent("forged")
        assertSame(d.read(forged, other), d.read(second, honest), "the pending id is plain dedup again")
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
        d.poisonAndReport(e)
        d.read(e, honest)
        d.forgive(rogue)

        assertEquals(0, d.offenses(rogue))
        val verified = d.verifiedCount
        d.read(note("after"), rogue)
        assertEquals(verified, d.verifiedCount)
    }

    @Test
    fun withoutARelayADroppedFrameFails() {
        val d = decoder()
        val forged = note("x").withSig(randomSig())
        d.decode(frame(forged))
        d.reportForged((d.decode(frame(forged)) as EventMessage).event)
        val outcome = runCatching { d.decode(frame(forged)) }.exceptionOrNull()
        assertTrue(outcome is IllegalArgumentException)
    }
}
