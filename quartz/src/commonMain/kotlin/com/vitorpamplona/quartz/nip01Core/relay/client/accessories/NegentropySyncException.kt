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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.relay.client.single.RelayDisconnect
import com.vitorpamplona.quartz.nip01Core.relay.client.single.SentFrames
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl

/**
 * Thrown by [negentropySync] when a relay's matched set cannot be reconciled
 * through NIP-77 for a given [window].
 *
 * The accessory does NOT silently fall back to plain paging — that is a heavier,
 * non-delta transport and the choice belongs to the caller. Catch this and decide:
 * page the filter yourself with [fetchAllPages], try another relay, narrow the
 * filter, or give up. For the common "try negentropy, else page" shape use
 * [negentropySyncOrFetch], which does exactly that (with id-dedup) for you.
 *
 * [window] is the specific `created_at` slice that failed. When negentropy fails
 * on the very first reconcile (e.g. the relay does not speak NIP-77) it equals the
 * filter you passed; after windowing it is a sub-range. Note that events from
 * windows that DID reconcile before this failure may already have been delivered
 * to your `onEvent`, so dedupe by event id if you then page the whole filter.
 *
 * @property relay  the relay that could not reconcile.
 * @property window the filter slice that failed.
 * @property reason machine-readable category — branch on this to recover.
 * @property detail the underlying specifics (a relay's `NEG-ERR` text, `timeout`, …).
 * @property cap    the relay's own `max_sync_events` when its refusal stated one
 *   (see [com.vitorpamplona.quartz.nip77Negentropy.NegErrMessage.statedCap]).
 *   Worth persisting per relay: it is what sizes the first window next time.
 * @property failure why an [Reason.UNAVAILABLE] sync stopped, typed; null for
 *   [Reason.OVER_MAX_SYNC_EVENTS]. Branch on this rather than on [detail]'s wording.
 * @property disconnect how the socket ended, when [failure] is [Failure.DISCONNECTED], or the
 *   failed dial behind a [Failure.CONNECT_TIMEOUT]: the relay's close code, our own close, or the
 *   transport error, with the frames sent on that socket.
 * @property rounds `NEG-MSG` rounds the failed window completed before it stopped, when known.
 * @property sent   the frames the socket had sent: the ended socket's for a disconnect, else the
 *   live one's, so a relay that goes silent or refuses after a large frame shows the size too.
 */
class NegentropySyncException(
    val relay: NormalizedRelayUrl,
    val window: Filter,
    val reason: Reason,
    val detail: String,
    val cap: Long? = null,
    val failure: Failure? = null,
    val disconnect: RelayDisconnect? = null,
    val rounds: Int? = null,
    val sent: SentFrames? = null,
) : Exception("NIP-77 sync of $relay failed ($reason): $detail") {
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    constructor(
        relay: NormalizedRelayUrl,
        window: Filter,
        reason: Reason,
        detail: String,
        cap: Long? = null,
    ) : this(relay, window, reason, detail, cap, null, null, null, null)

    /** Why an [Reason.UNAVAILABLE] sync stopped. */
    enum class Failure {
        /** The socket ended mid-reconcile; [disconnect] says how. */
        DISCONNECTED,

        /** No connection came up within the connect bound; [disconnect] is the failed dial, if one ended. */
        CONNECT_TIMEOUT,

        /** The relay sent nothing for the idle window, mid-reconcile. */
        SILENT,

        /** The relay refused in its own words (a `NEG-ERR`, a `CLOSED`, a `NOTICE` answering `NEG-OPEN`); [detail] quotes it. */
        REFUSED,

        /** The window split kept growing without converging, or the relay hands out a sliver per pass. */
        NOT_CONVERGING,

        /** The relay named ids and then would not serve them. */
        DOWNLOADS_FAILED,
    }

    enum class Reason {
        /**
         * The relay caps negentropy below the matched set and even a minimal
         * `created_at` window still exceeds that cap (strfry's `max_sync_events`),
         * so negentropy cannot enumerate the window at all. Paging is the only way
         * to get these events.
         */
        OVER_MAX_SYNC_EVENTS,

        /**
         * The relay did not complete reconciliation: no NIP-77 support, a
         * non-overflow `NEG-ERR`, a disconnect, or a timeout. [detail] carries the
         * specifics.
         */
        UNAVAILABLE,
    }
}
