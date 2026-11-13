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
package com.vitorpamplona.quartz.routstr.node

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Finding Routstr nodes: the relays Routstr's docs name, and the filter for their announcements.
 *
 * Ask [DEFAULT_RELAYS] with [listedFilter] (nothing older than [RoutstrNodeEvent.LISTED_MAX_AGE],
 * a month), keep the newest [RoutstrNodeEvent] per address, list the ones seen this week
 * ([RoutstrNodeEvent.isFresh]) and put the rest behind a "not seen this week" control, and call
 * only [RoutstrNodeEvent.usableEndpoints], refusing private addresses when they resolve (see
 * [RoutstrEndpoint]).
 */
object RoutstrDiscovery {
    /** `wss://relay.routstr.com`, `wss://relay.damus.io` and `wss://nos.lol`, from Routstr's docs. */
    val DEFAULT_RELAYS: List<NormalizedRelayUrl> =
        listOf(
            "wss://relay.routstr.com",
            "wss://relay.damus.io",
            "wss://nos.lol",
        ).map(RelayUrlNormalizer::normalize)

    /**
     * Node announcements (kind 38421), optionally only those published at or after [since]
     * (Unix seconds). [listedFilter] is the one a node list asks for.
     */
    fun filter(
        since: Long? = null,
        limit: Int? = null,
    ) = Filter(kinds = listOf(RoutstrNodeEvent.KIND), since = since, limit = limit)

    /**
     * The announcements a node list can show at [now]: since `now -`
     * [RoutstrNodeEvent.LISTED_MAX_AGE], so the relay skips what is too old to list, but not the
     * nodes that weren't seen this week ([RoutstrNodeEvent.MAX_AGE]), which still answer.
     */
    fun listedFilter(
        now: Long = TimeUtils.now(),
        limit: Int? = null,
    ) = filter(since = now - RoutstrNodeEvent.LISTED_MAX_AGE, limit = limit)
}
