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
package com.vitorpamplona.quartz.marketplace

import com.vitorpamplona.quartz.nip15Marketplace.auction.AuctionEvent
import com.vitorpamplona.quartz.nip15Marketplace.bid.BidEvent
import com.vitorpamplona.quartz.nip15Marketplace.bidConfirmation.BidConfirmationEvent
import com.vitorpamplona.quartz.nip15Marketplace.marketplace.MarketplaceEvent
import com.vitorpamplona.quartz.nip15Marketplace.product.ProductEvent
import com.vitorpamplona.quartz.nip15Marketplace.stall.StallEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.P2POrderEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDevFee.MostroDevFeePaymentEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDispute.MostroDisputeEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDispute.UnrecognizedKind38386Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroInfo.MostroInfoEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroInfo.UnrecognizedKind38385Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroRating.MostroUserRatingEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroRating.UnrecognizedKind38384Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.robosatsRating.RoboSatsCoordinatorRatingEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.robosatsRating.UnrecognizedKind31986Event
import com.vitorpamplona.quartz.nip99Classifieds.ClassifiedsEvent
import com.vitorpamplona.quartz.nipCCGeocaching.curation.GeocacheCurationListEvent
import com.vitorpamplona.quartz.nipCCGeocaching.foundLog.GeocacheFoundLogEvent
import com.vitorpamplona.quartz.nipCCGeocaching.listing.GeocacheListingEvent
import com.vitorpamplona.quartz.nipCCGeocaching.verification.GeocacheVerificationEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-marketplace` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object MarketplaceEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(AuctionEvent.KIND, ::AuctionEvent)
        registry.register(BidEvent.KIND, ::BidEvent)
        registry.register(BidConfirmationEvent.KIND, ::BidConfirmationEvent)
        registry.register(ClassifiedsEvent.KIND, ::ClassifiedsEvent)
        registry.register(GeocacheListingEvent.KIND, ::GeocacheListingEvent)
        registry.register(GeocacheFoundLogEvent.KIND, ::GeocacheFoundLogEvent)
        registry.register(GeocacheVerificationEvent.KIND, ::GeocacheVerificationEvent)
        registry.register(GeocacheCurationListEvent.KIND, ::GeocacheCurationListEvent)
        registry.register(MarketplaceEvent.KIND, ::MarketplaceEvent)
        registry.register(P2POrderEvent.KIND, ::P2POrderEvent)
        registry.register(MostroDevFeePaymentEvent.KIND, ::MostroDevFeePaymentEvent)
        registry.register(ProductEvent.KIND, ::ProductEvent)
        registry.register(StallEvent.KIND, ::StallEvent)

        // Mostro split its kind 38383 into 38384 ratings / 38385 info / 38386 disputes, but
        // Paygress (a compute marketplace) publishes heartbeats, lease revocations and promotion
        // announcements on the same three numbers, and other apps add their own. Each is told
        // apart by its Mostro `z` tag; the rest stays addressable but unread. RoboSats
        // coordinator ratings carry a coordinator-signed token; Borkstr's NIP compatibility
        // reports share 31986 without one.
        registry.variant(MostroUserRatingEvent.KIND, matches = MostroUserRatingEvent::isMostroRating, builder = ::MostroUserRatingEvent)
        registry.register(MostroUserRatingEvent.KIND, ::UnrecognizedKind38384Event)
        registry.probeAs(MostroUserRatingEvent.KIND, ::MostroUserRatingEvent)
        registry.variant(MostroInfoEvent.KIND, matches = MostroInfoEvent::isMostroInfo, builder = ::MostroInfoEvent)
        registry.register(MostroInfoEvent.KIND, ::UnrecognizedKind38385Event)
        registry.probeAs(MostroInfoEvent.KIND, ::MostroInfoEvent)
        registry.variant(MostroDisputeEvent.KIND, matches = MostroDisputeEvent::isMostroDispute, builder = ::MostroDisputeEvent)
        registry.register(MostroDisputeEvent.KIND, ::UnrecognizedKind38386Event)
        registry.probeAs(MostroDisputeEvent.KIND, ::MostroDisputeEvent)
        registry.variant(RoboSatsCoordinatorRatingEvent.KIND, matches = RoboSatsCoordinatorRatingEvent::isCoordinatorRating, builder = ::RoboSatsCoordinatorRatingEvent)
        registry.register(RoboSatsCoordinatorRatingEvent.KIND, ::UnrecognizedKind31986Event)
        registry.probeAs(RoboSatsCoordinatorRatingEvent.KIND, ::RoboSatsCoordinatorRatingEvent)
    }
}
