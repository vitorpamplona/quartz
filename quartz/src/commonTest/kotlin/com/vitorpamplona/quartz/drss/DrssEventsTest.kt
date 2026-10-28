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
package com.vitorpamplona.quartz.drss

import com.vitorpamplona.quartz.drss.eventList.DrssEventListEvent
import com.vitorpamplona.quartz.drss.eventList.UnrecognizedKind30102Event
import com.vitorpamplona.quartz.drss.eventList.tags.ListItemTag
import com.vitorpamplona.quartz.drss.feedList.DrssFeedListEvent
import com.vitorpamplona.quartz.drss.feedList.UnrecognizedKind30100Event
import com.vitorpamplona.quartz.drss.feedList.tags.FeedTag
import com.vitorpamplona.quartz.drss.listFolder.DrssListFolderEvent
import com.vitorpamplona.quartz.drss.listFolder.UnrecognizedKind30101Event
import com.vitorpamplona.quartz.drss.listenProgress.DrssListenProgressEvent
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceEvent
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceFolderEvent
import com.vitorpamplona.quartz.drss.provenance.UnrecognizedKind30104Event
import com.vitorpamplona.quartz.drss.provenance.UnrecognizedKind31985Event
import com.vitorpamplona.quartz.drss.show.DrssShowEvent
import com.vitorpamplona.quartz.kinds.KindNames
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip50Search.IndexableFields
import com.vitorpamplona.quartz.nip50Search.SearchFieldExtractor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.Podcasting20EpisodeEvent
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.UnrecognizedKind30054Event
import com.vitorpamplona.quartz.utils.DeterministicSigner
import com.vitorpamplona.quartz.utils.EventFactory
import com.vitorpamplona.quartz.utils.nsecToKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * drss.io's reader kinds, and the other apps' events on the same numbers, in the shapes relays held
 * them on 2026-10-28 (relay.nostr.io, relay.drss.io, nos.lol, relay.damus.io, relay.primal.net).
 * Lists of repeated tags are cut to three and long contents to 300 characters.
 */
class DrssEventsTest {
    private val sig = "00".repeat(64)

    private fun event(
        kind: Int,
        pubKey: String,
        content: String,
        vararg tags: Array<String>,
    ): Event = EventFactory.create("11".repeat(32), pubKey, 1_793_192_579L, kind, arrayOf(*tags), content, sig)

    private val progressCompleted =
        event(
            30054,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "57fb027f8dd3f9518116d98e6a506cc116215b15a79307b0b76c5725057248eb"),
            arrayOf("a", "30023:7d5a54ae2a6004cdb36c3f5772250200b90b1ab2303c8ed97e19add6a9925192:57fb027f8dd3f9518116d98e6a506cc116215b15a79307b0b76c5725057248eb"),
            arrayOf("duration", "1003"),
            arrayOf("completed", "1793139987"),
            arrayOf("plays", "1"),
        )

    private val progressPositionOnly =
        event(
            30054,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "margrevro-2026-10-23-1792817607"),
            arrayOf("a", "30023:9548a6727f5e53885d2bec1c2d753e5023c5691976357d38e4ae508cb754c688:margrevro-2026-10-23-1792817607"),
            arrayOf("position", "0"),
        )

    private val episodeNostrCompass =
        event(
            30054,
            "775954f7314112489a4a29ec692b72386fd60bcceb0308d423101ea979c57a80",
            "<p>Episode42 covers White Noise group polls, offline app sharing with Myco, Marmot history recovery, Nostr releases and ongoing protocol work. Synthetic narration is followed by Max\u2019s recorded contributions.</p><p>TL;DR: Synthetic narration and Max\u2019s recordings cover White Noise polls, Myco app shar",
            arrayOf("d", "logbook-logbook-42"),
            arrayOf("title", "Nostr Compass Podcast #42"),
            arrayOf("published", "1791447015"),
            arrayOf("summary", "Synthetic narration and Max\u2019s recordings cover White Noise polls, Myco app sharing, Marmot history recovery, Nostr releases and ongoing protocol work."),
            arrayOf("image", "https://nostrcompass.org/assets/images/profile.png"),
            arrayOf("audio", "https://compass-blossom.samt.st/7c6c889177fd53ef0c65d66aa539f1345de01691d7dbf8bc45db4fafbe4d98ff", "audio/mpeg"),
            arrayOf("size", "121236209"),
            arrayOf("x", "7c6c889177fd53ef0c65d66aa539f1345de01691d7dbf8bc45db4fafbe4d98ff"),
            arrayOf("duration", "5051"),
            arrayOf("explicit", "no"),
            arrayOf("episode", "42"),
            arrayOf("chapters", "https://compass-blossom.samt.st/e724b00620ec156d244f635ecb6883bc3f0bcd4821d3dd91a52b3e6a9e1b7b1c"),
            arrayOf("podcast:chapters", "https://compass-blossom.samt.st/e724b00620ec156d244f635ecb6883bc3f0bcd4821d3dd91a52b3e6a9e1b7b1c", "application/json+chapters"),
            arrayOf("transcript", "https://compass-blossom.samt.st/4d31ee8b7ff25c048d2262ec198abec6b09e70f9ab8ceb763f8d067fd0759e22.txt"),
            arrayOf("podcast:transcript", "https://compass-blossom.samt.st/4d31ee8b7ff25c048d2262ec198abec6b09e70f9ab8ceb763f8d067fd0759e22.txt", "text/plain"),
            arrayOf("p", "b7ed68b062de6b4a12e51fd5285c1e1e0ed0e5128cda93ab11b4150b55ed32fc"),
            arrayOf("t", "nostr"),
            arrayOf("t", "compass"),
            arrayOf("t", "podcast"),
            arrayOf("alt", "Nostr Compass Podcast episode42: White Noise polls, Myco app sharing, Marmot history recovery, Nostr releases and ongoing protocol work."),
        )

    private val feedlistDrss =
        event(
            30100,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "drss/antennapod-subscriptions"),
            arrayOf("p", "2e2a9a818f5c747d6bca9c32a8d956a545472f777bc501575eb1499f90243e3c", "wss://relay.drss.io", "Active Self Protection Podcast"),
            arrayOf("p", "f3dbbf451e1d129d2dab567f05dd1ef27b4952cef33d03a61296d5656217855c", "wss://relay.drss.io", "Akimbo: A Podcast from Seth Godin"),
            arrayOf("p", "bc4a951dd7cadb89e8abe589b05e4b598284408ff76b358c1e898854c0598224", "wss://relay.drss.io", "Alan Watts Being in the Way"),
        )

    private val feedlistCi =
        event(
            30100,
            "7b354c52eb559cd7aba572415b778de129a15d080f9662f2c54c47ec53e8e84e",
            "Job failed with exit code 1",
            arrayOf("d", "913035c482e1285b12d48c0fa2deef6c898ea0a5468471bf7bdfacb137fa1c28"),
            arrayOf("e", "913035c482e1285b12d48c0fa2deef6c898ea0a5468471bf7bdfacb137fa1c28"),
            arrayOf("status", "failed"),
        )

    private val feedlistRoom =
        event(
            30100,
            "5e13028c3b4a603d4101034cc5fa32edb6591d8b620d96f43b0e35ca26aebc06",
            "",
            arrayOf("d", "1d67ee00-e862-43ba-89e3-2b4918037bbb"),
            arrayOf("room", "katzenraum-nienburg"),
            arrayOf("category", "analog"),
            arrayOf("expires", "1792948760"),
            arrayOf("standort", "NIE"),
        )

    private val feedlistGlo =
        event(
            30100,
            "e4ae5f87cc744e4eaf6f640c4f1b8e37e3229c0c993ab211053405c484869a93",
            "{\"spec\":\"org.bitos.glo\",\"version\":1,\"type\":\"catalog.product\",\"id\":\"8a01b980-8fbb-4451-8134-d23a729fd11f\",\"scope\":{\"organizationId\":\"42124827-a109-4ed5-9a9b-21fe1ec8786a\",\"ownerPubkey\":\"e4ae5f87cc744e4eaf6f640c4f1b8e37e3229c0c993ab211053405c484869a93\"},\"visibility\":\"organization\",\"data\":{\"name\":\"capp",
            arrayOf("d", "org.bitos.glo:v1:catalog.product:8a01b980-8fbb-4451-8134-d23a729fd11f"),
            arrayOf("L", "org.bitos.glo"),
            arrayOf("l", "catalog.product", "org.bitos.glo"),
            arrayOf("t", "glo:type:catalog.product"),
            arrayOf("glo-version", "1"),
            arrayOf("visibility", "organization"),
            arrayOf("organization", "42124827-a109-4ed5-9a9b-21fe1ec8786a"),
            arrayOf("t", "glo:organization:42124827-a109-4ed5-9a9b-21fe1ec8786a"),
            arrayOf("p", "e4ae5f87cc744e4eaf6f640c4f1b8e37e3229c0c993ab211053405c484869a93"),
            arrayOf("client", "bnos"),
            arrayOf("alt", "catalog.product 8a01b980-8fbb-4451-8134-d23a729fd11f"),
        )

    private val folderRoot =
        event(
            30101,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "drss"),
            arrayOf("title", "DRSS lists"),
            arrayOf("a", "30000:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:drss-feeds", "wss://relay.nostr.io"),
            arrayOf("a", "30004:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:drss-podcasts", "wss://relay.nostr.io"),
            arrayOf("a", "30004:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:drss-links", "wss://relay.nostr.io"),
        )

    private val folderOpml =
        event(
            30101,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "Root list with 0 feeds and 2 categories",
            arrayOf("d", "rss/small-test-opml"),
            arrayOf("title", "small test opml"),
            arrayOf("import-source", "opml"),
            arrayOf("import-date", "2025-10-04T13:25:54-05:00"),
            arrayOf("a", "30003:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:rss/software"),
            arrayOf("a", "30003:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:rss/go"),
        )

    private val folderVault =
        event(
            30101,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "vault-provenance"),
            arrayOf("a", "30104:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:vault-provenance/_root", "wss://relay.nostr.io"),
        )

    private val folderRoom =
        event(
            30101,
            "629fe1efddd0b711ddac9edad17905b10bbe98f77bf5239df33621eabc7508f0",
            "",
            arrayOf("s", "1d67ee00-e862-43ba-89e3-2b4918037bbb"),
            arrayOf("p", "5e13028c3b4a603d4101034cc5fa32edb6591d8b620d96f43b0e35ca26aebc06"),
        )

    private val folderMatch =
        event(
            30101,
            "de062f1200b5ceeb53430b8125cfd78930e7d32ed9c4bb8afea1ce7cdf4855b3",
            "{\"type\":\"MatchHello\",\"matchId\":\"9a25b894bd28e127729443ba9d27153c\",\"seq\":0,\"senderId\":\"de062f1200b5ceeb53430b8125cfd78930e7d32ed9c4bb8afea1ce7cdf4855b3\",\"sentAt\":\"2026-10-23T18:29:48.931\",\"settings\":{\"protoVersion\":8,\"seats\":[{\"playerId\":\"RP\",\"kind\":\"local\",\"peerPubkey\":\"de062f1200b5ceeb53430b8125cfd",
            arrayOf("m", "9a25b894bd28e127729443ba9d27153c"),
            arrayOf("d", "9a25b894bd28e127729443ba9d27153c"),
        )

    private val folderRodada =
        event(
            30101,
            "e33641eda1b7241eeccdca6941a435f6b3b3f8416c71203470fa9d95b91c3ccd",
            "",
            arrayOf("d", "rodada-002"),
            arrayOf("title", "Rodada 2"),
            arrayOf("inicio", "1790739283"),
            arrayOf("fim", "1790746483"),
            arrayOf("projeto", "30100:880665fbff108d359c013de707814b5c9b94ee88cac99f651e29190b4a0043e7:uhshhs"),
            arrayOf("projeto", "30100:b4330aa154694a4c86309bb4dbb8acea64ae6bd611b210f3327353ea272c59d7:test35"),
            arrayOf("projeto", "30100:a1593bbdb1e50fd66e0fffc881c28d52ede2c252c93f9663e1954090c0567a52:teste6"),
            arrayOf("alt", "Rodada 2 da Vitrine Coletiva"),
        )

    private val evlistDrss =
        event(
            30102,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "ambient-samples"),
            arrayOf("e", "646b80fbbc532824df8f542b90fe0d6e8945c370a05455ea358298627f8d5efd", "wss://relay.nostr.io", "1791906278"),
            arrayOf("e", "1af10e47c217e547efd6c7411c37951af6e1de58562f813caa10b98572937265", "wss://relay.nostr.io", "1791906278"),
            arrayOf("e", "f3b232cc72d37e737921130b6ad47e0264083528e036c47ace70311f97c5ca6d", "wss://relay.nostr.io", "1791906278"),
        )

    private val evlistTitled =
        event(
            30102,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "keyes-lora-v1"),
            arrayOf("title", "keyes lora v1"),
            arrayOf("e", "06d0af977ed2237bdfaa7ef742ec2df0f8bc17902c6b45376a1b9d01a1fa4289", "wss://relay.nostr.io", "1789237171"),
            arrayOf("e", "8f69298d96a61d3716e2e81863f535d0e4da744f161943b83bd5809d487ca4bf", "wss://relay.nostr.io", "1789237171"),
            arrayOf("e", "d084df7ba2079d0d2c09553e61b6921115996bce7f07613675342f379b86cdcc", "wss://relay.nostr.io", "1789237171"),
        )

    private val evlistVoto =
        event(
            30102,
            "eaecf940732d300080c3b1d6a6afb1d713c99c11d3b4f26a438c228e1713680f",
            "",
            arrayOf("d", "rodada-001"),
            arrayOf("a", "30101:e33641eda1b7241eeccdca6941a435f6b3b3f8416c71203470fa9d95b91c3ccd:rodada-001"),
            arrayOf("voto", "30100:e10883607cab10d9c58e29e32cb339aa9bf6174a3ede618e7ee84ec3cc51032d:teste", "kjhhj"),
            arrayOf("alt", "Voto na Rodada 1 da Vitrine Coletiva"),
        )

    private val evlistWaifu =
        event(
            30102,
            "247859d46dc32a8e2fd0423ce0a588b6b0b7dbddd3ddc7ddb24d408e3ebc6285",
            "",
            arrayOf("d", "247859d46dc32a8e2fd0423ce0a588b6b0b7dbddd3ddc7ddb24d408e3ebc6285:1e3194fed039a9003110e04c81d2fee9c39e89dd"),
            arrayOf("e", "b54d21c7e5d66c320c9a3995b374d5196ad2855fbd0c39c34b0280f9e5db1d19"),
            arrayOf("i", "1e3194fed039a9003110e04c81d2fee9c39e89dd"),
            arrayOf("name", "Qwen3.8-Flash-Next-APEX \u00b7 gguf \u00b7 mudler"),
            arrayOf("client", "waifu-magnet-25"),
            arrayOf("nonce", "4275689930"),
        )

    private val evlistVouch =
        event(
            30102,
            "e2d3e5dd7eef26554c3d54f218b0bf3a9317404f22ef74c8c1fdceed332995e4",
            "{\"v\":1,\"count\":1}",
            arrayOf("d", "vouches"),
            arrayOf("p", "61799f2fb76bbc2001a5750f40aa8a79cfd9fd2135facaf194ba4f2dae3470d3"),
            arrayOf("t", "nostrride"),
        )

    private val pfolderDrss =
        event(
            30104,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "",
            arrayOf("d", "vault-provenance/_root"),
            arrayOf("title", "Root"),
            arrayOf("a", "31985:dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5:4a6edcf5840db6709d8d82216991031a80416246f25834368adb8de8cf95365d", "wss://relay.nostr.io"),
        )

    private val pfolderRodada =
        event(
            30104,
            "e33641eda1b7241eeccdca6941a435f6b3b3f8416c71203470fa9d95b91c3ccd",
            "",
            arrayOf("d", "rodada-001"),
            arrayOf("title", "Rodada 1"),
            arrayOf("a", "30101:e33641eda1b7241eeccdca6941a435f6b3b3f8416c71203470fa9d95b91c3ccd:rodada-001"),
            arrayOf("votantes", "11"),
            arrayOf("votantes_publico", "1"),
            arrayOf("posicao", "30100:e10883607cab10d9c58e29e32cb339aa9bf6174a3ede618e7ee84ec3cc51032d:teste", "6"),
            arrayOf("posicao", "30100:eafbb9fe15fb91f18c5a154b88edad6a96346f158ebeb28db93794d52b0cf856:teste", "3"),
            arrayOf("posicao", "30100:ba52935b178d5f3aec4f6a978a55ca2f2d0bee796ba0b98bdeeb87dc1c9b6e1d:tetey", "2"),
            arrayOf("publico", "30100:e10883607cab10d9c58e29e32cb339aa9bf6174a3ede618e7ee84ec3cc51032d:teste", "1"),
            arrayOf("alt", "Resultado da Rodada 1 da Vitrine Coletiva"),
        )

    private val provDrss =
        event(
            31985,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "I want to find people who are interested in jev and able to put it to good use, and see what other kinds of things they are doing",
            arrayOf("d", "4a6edcf5840db6709d8d82216991031a80416246f25834368adb8de8cf95365d"),
            arrayOf("L", "systems.buildtall.provenance"),
            arrayOf("l", "provenance", "systems.buildtall.provenance"),
            arrayOf("p", "4a6edcf5840db6709d8d82216991031a80416246f25834368adb8de8cf95365d", "wss://relay.nostr.io"),
            arrayOf("a", "30023:4093f1972489272756a98103e457a26914f00216317cf38d57710b431360d30b:seroter-daily-reading-872", "wss://relay.nostr.io"),
            arrayOf("i", "https://blog.senko.net/feed/"),
            arrayOf("k", "web"),
        )

    private val provDitto =
        event(
            31985,
            "19033acda8b18a29d15545bc91f05656b1d0f4d697cc68d6ef685d26624d8047",
            "",
            arrayOf("d", "isbn:9783442366064"),
            arrayOf("k", "isbn"),
            arrayOf("rating", "0.8"),
            arrayOf("client", "Ditto", "31990:781a1527055f74c1f70230f10384609b34548f8ab6a0a6caa74025827f9fdae5:ditto"),
            arrayOf("published_at", "1791314445"),
        )

    private val provGame =
        event(
            31985,
            "e1d4719b7303c2d583ea78277c04c18003f423b7ef2b528f981b02964dd3e2fb",
            "{\"game_key\":\"super-mario-galaxy-nintendo-wii\",\"liked\":true,\"platform\":\"Nintendo Wii\",\"rating\":4.5,\"review_date\":\"2026-06-01\",\"spoiler\":false,\"steam_app_id\":null,\"tags\":\"[\\\"platformer\\\"]\",\"text\":\"galaxy\",\"title\":\"Super Mario Galaxy\",\"year\":2007}",
            arrayOf("d", "super-mario-galaxy-nintendo-wii"),
        )

    private val show =
        event(
            31341,
            "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5",
            "{\"name\":\"drift\",\"slug\":\"drift\",\"language\":\"en-us\",\"episode_retention\":\"336h\",\"cover_style\":\"mead-berkey-far-future\",\"renderer\":\"ambient\",\"tags\":[\"podcast\",\"music\",\"ambient\",\"generative\"],\"products\":[\"script\",\"summary\",\"article\",\"cover\",\"audio\"],\"schedule\":[{\"time\":\"05:00\",\"zone\":\"America/Chicago\"}],",
            arrayOf("d", "drift"),
            arrayOf("p", "3bd809035c152a0ca4927d94fd3b901f4cd6346a31a5b2b6c88a2689a109cf8b"),
            arrayOf("status", "active"),
            arrayOf("alt", "Podcast show: drift"),
        )

    // ---- 30054 listening progress ----

    @Test
    fun listenProgressReadsEveryTag() {
        val done = assertIs<DrssListenProgressEvent>(progressCompleted)
        assertEquals(null, done.position())
        assertEquals(1003L, done.durationInSeconds())
        assertEquals(1793139987L, done.completedAt())
        assertEquals(1L, done.plays())
        assertEquals("30023:7d5a54ae2a6004cdb36c3f5772250200b90b1ab2303c8ed97e19add6a9925192:57fb027f8dd3f9518116d98e6a506cc116215b15a79307b0b76c5725057248eb", done.episode()?.toTag())
        assertEquals(listOf(done.episode()?.toTag()), done.linkedAddressIds())

        val started = assertIs<DrssListenProgressEvent>(progressPositionOnly)
        assertEquals(0L, started.position())
        assertNull(started.durationInSeconds())
        assertNull(started.completedAt())
    }

    @Test
    fun anEpisodeOnTheSameKindStaysAnEpisode() {
        // Nostr Compass carries `duration` too: the title and audio decide first.
        assertIs<Podcasting20EpisodeEvent>(episodeNostrCompass)
    }

    @Test
    fun progressWhoseNumbersAreEmptyIsUnrecognized() {
        val episode = arrayOf("a", "30023:${"dd".repeat(32)}:x")
        assertIs<UnrecognizedKind30054Event>(event(30054, "22".repeat(32), "", arrayOf("d", "x"), episode, arrayOf("position", "")))
        assertIs<UnrecognizedKind30054Event>(event(30054, "22".repeat(32), "", arrayOf("d", "x"), episode, arrayOf("plays", "many")))
        assertIs<DrssListenProgressEvent>(event(30054, "22".repeat(32), "", arrayOf("d", "x"), episode, arrayOf("position", ""), arrayOf("plays", "2")))
    }

    @Test
    fun progressWithoutAnEpisodeIsUnrecognized() {
        assertIs<UnrecognizedKind30054Event>(event(30054, "22".repeat(32), "", arrayOf("d", "x"), arrayOf("position", "10")))
    }

    // ---- 30100 feed list ----

    @Test
    fun feedListReadsItsFeeds() {
        val list = assertIs<DrssFeedListEvent>(feedlistDrss)
        assertEquals(3, list.feeds().size)
        assertEquals(list.feeds().map { it.pubKey }, list.feedKeys())
        assertEquals(list.feedKeys(), list.linkedPubKeys())
        assertEquals(list.feeds().size, list.pubKeyHints().size)
        assertEquals(list.feeds().mapNotNull { it.title }, list.feedTitles())
    }

    @Test
    fun otherAppsOn30100AreUnrecognized() {
        listOf(feedlistCi, feedlistRoom, feedlistGlo).forEach { assertIs<UnrecognizedKind30100Event>(it, it.toJson()) }
    }

    // ---- 30101 list folder ----

    @Test
    fun listFoldersReadTheirChildren() {
        val root = assertIs<DrssListFolderEvent>(folderRoot)
        assertEquals("DRSS lists", root.title())
        assertEquals(listOf(30000, 30004, 30004), root.children().map { it.kind })
        assertIs<DrssListFolderEvent>(folderOpml)
        assertEquals(listOf(30104), assertIs<DrssListFolderEvent>(folderVault).children().map { it.kind })
    }

    @Test
    fun otherAppsOn30101AreUnrecognized() {
        listOf(folderRoom, folderMatch, folderRodada).forEach { assertIs<UnrecognizedKind30101Event>(it, it.toJson()) }
    }

    // ---- 30102 event list ----

    @Test
    fun eventListsReadTheirItems() {
        assertEquals(3, assertIs<DrssEventListEvent>(evlistDrss).items().size)
        val titled = assertIs<DrssEventListEvent>(evlistTitled)
        assertTrue(titled.title() != null)
        assertEquals(titled.items().map { it.eventId }, titled.itemIds())
        assertEquals(titled.itemIds(), titled.linkedEventIds())
    }

    @Test
    fun otherAppsOn30102AreUnrecognized() {
        listOf(evlistVoto, evlistWaifu, evlistVouch).forEach { assertIs<UnrecognizedKind30102Event>(it, it.toJson()) }
    }

    // ---- 30104 provenance folder, 31985 provenance ----

    @Test
    fun provenanceFolderAndRecord() {
        val folder = assertIs<DrssProvenanceFolderEvent>(pfolderDrss)
        assertEquals("Root", folder.title())
        assertEquals(listOf(31985), folder.children().map { it.kind })

        val record = assertIs<DrssProvenanceEvent>(provDrss)
        assertEquals("4a6edcf5840db6709d8d82216991031a80416246f25834368adb8de8cf95365d", record.feedKey())
        assertEquals(listOf(record.feedKey()), record.linkedPubKeys())
        assertEquals(listOf(record.foundThroughAddressId()), record.linkedAddressIds())
        assertEquals("https://blog.senko.net/feed/", record.source())
        assertEquals("web", record.sourceType())
        assertEquals(30023, record.foundThrough()?.kind)
        assertTrue(record.note().startsWith("I want to find people"))
    }

    @Test
    fun otherAppsOn30104And31985AreUnrecognized() {
        assertIs<UnrecognizedKind30104Event>(pfolderRodada)
        assertIs<UnrecognizedKind31985Event>(provDitto)
        assertIs<UnrecognizedKind31985Event>(provGame)
    }

    // ---- 31341 show ----

    @Test
    fun showReadsItsBotAndStatus() {
        val definition = assertIs<DrssShowEvent>(show)
        assertEquals("3bd809035c152a0ca4927d94fd3b901f4cd6346a31a5b2b6c88a2689a109cf8b", definition.showBotKey())
        assertEquals("drift", definition.showName())
        assertTrue(definition.isActive())
        assertEquals("Podcast show: drift", definition.alt())
    }

    // ---- every shape ----

    @Test
    fun theDrssShapesWithTextAreSearchableAndTheRestAreNot() {
        listOf(feedlistDrss, folderRoot, evlistTitled, pfolderDrss, provDrss, show).forEach {
            assertTrue(it is SearchableEvent, it.toJson())
        }
        listOf(progressCompleted, feedlistCi, folderRoom, evlistWaifu, pfolderRodada, provDitto).forEach {
            assertTrue(it is AddressableEvent, it.toJson())
            assertFalse(it is SearchableEvent, it.toJson())
            assertEquals(IndexableFields.None, SearchFieldExtractor.extract(it), it.toJson())
        }
    }

    @Test
    fun searchFieldsRankTitlesAboveTheRest() {
        val folder = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(folderRoot))
        assertEquals(listOf("DRSS lists"), folder.primary)

        val feeds = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(feedlistDrss))
        assertEquals((feedlistDrss as DrssFeedListEvent).feedTitles(), feeds.secondary)

        val showFields = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(show))
        assertEquals(listOf("drift"), showFields.primary)

        val note = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(provDrss))
        assertTrue(note.text!!.startsWith("I want to find people"))
        assertEquals(listOf("https://blog.senko.net/feed/"), note.websites)
    }

    @Test
    fun anEventListItemWithNoRelayIsALinkButNotAHint() {
        val list = assertIs<DrssEventListEvent>(event(30102, "22".repeat(32), "", arrayOf("d", "papers"), arrayOf("e", "aa".repeat(32), "", "1787491182"), arrayOf("e", "bb".repeat(32), "wss://relay.nostr.io", "1787491292")))
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), list.linkedEventIds())
        assertEquals(listOf("bb".repeat(32)), list.eventHints().map { it.eventId })
        assertEquals(1787491182L, list.items().first().addedAt)
    }

    @Test
    fun aProvenanceNoteCitesWhatItMentions() {
        val npub = "npub1hwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwasxw04hu" // "bb" x 32
        val cited = event(31985, "22".repeat(32), "Found through nostr:$npub", arrayOf("d", "cc".repeat(32)), arrayOf("L", DrssProvenanceEvent.NAMESPACE), arrayOf("p", "cc".repeat(32)))
        val record = assertIs<DrssProvenanceEvent>(cited)
        assertEquals(listOf("cc".repeat(32), "bb".repeat(32)), record.linkedPubKeys())
    }

    // ---- builders ----

    private val signer = DeterministicSigner("nsec10g0wheggqn9dawlc0yuv6adnat6n09anr7eyykevw2dm8xa5fffs0wsdsr".nsecToKeyPair())

    private fun <T : Event> roundTrip(signed: T): Event = EventFactory.create(signed.id, signed.pubKey, signed.createdAt, signed.kind, signed.tags, signed.content, signed.sig)

    @Test
    fun everyBuilderParsesBackAsItsClass() {
        val episode = ATag(30023, "dd".repeat(32), "episode-1", RelayUrlNormalizer.normalizeOrNull("wss://relay.drss.io"))
        val bot = PTag("ee".repeat(32), RelayUrlNormalizer.normalizeOrNull("wss://relay.drss.io"))

        val progress = assertIs<DrssListenProgressEvent>(roundTrip(signer.sign<DrssListenProgressEvent>(DrssListenProgressEvent.build(episode, position = 733, durationInSeconds = 2744))))
        assertEquals("episode-1", progress.dTag())
        assertEquals(episode.toTag(), progress.episodeAddressId())
        assertEquals(733L, progress.position())
        assertEquals(2744L, progress.durationInSeconds())

        val feeds = assertIs<DrssFeedListEvent>(roundTrip(signer.sign<DrssFeedListEvent>(DrssFeedListEvent.build("drss/podcasts", listOf(FeedTag(bot.pubKey, bot.relayHint, "Conversations with Tyler")), title = "Podcasts"))))
        assertEquals(listOf(FeedTag(bot.pubKey, bot.relayHint, "Conversations with Tyler")), feeds.feeds())
        assertEquals("Podcasts", feeds.title())

        val child = ATag(30004, "dd".repeat(32), "drss-podcasts", null)
        val folder = assertIs<DrssListFolderEvent>(roundTrip(signer.sign<DrssListFolderEvent>(DrssListFolderEvent.build("drss", "DRSS lists", listOf(child), language = "en-us"))))
        assertEquals(listOf(child), folder.children())
        assertEquals("en-us", folder.language())

        val item = ListItemTag("aa".repeat(32), null, 1787491182L)
        val items = assertIs<DrssEventListEvent>(roundTrip(signer.sign<DrssEventListEvent>(DrssEventListEvent.build("papers", listOf(item), title = "Papers"))))
        assertEquals(listOf(item), items.items())

        val record = assertIs<DrssProvenanceEvent>(roundTrip(signer.sign<DrssProvenanceEvent>(DrssProvenanceEvent.build(bot, "https://blog.senko.net/feed/", "web", "Worth keeping", foundThrough = episode))))
        assertEquals(bot.pubKey, record.dTag())
        assertEquals(bot.pubKey, record.feedKey())
        assertEquals("web", record.sourceType())
        assertEquals(episode.toTag(), record.foundThroughAddressId())

        val recordAddress = ATag(DrssProvenanceEvent.KIND, signer.pubKey, bot.pubKey, null)
        val provenanceFolder = assertIs<DrssProvenanceFolderEvent>(roundTrip(signer.sign<DrssProvenanceFolderEvent>(DrssProvenanceFolderEvent.build("vault-provenance/_root", "Root", listOf(recordAddress)))))
        assertEquals(listOf(recordAddress), provenanceFolder.children())

        val definition = assertIs<DrssShowEvent>(roundTrip(signer.sign<DrssShowEvent>(DrssShowEvent.build("drift", "drift", bot, "{\"name\":\"drift\"}"))))
        assertEquals("drift", definition.showName())
        assertEquals(bot.pubKey, definition.showBotKey())
        assertTrue(definition.isActive())
    }

    @Test
    fun aBuilderLeftWithoutItsShapeParsesBackAsUnrecognized() {
        val emptyFolder = DrssListFolderEvent.build("empty", "Empty", emptyList())
        assertIs<UnrecognizedKind30101Event>(signer.sign<Event>(emptyFolder.createdAt, emptyFolder.kind, emptyFolder.tags, emptyFolder.content))

        val noNumbers = DrssListenProgressEvent.build(ATag(30023, "dd".repeat(32), "x", null))
        assertIs<UnrecognizedKind30054Event>(signer.sign<Event>(noNumbers.createdAt, noNumbers.kind, noNumbers.tags, noNumbers.content))
    }

    @Test
    fun probeAnswersAsTheDrssClassesAndEveryKindIsNamed() {
        assertIs<DrssFeedListEvent>(EventFactory.probe(30100))
        assertIs<DrssListFolderEvent>(EventFactory.probe(30101))
        assertIs<DrssEventListEvent>(EventFactory.probe(30102))
        assertIs<DrssProvenanceFolderEvent>(EventFactory.probe(30104))
        assertIs<DrssProvenanceEvent>(EventFactory.probe(31985))
        assertIs<DrssShowEvent>(EventFactory.probe(31341))
        listOf(30100, 30101, 30102, 30104, 31341, 31985).forEach { assertTrue(KindNames.nameFor(it)!!.startsWith("DRSS"), "kind $it") }
    }
}
