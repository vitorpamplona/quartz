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
package com.vitorpamplona.quartz.nip01Core.hints

import com.vitorpamplona.quartz.buzz.cwChannelWindow.ThreadSummaryEvent
import com.vitorpamplona.quartz.experimental.music.playlist.MusicPlaylistEvent
import com.vitorpamplona.quartz.experimental.ratings.EntityRatingEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelCreateEvent
import com.vitorpamplona.quartz.nip34Git.ci.manualTrigger.CiManualTriggerEvent
import com.vitorpamplona.quartz.nip34Git.ci.secretUpdate.CiSecretUpdateEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceRequest.CiServiceRequestEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceStop.CiServiceStopEvent
import com.vitorpamplona.quartz.nip34Git.coverNote.GitCoverNoteEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip51Lists.favoriteFollowSetsList.FavoriteFollowSetsListEvent
import com.vitorpamplona.quartz.nip51Lists.interestList.InterestListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.FavoriteRelayListEvent
import com.vitorpamplona.quartz.nip52Calendar.rsvp.CalendarRSVPEvent
import com.vitorpamplona.quartz.nip58Badges.accepted.AcceptedBadgeSetEvent
import com.vitorpamplona.quartz.nip58Badges.award.BadgeAwardEvent
import com.vitorpamplona.quartz.nip58Badges.profile.ProfileBadgesEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterProtocol
import com.vitorpamplona.quartz.nip69P2pOrderEvents.robosatsRating.RoboSatsCoordinatorRatingEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.nip75ZapGoals.ZapGoalEvent
import com.vitorpamplona.quartz.nip99Classifieds.collection.ProductCollectionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Each `linkedXxx()` reads its references through a method named for what they are to the event
 * (`mentionKeys()`, `coordinatorKeys()`, `favoriteAddressIds()`, …). These pin both: the named
 * method returns what the override used to compute from the raw tags, and the override returns
 * the named method, so the graph links and the outbox lookups did not change.
 */
class SemanticLinkMethodsTest {
    private val id = "00".repeat(32)
    private val sig = "00".repeat(64)
    private val pk1 = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val pk2 = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val e1 = "a1".repeat(32)
    private val e2 = "b2".repeat(32)
    private val followSet = "30000:$pk1:friends"
    private val article = "30023:$pk2:article"

    /** Two good `p`, `e` and `a` tags each, a malformed one of each, and a `q` per target type. */
    private val tags =
        arrayOf(
            arrayOf("p", pk1, "wss://relay.example.com/"),
            arrayOf("p", "not-a-key"),
            arrayOf("p", pk2),
            arrayOf("e", e1),
            arrayOf("e", "short"),
            arrayOf("e", e2, "", "reply"),
            arrayOf("a", followSet),
            arrayOf("a", article),
            arrayOf("a", "garbage"),
            arrayOf("q", e1),
            arrayOf("q", article),
        )

    private val keys = listOf(pk1, pk2)
    private val eventIds = listOf(e1, e2)

    /** What `ATag::parseAddressId` keeps: any non-empty value. */
    private val rawAddressIds = listOf(followSet, article, "garbage")

    /** What `parseValidAddress` keeps: well-formed coordinates only. */
    private val validAddressIds = listOf(followSet, article)

    @Test
    fun acceptedBadgeSet() {
        val event = AcceptedBadgeSetEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.mentionKeys())
        assertEquals(keys, event.mentions().map { it.pubKey })
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun profileBadges() {
        val event = ProfileBadgesEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.mentionKeys())
        assertEquals(keys, event.mentions().map { it.pubKey })
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun badgeAward() {
        val event = BadgeAwardEvent(id, pk1, 1, tags, "", sig)
        assertEquals(eventIds, event.mentionedEventIds())
        assertEquals(eventIds, event.mentionedEvents().map { it.eventId })
        assertEquals(eventIds, event.linkedEventIds())
        assertEquals(rawAddressIds, event.awardDefinitionIds())
        assertEquals(rawAddressIds, event.linkedAddressIds())
    }

    @Test
    fun calendarRsvp() {
        val event = CalendarRSVPEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.calendarEventAuthorKeys())
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun channelCreate() {
        val event = ChannelCreateEvent(id, pk1, 1, tags, "", sig)
        assertEquals(rawAddressIds, event.mentionedAddressIds())
        assertEquals(validAddressIds, event.mentionedAddresses().map { it.toTag() })
        assertEquals(rawAddressIds, event.linkedAddressIds())
        assertEquals(listOf(id), event.linkedEventIds())
    }

    @Test
    fun ciManualTriggerAddsThePullRequestAuthor() {
        val event = CiManualTriggerEvent(id, pk1, 1, tags + arrayOf(arrayOf("E", e1), arrayOf("P", pk2)), "", sig)
        assertEquals(keys, event.coordinatorKeys())
        // Two `p` tags make the request ambiguous, but every one of them is still linked.
        assertNull(event.coordinator())
        assertEquals(keys + pk2, event.linkedPubKeys())
    }

    @Test
    fun ciSecretUpdateServiceRequestAndStop() {
        val events =
            listOf(
                CiSecretUpdateEvent(id, pk1, 1, tags, "", sig).let { it.coordinatorKeys() to it.linkedPubKeys() },
                CiServiceRequestEvent(id, pk1, 1, tags, "", sig).let { it.coordinatorKeys() to it.linkedPubKeys() },
                CiServiceStopEvent(id, pk1, 1, tags, "", sig).let { it.coordinatorKeys() to it.linkedPubKeys() },
            )
        events.forEach { (coordinatorKeys, linked) ->
            assertEquals(keys, coordinatorKeys)
            assertEquals(keys, linked)
        }
    }

    @Test
    fun communityDefinition() {
        val event = CommunityDefinitionEvent(id, pk1, 1, tags, "", sig)
        assertEquals(eventIds, event.mentionedEventIds())
        assertEquals(listOf(e1), event.quotedEventIds())
        assertEquals(eventIds + e1, event.linkedEventIds())
        assertEquals(rawAddressIds, event.mentionedAddressIds())
        assertEquals(listOf(article), event.quotedAddressIds())
        assertEquals(rawAddressIds + article, event.linkedAddressIds())
        // The owner moderates without a tag, so it is not a link.
        assertEquals(keys, event.listedModeratorKeys())
        assertEquals(keys, event.linkedPubKeys())
        assertEquals(keys + pk1, event.moderatorKeys())
    }

    @Test
    fun entityRatingReadsBothAddressTags() {
        val event = EntityRatingEvent(id, pk1, 1, tags + arrayOf(arrayOf("A", article)), "", sig)
        assertEquals(rawAddressIds + article, event.targetAddressIds())
        assertEquals(rawAddressIds + article, event.linkedAddressIds())
    }

    @Test
    fun favoriteFollowSetsLinksEveryKind() {
        val event = FavoriteFollowSetsListEvent(id, pk1, 1, tags, "", sig)
        assertEquals(validAddressIds, event.favoriteAddressIds())
        assertEquals(validAddressIds, event.linkedAddressIds())
        assertEquals(listOf(followSet), event.publicFavoriteFollowSets().map { it.address.toValue() })
    }

    @Test
    fun favoriteRelayListLinksEveryKind() {
        val event = FavoriteRelayListEvent(id, pk1, 1, tags, "", sig)
        assertEquals(validAddressIds, event.favoriteAddressIds())
        assertEquals(validAddressIds, event.linkedAddressIds())
        assertEquals(emptyList(), event.publicRelaySets())
    }

    @Test
    fun interestListLinksEveryKind() {
        val event = InterestListEvent(id, pk1, 1, tags, "", sig)
        assertEquals(validAddressIds, event.subscribedAddressIds())
        assertEquals(validAddressIds, event.linkedAddressIds())
        assertEquals(emptyList(), event.publicInterestSets())
    }

    @Test
    fun giftWrap() {
        val event = GiftWrapEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.recipientKeys())
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun gitCoverNote() {
        val event = GitCoverNoteEvent(id, pk1, 1, tags, "", sig)
        assertEquals(eventIds, event.rootEventIds())
        assertEquals(listOf(e1), event.quotedEventIds())
        assertEquals(eventIds + e1, event.linkedEventIds())
        assertEquals(keys, event.rootAuthorKeys())
        assertEquals(keys, event.linkedPubKeys())
        assertEquals(listOf(article), event.quotedAddressIds())
        assertEquals(listOf(article), event.linkedAddressIds())
    }

    @Test
    fun gitPatch() {
        val event = GitPatchEvent(id, pk1, 1, tags, "", sig)
        assertEquals(eventIds, event.threadEventIds())
        assertEquals(eventIds, event.linkedEventIds())
    }

    @Test
    fun jesterSkipsTheStartPositionHash() {
        val event = JesterEvent(id, pk1, 1, arrayOf(arrayOf("e", JesterProtocol.START_POSITION_HASH)) + tags, "", sig)
        assertEquals(eventIds, event.gameEventIds())
        assertEquals(eventIds, event.linkedEventIds())
        assertEquals(keys, event.opponentKeys())
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun musicPlaylist() {
        val event = MusicPlaylistEvent(id, pk1, 1, tags, "", sig)
        assertEquals(validAddressIds, event.itemAddressIds())
        assertEquals(validAddressIds, event.linkedAddressIds())
    }

    @Test
    fun productCollection() {
        val event = ProductCollectionEvent(id, pk1, 1, tags, "", sig)
        assertEquals(rawAddressIds, event.itemAddressIds())
        assertEquals(rawAddressIds, event.linkedAddressIds())
    }

    @Test
    fun roboSatsCoordinatorRating() {
        val event = RoboSatsCoordinatorRatingEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.coordinatorKeys())
        assertEquals(pk1, event.coordinator())
        assertEquals(keys, event.linkedPubKeys())
    }

    @Test
    fun threadSummary() {
        val content = """{"reply_count":2,"descendant_count":3,"participants":["$pk1","not-a-key"]}"""
        val event = ThreadSummaryEvent(id, pk1, 1, tags, content, sig)
        assertEquals(eventIds, event.rootEventIds())
        assertEquals(eventIds, event.linkedEventIds())
        assertEquals(listOf(pk1), event.participantKeys())
        assertEquals(listOf(pk1), event.linkedPubKeys())
    }

    @Test
    fun zapGoal() {
        val event = ZapGoalEvent(id, pk1, 1, tags, "", sig)
        assertEquals(keys, event.mentionKeys())
        assertEquals(keys, event.mentions().map { it.pubKey })
        assertEquals(keys, event.linkedPubKeys())
    }
}
