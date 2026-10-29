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

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListDocument
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.PinListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.AddressBookmark
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark
import com.vitorpamplona.quartz.nip51Lists.gitAuthorList.GitAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.gitAuthorList.tags.GitAuthorTag
import com.vitorpamplona.quartz.nip51Lists.gitRepositoryList.GitRepositoryListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiAuthorList.GoodWikiAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiRelayList.GoodWikiRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.mediaFollowList.MediaFollowListEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.UserTag
import com.vitorpamplona.quartz.nip51Lists.simpleGroupList.GroupTag
import com.vitorpamplona.quartz.nip51Lists.simpleGroupList.SimpleGroupListEvent
import com.vitorpamplona.quartz.nip53LiveActivities.nestsServers.NestsServer
import com.vitorpamplona.quartz.nip53LiveActivities.nestsServers.NestsServersEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayInfo
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType
import com.vitorpamplona.quartz.nipA3PaymentTargets.PaymentTarget
import com.vitorpamplona.quartz.nipA3PaymentTargets.PaymentTargetsEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.offer.Bolt12OfferListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import com.vitorpamplona.quartz.nipF4Podcasts.favorites.FavoritePodcastsListEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Event-class cleanup, batch 4: the list kinds' new build(). Each template is signed, read back
 * from its JSON as its own class, and read through the class's accessors; the tags are the ones
 * the old create() wrote, which now signs the same template.
 */
class ListBuildersTest {
    private val signer = NostrSignerInternal(KeyPair())
    private val relay1 = RelayUrlNormalizer.normalizeOrNull("wss://relay1.example.com/")!!
    private val relay2 = RelayUrlNormalizer.normalizeOrNull("wss://relay2.example.com/")!!
    private val friend = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    private suspend inline fun <reified T : Event> signAndParse(template: EventTemplate<T>): T {
        val parsed = Event.fromJson(signer.sign(template).toJson())
        assertTrue(parsed.verify())
        return assertIs<T>(parsed)
    }

    private fun Event.tagList() = tags.map { it.toList() }

    @Test
    fun advertisedRelayList() =
        runTest {
            val relays = listOf(AdvertisedRelayInfo(relay1, AdvertisedRelayType.READ), AdvertisedRelayInfo(relay2, AdvertisedRelayType.BOTH))
            val event = signAndParse(AdvertisedRelayListEvent.build(relays))
            assertEquals(listOf(listOf("r", relay1.url, "read"), listOf("r", relay2.url)), event.tagList())
            assertEquals(listOf(relay1, relay2), event.allReadRelaysNorm())
            assertEquals(listOf(relay2), event.allWriteRelaysNorm())
        }

    @Test
    fun dmAndKeyPackageRelayLists() =
        runTest {
            val dm = signAndParse(DmRelayListEvent.build(listOf(relay1, relay2)))
            assertEquals(listOf(listOf("relay", relay1.url), listOf("relay", relay2.url)), dm.tagList())
            assertEquals(listOf(relay1, relay2), dm.allRelays())

            val keyPackages = signAndParse(KeyPackageRelayListEvent.build(listOf(relay1)))
            assertEquals(listOf(listOf("relay", relay1.url)), keyPackages.tagList())
            assertEquals(listOf(relay1), keyPackages.allRelays())
        }

    @Test
    fun blossomServers() =
        runTest {
            val event = signAndParse(BlossomServersEvent.build(listOf("https://cdn.example.com", "https://cdn2.example.com")))
            assertEquals(listOf(listOf("server", "https://cdn.example.com"), listOf("server", "https://cdn2.example.com")), event.tagList())
            assertEquals(listOf("https://cdn.example.com", "https://cdn2.example.com"), event.servers())
        }

    @Test
    fun bolt12Offers() =
        runTest {
            val offer = "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrc"
            val event = signAndParse(Bolt12OfferListEvent.build(listOf(offer)))
            assertEquals(listOf(listOf("offer", offer)), event.tagList())
            assertEquals(offer, event.firstOffer())
        }

    @Test
    fun paymentTargets() =
        runTest {
            val target = PaymentTarget("bitcoin", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")
            val event = signAndParse(PaymentTargetsEvent.build(listOf(target)))
            assertEquals(listOf(target), event.paymentTargets())
        }

    @Test
    fun nestsServers() =
        runTest {
            val server = NestsServer("https://moq.example.com:4443", "https://moq-auth.example.com")
            val event = signAndParse(NestsServersEvent.build(listOf(server)))
            assertEquals(listOf(listOf("server", server.relay, server.auth)), event.tagList())
            assertEquals(listOf(server), event.servers())
        }

    @Test
    fun contactList() =
        runTest {
            val event = signAndParse(ContactListEvent.build(listOf(ContactTag(friend, relay1, "bob"))))
            assertEquals(listOf(listOf("p", friend, relay1.url, "bob")), event.tagList())
            assertEquals("", event.content)
            assertTrue(event.isFollowing(friend))
            assertEquals(setOf(friend), event.verifiedFollowKeySet())
        }

    @Test
    fun concordListsEncryptToTheAuthor() =
        runTest {
            val communities = signAndParse(ConcordCommunityListEvent.build(emptyList(), signer))
            assertEquals(emptyList(), communities.tagList())
            assertEquals(emptyList(), communities.decrypt(signer))

            val fragment = signAndParse(ConcordCommunityListFragmentEvent.build(2, "secret", signer))
            assertEquals(listOf(listOf("d", "2")), fragment.tagList())
            assertEquals(2, fragment.index())
            assertEquals("secret", fragment.decryptPlaintext(signer))

            val invites = signAndParse(ConcordInviteListEvent.build(ConcordInviteListDocument.EMPTY, signer))
            assertEquals(emptyList(), invites.tagList())
            assertEquals(0, invites.decrypt(signer)?.entries?.size)
        }

    private val bob = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val repo = Address(30617, friend, "quartz")

    @Test
    fun privateListsKeepTheirPublicTagsAndEncryptThePrivateOnes() =
        runTest {
            val podcasts = signAndParse(FavoritePodcastsListEvent.build(listOf(UserTag(friend)), listOf(UserTag(bob)), signer))
            assertEquals(listOf(listOf("p", friend)), podcasts.tagList())
            assertEquals(listOf(friend), podcasts.publicFavorites().map { it.pubKey })
            assertEquals(listOf(bob), podcasts.privateFavorites(signer)?.map { it.pubKey })

            val authors = signAndParse(GitAuthorListEvent.build(listOf(GitAuthorTag(friend)), listOf(GitAuthorTag(bob)), signer))
            assertEquals(listOf(friend), authors.publicAuthors().map { it.pubKey })
            assertEquals(listOf(bob), authors.privateAuthors(signer)?.map { it.pubKey })

            val repositories = signAndParse(GitRepositoryListEvent.build(listOf(AddressBookmark(repo)), emptyList(), signer))
            assertEquals(listOf(listOf("a", repo.toValue())), repositories.tagList())
            assertEquals(listOf(repo), repositories.publicRepositories().map { it.address })
            assertEquals(emptyList(), repositories.privateRepositories(signer)?.map { it.address })

            val wikiAuthors = signAndParse(GoodWikiAuthorListEvent.build(emptyList(), listOf(UserTag(bob)), signer))
            assertEquals(emptyList(), wikiAuthors.tagList())
            assertEquals(listOf(bob), wikiAuthors.privateAuthors(signer)?.map { it.pubKey })

            val wikiRelays = signAndParse(GoodWikiRelayListEvent.build(listOf(relay1), listOf(relay2), signer))
            assertEquals(listOf(listOf("relay", relay1.url)), wikiRelays.tagList())
            assertEquals(listOf(relay1), wikiRelays.publicRelays())
            assertEquals(listOf(relay2), wikiRelays.privateRelays(signer))

            val media = signAndParse(MediaFollowListEvent.build(listOf(UserTag(friend), UserTag(bob)), emptyList(), signer))
            assertEquals(listOf(friend, bob), media.publicFollows().map { it.pubKey })

            val groups = signAndParse(SimpleGroupListEvent.build(listOf(GroupTag("g1", relay1.url, "Dev")), emptyList(), signer))
            assertEquals(listOf(listOf("group", "g1", relay1.url, "Dev")), groups.tagList())
            assertEquals(listOf("g1"), groups.publicGroups().map { it.groupId })
        }

    @Test
    fun pinListKeepsPinOrder() =
        runTest {
            val first = "a1".repeat(32)
            val second = "b2".repeat(32)
            val pins = signAndParse(PinListEvent.build(listOf(EventBookmark(first), EventBookmark(second))))
            assertEquals(listOf(first, second), pins.pinnedEventIds())
            assertEquals(listOf(listOf("e", first)), signAndParse(PinListEvent.build(listOf(EventBookmark(first)))).tagList())
        }
}
