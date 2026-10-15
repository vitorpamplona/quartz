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
package com.vitorpamplona.quartz.payments

import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcInfoEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcNotificationEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcRequestEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcResponseEvent
import com.vitorpamplona.quartz.nip60Cashu.history.CashuSpendingHistoryEvent
import com.vitorpamplona.quartz.nip60Cashu.quote.CashuMintQuoteEvent
import com.vitorpamplona.quartz.nip60Cashu.token.CashuTokenEvent
import com.vitorpamplona.quartz.nip60Cashu.wallet.CashuWalletEvent
import com.vitorpamplona.quartz.nip61Nutzaps.info.NutzapInfoEvent
import com.vitorpamplona.quartz.nip61Nutzaps.nutzap.NutzapEvent
import com.vitorpamplona.quartz.nip87Ecash.cashu.CashuMintEvent
import com.vitorpamplona.quartz.nip87Ecash.fedimint.FedimintEvent
import com.vitorpamplona.quartz.nip87Ecash.recommendation.MintRecommendationEvent
import com.vitorpamplona.quartz.nip87Ecash.recommendation.UnrecognizedKind38000Event
import com.vitorpamplona.quartz.nipA3PaymentTargets.PaymentTargetsEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.intent.Bolt12ZapIntentEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.offer.Bolt12OfferListEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.zap.Bolt12ZapEvent
import com.vitorpamplona.quartz.nipBCOnchainZaps.zap.OnchainZapEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-payments` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object PaymentEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(CashuMintEvent.KIND, ::CashuMintEvent)
        registry.register(CashuMintQuoteEvent.KIND, ::CashuMintQuoteEvent)
        registry.register(CashuTokenEvent.KIND, ::CashuTokenEvent)
        registry.register(CashuSpendingHistoryEvent.KIND, ::CashuSpendingHistoryEvent)
        registry.register(CashuWalletEvent.KIND, ::CashuWalletEvent)
        registry.register(FedimintEvent.KIND, ::FedimintEvent)
        registry.register(NwcRequestEvent.KIND, ::NwcRequestEvent)
        registry.register(NwcResponseEvent.KIND, ::NwcResponseEvent)
        registry.register(NwcInfoEvent.KIND, ::NwcInfoEvent)
        registry.register(NwcNotificationEvent.KIND, ::NwcNotificationEvent)
        registry.register(NwcNotificationEvent.LEGACY_KIND, ::NwcNotificationEvent)
        registry.register(NutzapEvent.KIND, ::NutzapEvent)
        registry.register(NutzapInfoEvent.KIND, ::NutzapInfoEvent)
        registry.register(OnchainZapEvent.KIND, ::OnchainZapEvent)
        registry.register(Bolt12ZapEvent.KIND, ::Bolt12ZapEvent)
        registry.register(Bolt12ZapIntentEvent.KIND, ::Bolt12ZapIntentEvent)
        registry.register(Bolt12OfferListEvent.KIND, ::Bolt12OfferListEvent)
        registry.register(PaymentTargetsEvent.KIND, ::PaymentTargetsEvent)

        // kind:38000 is NIP-87's mint recommendation, but unrelated apps reuse the number:
        // BAO Markets publishes prediction markets on it, an auditable-voting app publishes
        // ballots (both claimed in ExperimentalEventKinds), and most of what a big relay holds
        // for it is spam (`d`-only "sybil test votes"). A recommendation has a `k` (the
        // recommended mint's kind) or, on old events, a `u` (mint URL) or the mint's `a`.
        // Everything else falls to UnrecognizedKind38000Event: still addressable, so stores
        // replace and delete it by `d`, but never indexed and drawn as no card.
        registry.variant(MintRecommendationEvent.KIND, priority = 0, matches = MintRecommendationEvent::isMintRecommendation, builder = ::MintRecommendationEvent)
        registry.register(MintRecommendationEvent.KIND, ::UnrecognizedKind38000Event)
        registry.probeAs(MintRecommendationEvent.KIND, ::MintRecommendationEvent)
    }
}
