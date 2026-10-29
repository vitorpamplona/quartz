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
package com.vitorpamplona.quartz.nip01Core.signers

import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChatEditEvent
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordDisappearing
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordTimerNoticeEvent
import com.vitorpamplona.quartz.cyberspace.CyberspaceBagContents
import com.vitorpamplona.quartz.cyberspace.CyberspaceBagEvent
import com.vitorpamplona.quartz.cyberspace.CyberspaceCoordinate
import com.vitorpamplona.quartz.cyberspace.CyberspaceHint
import com.vitorpamplona.quartz.cyberspace.CyberspacePoint
import com.vitorpamplona.quartz.cyberspace.RegionKey
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoAvatarEvent
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoShardEvent
import com.vitorpamplona.quartz.experimental.agora.FundraiserEvent
import com.vitorpamplona.quartz.experimental.ballots.BallotAnswer
import com.vitorpamplona.quartz.experimental.ballots.BallotEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdDetectionEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdexEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdexSpecies
import com.vitorpamplona.quartz.experimental.clink.offers.OfferEvent
import com.vitorpamplona.quartz.experimental.clink.offers.OfferReceipt
import com.vitorpamplona.quartz.experimental.clink.offers.OfferRequest
import com.vitorpamplona.quartz.experimental.clink.offers.OfferResponse
import com.vitorpamplona.quartz.experimental.clink.tags.ClinkVersionTag
import com.vitorpamplona.quartz.experimental.fitness.workout.ExerciseTemplateEvent
import com.vitorpamplona.quartz.experimental.predictionMarkets.PredictionMarketEvent
import com.vitorpamplona.quartz.experimental.predictionMarkets.PredictionMarketOutcome
import com.vitorpamplona.quartz.experimental.predictionMarkets.PredictionMarketStatus
import com.vitorpamplona.quartz.experimental.ps1saves.Ps1SaveEvent
import com.vitorpamplona.quartz.feedDefinition.FeedDefinitionEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.geohash.geohashes
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nip57Zaps.preimage
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Event-class cleanup, batch 4: builders for kinds Quartz used to only read (other apps'
 * kinds) or only assembled inline. Each one is signed, parsed back through the event factory
 * and read with the class's own accessors.
 */
class AppKindBuildersTest {
    private val alice = NostrSignerInternal(KeyPair())
    private val bob = NostrSignerInternal(KeyPair())
    private val channelId = "c1".repeat(32)
    private val targetId = "e1".repeat(32)

    private suspend inline fun <reified T : Event> signAndParse(template: EventTemplate<T>): T {
        val parsed = Event.fromJson(alice.sign(template).toJson())
        assertTrue(parsed.verify())
        return assertIs<T>(parsed)
    }

    private fun Event.tagList() = tags.map { it.toList() }

    @Test
    fun concordEditMatchesChannelChatEdit() {
        val built = RumorAssembler.assembleRumor(alice.pubKey, ConcordChatEditEvent.build(channelId, 7, targetId, "fixed", 1_700_000_000, ms = 42))
        val inline = ChannelChat.edit(alice.pubKey, channelId, 7, targetId, "fixed", 1_700_000_000, ms = 42)
        assertEquals(inline.id, built.id)
        assertIs<ConcordChatEditEvent>(built)
        assertEquals(targetId, built.editedMessageId())
        assertEquals(1_700_000_000_042, built.orderingMs())
    }

    @Test
    fun concordTimerNotice() =
        runTest {
            val notice = signAndParse<ConcordTimerNoticeEvent>(ConcordTimerNoticeEvent.build(channelId, 7, 3600))
            assertEquals(3600L, notice.timerSecs())
            assertEquals(listOf(listOf("channel", channelId), listOf("epoch", "7"), listOf("timer", "3600")), notice.tagList())

            val off = ConcordDisappearing.timerNotice(alice.pubKey, channelId, 7, -5)
            assertEquals(0L, assertIs<ConcordTimerNoticeEvent>(off).timerSecs())
        }

    @Test
    fun snoAvatarAndShard() =
        runTest {
            val avatar = signAndParse<SnoAvatarEvent>(SnoAvatarEvent.build(name = "Ghost"))
            assertTrue(avatar.isDefaultAvatar())
            assertEquals("Ghost", avatar.nameTag())
            assertTrue(avatar.isPaid())

            val shard = signAndParse<SnoShardEvent>(SnoShardEvent.build("{}", coordinate = LONDON))
            assertEquals(LONDON, shard.coordinate())
            assertNotNull(shard.plane())
        }

    @Test
    fun cyberspaceBagOpensWithItsRegionKey() =
        runTest {
            val point = assertNotNull(CyberspaceCoordinate.decode(LONDON))
            val region = RegionKey.at(point, 5)
            val hint = CyberspaceHint(CyberspacePoint(point.x.alignedBase(11), point.y.alignedBase(11), point.z.alignedBase(11), point.plane), 11, 11, 11)
            val item = alice.sign<TextNoteEvent>(TextNoteEvent.build("hidden here"))

            val bag = signAndParse<CyberspaceBagEvent>(CyberspaceBagEvent.build(region, listOf(item), hint))
            assertEquals(region.lookupId, bag.lookupId())
            assertEquals(5, bag.height())
            assertTrue(bag.isKnownVersion())
            assertEquals(hint, bag.hint())
            assertEquals(hint.sectorTags().map { it.toList() }, bag.tags.filter { it[0] in setOf("X", "Y", "Z", "S") }.map { it.toList() })

            val contents = assertIs<CyberspaceBagContents.Items>(bag.open(region.decryptionKey))
            assertEquals(listOf(item.id), contents.items.map { it.event.id })
            assertTrue(contents.items.single().verified)
            assertNull(bag.open(ByteArray(32)))

            val opaque = signAndParse<CyberspaceBagEvent>(CyberspaceBagEvent.build(region, "a note".encodeToByteArray()))
            assertContentEquals("a note".encodeToByteArray(), assertIs<CyberspaceBagContents.Opaque>(opaque.open(region.decryptionKey)).bytes)

            // A hint below the bag's height reads as absent (§7.7), and an empty item list would open as opaque bytes.
            val tooLow = CyberspaceHint(CyberspacePoint(point.x.alignedBase(4), point.y.alignedBase(4), point.z.alignedBase(4), point.plane), 4, 4, 4)
            assertFailsWith<IllegalArgumentException> { CyberspaceBagEvent.build(region, "x".encodeToByteArray(), tooLow) }
            assertFailsWith<IllegalArgumentException> { CyberspaceBagEvent.build(region, emptyList<Event>()) }
        }

    @Test
    fun birdstarDetectionAndBirdex() =
        runTest {
            val detection = signAndParse<BirdDetectionEvent>(BirdDetectionEvent.build("Porphyrio martinica", "Purple Gallinule", WIKIDATA_1, "9vk"))
            assertEquals("Purple Gallinule", detection.commonName())
            assertEquals("Porphyrio martinica", detection.speciesName())
            assertEquals(WIKIDATA_1, detection.speciesReference())
            assertTrue("9vk" in detection.tags.geohashes())

            val species = listOf(BirdexSpecies("Icterus galbula", WIKIDATA_1), BirdexSpecies("Mimus polyglottos", null), BirdexSpecies("Baeolophus bicolor", WIKIDATA_2))
            val birdex = signAndParse<BirdexEvent>(BirdexEvent.build(species) { add(arrayOf("client", "test")) })
            assertEquals(species.map { it.name to it.reference }, birdex.species().map { it.name to it.reference })
            assertEquals("Birdex: 3 species", birdex.summary())
            // Birdstar's i-before-n, except after Mimus (no reference), where an `i` would bind back to it.
            assertEquals(listOf("alt", "i", "n", "n", "n", "i", "client"), birdex.tags.map { it[0] })

            val allReferenced = listOf(BirdexSpecies("Icterus galbula", WIKIDATA_1), BirdexSpecies("Baeolophus bicolor", WIKIDATA_2))
            val birdstarShape = signAndParse<BirdexEvent>(BirdexEvent.build(allReferenced))
            assertEquals(listOf("alt", "i", "n", "i", "n"), birdstarShape.tags.map { it[0] })
            assertEquals(allReferenced.map { it.name to it.reference }, birdstarShape.species().map { it.name to it.reference })

            // A reference that is not a web URL reads as none, so the next `i` must not bind back to its species.
            val bareId = signAndParse<BirdexEvent>(BirdexEvent.build(listOf(BirdexSpecies("Icterus galbula", "Q805774"), BirdexSpecies("Baeolophus bicolor", WIKIDATA_2))))
            assertEquals(listOf("Icterus galbula" to null, "Baeolophus bicolor" to WIKIDATA_2), bareId.species().map { it.name to it.reference })
        }

    @Test
    fun ps1Save() =
        runTest {
            val save = signAndParse<Ps1SaveEvent>(Ps1SaveEvent.build("card", 1, "ffff", state = "first", filename = "BASCUS-00001", region = "America", title = "SAVE"))
            assertEquals("card-1", save.dTag())
            assertEquals(1, save.blockNumber())
            assertEquals("BASCUS-00001", save.filename())
            assertEquals("America", save.region())
            assertEquals("SAVE", save.saveTitle())
            assertEquals("PS1 save 'SAVE' (BASCUS-00001)", save.summary())
            assertTrue(save.isBlankBlock())
        }

    @Test
    fun fundraiser() =
        runTest {
            val wallets = listOf("bc1qexample", "sp1qexample")
            val campaign = signAndParse<FundraiserEvent>(FundraiserEvent.build("Help", "Our story", goal = 10_000, deadline = 1_900_000_000, wallets = wallets, banner = "https://img.example.com/b.jpg", topics = listOf("community"), dTag = "help"))
            assertEquals("help", campaign.dTag())
            assertEquals("Help", campaign.title())
            assertEquals(10_000L, campaign.goal())
            assertEquals(1_900_000_000L, campaign.deadline())
            assertEquals(wallets, campaign.wallets())
            assertEquals("https://img.example.com/b.jpg", campaign.coverImage())
            assertEquals(listOf("community"), campaign.topics())
        }

    @Test
    fun exerciseTemplate() =
        runTest {
            val exercise = signAndParse<ExerciseTemplateEvent>(ExerciseTemplateEvent.build("Squat", "Keep your back straight", listOf("weight", "reps"), listOf("kg", "count"), "barbell", "beginner"))
            assertEquals("Squat", exercise.title())
            assertEquals(listOf("weight", "reps"), exercise.format())
            assertEquals(listOf("kg", "count"), exercise.formatUnits())
            assertEquals("barbell", exercise.equipment())
            assertEquals("beginner", exercise.difficulty())
            assertFailsWith<IllegalArgumentException> { ExerciseTemplateEvent.build("Squat", "", listOf("weight", "reps"), listOf("kg")) }
            assertFailsWith<IllegalArgumentException> { ExerciseTemplateEvent.build("Squat", "", listOf("weight", ""), listOf("kg", "count")) }
        }

    @Test
    fun feedDefinition() =
        runTest {
            val feed = signAndParse<FeedDefinitionEvent>(FeedDefinitionEvent.build("Birds", """{"kinds":[1]}""", emoji = "🐦"))
            assertEquals("Birds", feed.title())
            assertEquals("🐦", feed.emoji())
            assertEquals("""{"kinds":[1]}""", feed.feedConfigJson())
        }

    @Test
    fun ballotParsesAsABallot() =
        runTest {
            val answers = listOf(BallotAnswer("proposal_approval", "Yes"), BallotAnswer("budget", "No"))
            val ballot = signAndParse<BallotEvent>(BallotEvent.build("spring-2026-council", answers, proofHash = "ab".repeat(32)))
            assertEquals("spring-2026-council", ballot.election())
            assertEquals(answers, ballot.answers())
            assertEquals("ab".repeat(32), ballot.proofHash())
            assertFailsWith<IllegalArgumentException> { BallotEvent.build(" ", answers) }
        }

    @Test
    fun predictionMarketParsesAsAMarket() =
        runTest {
            val outcomes = listOf(PredictionMarketOutcome("yes", "Yes"), PredictionMarketOutcome("no", "No"))
            val market = signAndParse<PredictionMarketEvent>(PredictionMarketEvent.build("m1", "Will it rain?", outcomes, 1_900_000_000, "In Boston, tomorrow", category = "weather"))
            assertEquals("m1", market.marketId())
            assertEquals("m1", market.dTag())
            assertEquals("Will it rain?", market.title())
            assertEquals("In Boston, tomorrow", market.description())
            assertEquals(outcomes, market.outcomeOptions())
            assertEquals(1_900_000_000L, market.endsAt())
            assertEquals("weather", market.category())
            assertTrue(market.isDemo())
            assertEquals(PredictionMarketStatus.OPEN, market.status(now = 1_800_000_000))
            assertNull(market.socialPost())
            assertFailsWith<IllegalArgumentException> { PredictionMarketEvent.build("", "Will it rain?", outcomes, 1_900_000_000) }
        }

    @Test
    fun zapReceiptCopiesTheRequest() =
        runTest {
            val note = alice.sign<TextNoteEvent>(TextNoteEvent.build("zap me"))
            val relays = setOf(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com/")!!)
            val request = bob.sign<ZapRequestEvent>(ZapRequestEvent.build(note, relays, "great post", ZapReceiptEvent.ZapType.PUBLIC, PTag(note.pubKey)))

            val receipt = signAndParse<ZapReceiptEvent>(ZapReceiptEvent.build(request, "lnbc1invoice", preimage = "00".repeat(32), createdAt = request.createdAt))
            assertEquals(listOf(note.id), receipt.zappedPost())
            assertEquals(listOf(note.pubKey), receipt.zappedAuthor())
            assertEquals(bob.pubKey, receipt.zapSender())
            assertEquals(request.id, receipt.zapRequest?.id)
            assertEquals("great post", receipt.zapRequest?.content)
            assertEquals("lnbc1invoice", receipt.lnInvoice())
            assertEquals("00".repeat(32), receipt.tags.preimage())
            assertEquals(listOf("p", "P", "e", "bolt11", "description", "preimage"), receipt.tags.map { it[0] })
        }

    @Test
    fun clinkOfferRoundTrip() =
        runTest {
            val request = signAndParse<OfferEvent>(OfferEvent.build(OfferRequest(offer = "noffer1x", amount_sats = 21), bob.pubKey, alice))
            assertEquals(listOf(listOf("p", bob.pubKey), listOf(ClinkVersionTag.TAG_NAME, ClinkVersionTag.CURRENT)), request.tagList())
            assertEquals("noffer1x", request.decryptRequest(bob).offer)
            assertEquals(request.tagList(), OfferEvent.createRequest(OfferRequest(offer = "noffer1x"), bob.pubKey, alice).tagList())

            val response = bob.sign(OfferEvent.build(OfferResponse(bolt11 = "lnbc1"), request, bob))
            assertEquals(request.id, response.requestId())
            assertEquals(alice.pubKey, response.recipientPubKey())
            assertEquals("lnbc1", response.decryptResponse(alice).bolt11)

            val receipt = bob.sign(OfferEvent.build(OfferReceipt(res = "ok"), request, bob))
            assertTrue(receipt.decryptReceipt(alice).isOk())
        }

    companion object {
        /** `CYBERSPACE_V2.md` §9.8's london vector. */
        const val LONDON = "c492492492492492492492edf5bee7267451c787d95ba4d7840c76d1e33c9940"
        const val WIKIDATA_1 = "https://www.wikidata.org/entity/Q805774"
        const val WIKIDATA_2 = "https://www.wikidata.org/entity/Q738534"
    }
}
