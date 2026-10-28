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

import com.vitorpamplona.quartz.experimental.edits.TextNoteModificationEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip38UserStatus.UserStatusEvent
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestPing
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcNotificationEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcRequestEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcResponseEvent
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.PayInvoiceMethod
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.PayInvoiceSuccessResponse
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.PaymentReceivedNotification
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.EphemeralGiftWrapEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Event-class cleanup, batch 4: builders for kinds whose content is encrypted, or that were only
 * created already signed. Each create() now signs its kind's build(); these pin the tags it writes
 * (shape and order, as before) and read the result back.
 */
class SignerBuildersTest {
    private val alice = NostrSignerInternal(KeyPair())
    private val bob = NostrSignerInternal(KeyPair())
    private val relay = RelayUrlNormalizer.normalizeOrNull("wss://inbox.example.com/")!!
    private val noteId = "a1".repeat(32)

    private suspend inline fun <reified T : Event> parse(event: Event): T {
        val parsed = Event.fromJson(event.toJson())
        assertTrue(parsed.verify())
        return assertIs<T>(parsed)
    }

    private fun Event.tagList() = tags.map { it.toList() }

    private suspend fun note() = alice.sign<TextNoteEvent>(TextNoteEvent.build("gm"))

    @Test
    fun noteModification() =
        runTest {
            val edit = parse<TextNoteModificationEvent>(TextNoteModificationEvent.create("gm!", noteId, bob.pubKey, "typo", alice))
            assertEquals(listOf(listOf("e", noteId), listOf("p", bob.pubKey), listOf("summary", "typo")), edit.tagList())
            assertEquals("gm!", edit.content)

            val original = note()
            val bundled = parse<TextNoteModificationEvent>(alice.sign(TextNoteModificationEvent.build("gm!", EventHintBundle(original, relay), notifyAuthor = true)))
            assertEquals(listOf("e", original.id, relay.url, original.pubKey), bundled.tags[0].toList())
            assertEquals(listOf("p", original.pubKey), bundled.tags[1].toList())
        }

    @Test
    fun userStatusKeepsItsTagOrderAndDropsAnInvalidAddress() =
        runTest {
            val address = "30311:${bob.pubKey}:stream"
            val status = parse<UserStatusEvent>(UserStatusEvent.create("listening", UserStatusEvent.MUSIC, 1_800_000_000, "https://music.example.com", bob.pubKey, noteId, address, signer = alice))
            assertEquals(
                listOf(
                    listOf("d", "music"),
                    listOf("expiration", "1800000000"),
                    listOf("r", "https://music.example.com"),
                    listOf("p", bob.pubKey),
                    listOf("e", noteId),
                    listOf("a", address),
                ),
                status.tagList(),
            )
            assertEquals(bob.pubKey, status.referencedProfile())
            assertEquals(address, status.referencedAddressId())

            val invalid = parse<UserStatusEvent>(UserStatusEvent.create("hi", addressableId = "not-an-address", signer = alice))
            assertEquals(listOf(listOf("d", "general")), invalid.tagList())

            val emoji = parse<UserStatusEvent>(alice.sign(UserStatusEvent.build("", statusEmoji = "\uD83C\uDF34")))
            assertEquals("\uD83C\uDF34", emoji.statusEmoji())
        }

    @Test
    fun blossomUploadAuthorization() =
        runTest {
            val hash = "ab".repeat(32)
            val auth = parse<BlossomAuthorizationEvent>(BlossomAuthorizationEvent.createUploadAuth(hash, 1024, "Upload", alice, listOf("https://CDN.example.com:443/x"), expiration = 1_800_000_000))
            assertEquals(
                listOf(listOf("t", "upload"), listOf("expiration", "1800000000"), listOf("size", "1024"), listOf("x", hash), listOf("server", "cdn.example.com")),
                auth.tagList(),
            )
            assertEquals(1024L, auth.size())
            assertEquals("Upload", auth.content)
        }

    @Test
    fun giftWrapsCarryTheRecipientAndOpenForThem() =
        runTest {
            val rumor = note()
            val wrap = parse<GiftWrapEvent>(GiftWrapEvent.create(rumor, bob.pubKey, recipientRelayHint = relay))
            assertEquals(listOf(listOf("p", bob.pubKey, relay.url)), wrap.tagList())
            assertNotEquals(alice.pubKey, wrap.pubKey)
            assertEquals(rumor.id, wrap.unwrapOrNull(bob)?.id)

            val expiring = parse<GiftWrapEvent>(GiftWrapEvent.create(rumor, bob.pubKey, expirationDelta = 60, createdAt = 1_700_000_000))
            assertEquals(listOf("expiration", (1_700_000_000L + 60 + 2 * 24 * 60 * 60).toString()), expiring.tags[1].toList())

            val ephemeral = parse<EphemeralGiftWrapEvent>(EphemeralGiftWrapEvent.create(rumor, bob.pubKey))
            assertEquals(listOf(listOf("p", bob.pubKey)), ephemeral.tagList())
            assertEquals(rumor.id, ephemeral.unwrapOrNull(bob)?.id)
        }

    @Test
    fun sealOpensForItsRecipient() =
        runTest {
            val rumor = note()
            val seal = parse<SealEvent>(SealEvent.create(Rumor.create(rumor), bob.pubKey, alice))
            assertEquals(emptyList(), seal.tagList())
            assertEquals(alice.pubKey, seal.pubKey)
            assertEquals(rumor.id, seal.unsealOrNull(bob)?.id)
        }

    @Test
    fun nostrConnectEnvelope() =
        runTest {
            val ping = BunkerRequestPing()
            val event = parse<NostrConnectEvent>(NostrConnectEvent.create(ping, bob.pubKey, alice))
            assertEquals(listOf(listOf("p", bob.pubKey)), event.tagList())
            assertEquals(ping.id, assertIs<BunkerRequestPing>(event.decryptMessage(bob)).id)
        }

    @Test
    fun walletConnectRequestResponseAndNotification() =
        runTest {
            val request = parse<NwcRequestEvent>(NwcRequestEvent.createRequest(PayInvoiceMethod.create("lnbc1"), bob.pubKey, alice, useNip44 = true))
            assertEquals(listOf(listOf("p", bob.pubKey), listOf("encryption", "nip44_v2")), request.tagList())
            assertIs<PayInvoiceMethod>(request.decryptRequest(bob))

            val nip04 = parse<NwcRequestEvent>(NwcRequestEvent.createRequest(PayInvoiceMethod.create("lnbc1"), bob.pubKey, alice))
            assertEquals(listOf(listOf("p", bob.pubKey)), nip04.tagList())

            val response = parse<NwcResponseEvent>(NwcResponseEvent.createResponse(PayInvoiceSuccessResponse(), request, bob, useNip44 = true))
            assertEquals(listOf(listOf("p", alice.pubKey), listOf("e", request.id)), response.tagList())
            assertIs<PayInvoiceSuccessResponse>(response.decrypt(alice))

            val notification = parse<NwcNotificationEvent>(NwcNotificationEvent.createNotification(PaymentReceivedNotification(), alice.pubKey, bob))
            assertEquals(listOf(listOf("p", alice.pubKey)), notification.tagList())
            assertIs<PaymentReceivedNotification>(notification.decryptNotification(alice))
        }

    @Test
    fun privateZapKeepsTheRequestTagsInOrder() =
        runTest {
            val tags = arrayOf(arrayOf("p", bob.pubKey), arrayOf("relays", relay.url), arrayOf("p", alice.pubKey))
            val copy = parse<PrivateZapEvent>(PrivateZapEvent.create(alice, tags, "secret"))
            assertEquals(tags.map { it.toList() }, copy.tagList())
            assertEquals("secret", copy.content)
        }

    @Test
    fun zapRequests() =
        runTest {
            val target = note()
            val public =
                parse<ZapRequestEvent>(
                    ZapRequestEvent.create(target, setOf(relay), alice, pollOption = 1, message = "zap", zapType = ZapReceiptEvent.ZapType.PUBLIC, toUserPubHex = null, amountMillisats = 21000, lnurl = "lnurl1x"),
                )
            assertEquals(
                listOf(
                    listOf("e", target.id),
                    listOf("p", target.pubKey),
                    listOf("relays", relay.url),
                    listOf("k", "1"),
                    listOf("poll_option", "1"),
                    listOf("amount", "21000"),
                    listOf("lnurl", "lnurl1x"),
                ),
                public.tagList(),
            )
            assertEquals(alice.pubKey, public.pubKey)

            val anonymous = parse<ZapRequestEvent>(ZapRequestEvent.create(bob.pubKey, setOf(relay), alice, "", ZapReceiptEvent.ZapType.ANONYMOUS))
            assertEquals(listOf(listOf("p", bob.pubKey), listOf("relays", relay.url), listOf("anon")), anonymous.tagList())
            assertNotEquals(alice.pubKey, anonymous.pubKey)

            val private = parse<ZapRequestEvent>(ZapRequestEvent.create(bob.pubKey, setOf(relay), alice, "", ZapReceiptEvent.ZapType.PRIVATE))
            // build() writes ["anon", ""]; signing a private zap request fills it with the encrypted copy.
            assertEquals("anon", private.tags.last()[0])
            assertTrue(private.isPrivateZap())
        }
}
