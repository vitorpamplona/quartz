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
package com.vitorpamplona.quartz.nip01Core.relay.client.counts

import com.vitorpamplona.quartz.nip01Core.relay.client.pool.FiltersChanged
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock

/**
 * Where one COUNT query stands on each relay.
 *
 * Written from each relay's reader thread (its COUNT and CLOSED) and from the sender (the app
 * thread, or a reader re-sending after an answer), so every access takes [lock]: the plain maps
 * this kept could be corrupted by two writers at once, like the ones PoolRequests replaced.
 */
class CountQueryState<T> {
    // Logs the state of each channel to:
    // 1. inform when an event is received as live
    // 2. to block COUNTs being sent before finished (receiving an COUNT or Closed)
    //
    // If 2 happens, the relay might send multiple COUNTs in sequence
    // for the same sub and we won't know which COUNT was it for.
    private val lock = PlatformLock()
    private val queryStates = mutableMapOf<T, CountQueryStatus>()
    private val filterStates = mutableMapOf<T, List<Filter>>()

    /** A copy of the filters last sent, per relay. */
    fun currentFilters(): Map<T, List<Filter>> = lock.withLock { filterStates.toMap() }

    fun currentFilters(reference: T) = lock.withLock { filterStates[reference] }

    fun currentState(reference: T) = lock.withLock { queryStates[reference] }

    fun onCountReply(reference: T) {
        lock.withLock { queryStates.put(reference, CountQueryStatus.RECEIVED) }
    }

    fun onClosed(reference: T) {
        lock.withLock { queryStates.put(reference, CountQueryStatus.CLOSED) }
    }

    /**
     * Marks a COUNT for [filters] as in flight. The send paths call it before the frame leaves
     * (see [decide]); called again when the send is reported, it only records the filters if an
     * answer already landed, so a relay that answers before the report does not leave the query
     * looking in flight forever.
     */
    fun onQuery(
        reference: T,
        filters: List<Filter>,
    ) {
        lock.withLock {
            if (filterStates[reference] !== filters || queryStates[reference] == null) {
                queryStates.put(reference, CountQueryStatus.SENT)
            }
            filterStates.put(reference, filters)
        }
    }

    fun onCloseQuery(reference: T) {
        lock.withLock { queryStates.put(reference, CountQueryStatus.CLOSED) }
    }

    fun connecting(reference: T) {
        lock.withLock {
            queryStates.remove(reference)
            filterStates.remove(reference)
        }
    }

    fun disconnected(reference: T) {
        lock.withLock { queryStates.remove(reference) }
    }

    /**
     * The command that brings [reference] in line with [newFilters], decided and, for a COUNT,
     * marked in flight in one step, so two threads never both send one. Null for nothing to send:
     * the filters did not change, or a COUNT is still in flight and [skipIfInFlight] is set.
     */
    fun decide(
        queryId: String,
        reference: T,
        newFilters: List<Filter>?,
        skipIfInFlight: Boolean,
    ): Command? =
        lock.withLock {
            if (skipIfInFlight && queryStates[reference] == CountQueryStatus.SENT) return@withLock null
            val oldFilters = filterStates[reference]
            when {
                // some relays are not in this sub anymore. Stop their subscriptions
                // only if the old filters are not already closed.
                newFilters.isNullOrEmpty() -> if (!oldFilters.isNullOrEmpty()) CloseCmd(queryId) else null
                // new relays, or filters changed enough (not only an update in since) to warn a new count
                oldFilters.isNullOrEmpty() || FiltersChanged.needsToResendRequest(oldFilters, newFilters) -> {
                    queryStates.put(reference, CountQueryStatus.SENT)
                    filterStates.put(reference, newFilters)
                    CountCmd(queryId, newFilters)
                }
                else -> null
            }
        }

    /** Marks [filters] in flight for a send that goes regardless of what was sent before (a resync). */
    fun markSending(
        reference: T,
        filters: List<Filter>,
    ) {
        lock.withLock {
            queryStates.put(reference, CountQueryStatus.SENT)
            filterStates.put(reference, filters)
        }
    }

    /** Whether [reference] has no COUNT on this connection, or the relay CLOSED it. */
    fun isUnansweredOrRefused(reference: T): Boolean =
        lock.withLock {
            val status = queryStates[reference]
            status == null || status == CountQueryStatus.CLOSED
        }
}
