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

import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import kotlinx.coroutines.CancellationException

/**
 * The caller's own matching set, read one `created_at` window at a time.
 *
 * The list overloads of [negentropySync] / [negentropyReconcile] need every
 * matching `(created_at, id)` pair before the first NEG-OPEN goes out, which
 * makes peak memory a property of the corpus: a multi-million-event filter is
 * a multi-million-entry list held for the whole sync, whether or not the sync
 * ends up splitting into windows that each touch a fraction of it.
 *
 * A caller whose store can answer by range doesn't need that. Passing an index
 * instead lets the window engine ask for a window's worth at a time, so the
 * high-water mark becomes the size of one window — which the engine also sizes,
 * from [count], before spending a round trip on it.
 *
 * Both methods are called on the reconciler coroutines, possibly concurrently
 * when `reconcileConcurrency > 1`, and possibly more than once for the same
 * window (a window that overflows is re-asked as halves). Implementations
 * should be cheap and side-effect free; a store-backed one usually is, since
 * these are index scans.
 */
interface NegentropyLocalIndex {
    /**
     * How many local events fall inside [window], or null when the store
     * cannot answer cheaply.
     *
     * This is what lets the engine split a window BEFORE asking the relay for
     * it — the only signal available about our own side, and the one that
     * bounds what [entriesFor] will have to materialise. Null disables that
     * pre-split for the window; the relay's own refusal is then the only thing
     * that shrinks it, exactly as before this method existed.
     *
     * The engine reads it only to decide whether to split, so a decorator may answer more than
     * [entriesFor] holds: [withRelayCount] answers the larger of this side's and the relay's.
     */
    suspend fun count(window: Filter): Int?

    /** The `(created_at, id)` pairs inside [window]. Order does not matter. */
    suspend fun entriesFor(window: Filter): List<IdAndTime>

    companion object {
        /** Nothing held locally: the sync downloads the relay's whole matched set. */
        val Empty: NegentropyLocalIndex =
            object : NegentropyLocalIndex {
                override suspend fun count(window: Filter) = 0

                override suspend fun entriesFor(window: Filter) = emptyList<IdAndTime>()
            }

        /**
         * An index over [store], each window read from its `created_at` index: [count] is
         * [IEventStore.count] and [entriesFor] is [IEventStore.snapshotIdsForNegentropy], so
         * memory is one window's ids at a time. What `NegentropyStoreSync` uses for a windowed
         * sync, and what a caller handing an index to `negentropySync` should pass for a store.
         *
         * The windows are the caller's own filter with `since`/`until` narrowed, so they go to
         * the store as they are. A count the store fails to give is null rather than an
         * exception (the engine then stops pre-splitting that window and lets the relay's
         * refusal decide, as without an index); a [CancellationException] still propagates.
         * Metering or logging goes in a decorator around the returned index.
         */
        fun of(store: IEventStore): NegentropyLocalIndex = StoreIndex(store)

        /**
         * An index over a list already in memory — what the list overloads use,
         * so they behave exactly as they did: sorted once, then binary-searched
         * per window.
         */
        fun of(entries: List<IdAndTime>): NegentropyLocalIndex = if (entries.isEmpty()) Empty else SortedListIndex(entries.sortedBy { it.createdAt })
    }
}

/**
 * This index, with each window's [NegentropyLocalIndex.count] raised to [relay]'s NIP-45 `COUNT`
 * for the same window when that is larger. [NegentropyLocalIndex.entriesFor] is this index's.
 *
 * The engine splits a window before asking the relay for it when the count is over
 * `targetWindow`, and the count is otherwise this side's alone. A side that is empty or small (a
 * first sync into a fresh store, a mirror rebuilt from nothing) then sends the relay its whole
 * matched set as one `NEG-OPEN`, however large, and the relay builds one negentropy state over
 * all of it. With this, a window that is over the target on either side is split.
 *
 * One `COUNT` per window the engine counts, which it does only with a `targetWindow`. A relay that
 * doesn't answer one (no NIP-45, a refusal, [idleTimeoutMs] of silence) leaves this side's count,
 * as without the decorator. An approximate count serves as well: it only decides a split.
 */
fun NegentropyLocalIndex.withRelayCount(
    client: INostrClient,
    relay: NormalizedRelayUrl,
    idleTimeoutMs: Long = 15_000,
): NegentropyLocalIndex = RelayCountedIndex(this, client, relay, idleTimeoutMs)

private class RelayCountedIndex(
    private val local: NegentropyLocalIndex,
    private val client: INostrClient,
    private val relay: NormalizedRelayUrl,
    private val idleTimeoutMs: Long,
) : NegentropyLocalIndex {
    override suspend fun count(window: Filter): Int? {
        val mine = local.count(window)
        val theirs = client.count(relay, window, idleTimeoutMs)?.count
        return when {
            mine == null -> theirs
            theirs == null -> mine
            else -> maxOf(mine, theirs)
        }
    }

    override suspend fun entriesFor(window: Filter): List<IdAndTime> = local.entriesFor(window)
}

private class SortedListIndex(
    private val sorted: List<IdAndTime>,
) : NegentropyLocalIndex {
    override suspend fun count(window: Filter): Int = slice(window).size

    override suspend fun entriesFor(window: Filter): List<IdAndTime> = slice(window)

    /**
     * The `createdAt`-range slice of [sorted] (ascending by `createdAt`) that
     * belongs to `[since, until]` (both inclusive, NIP-01 semantics).
     * Binary-searched so window splits stay O(log n) over multi-million sets.
     */
    private fun slice(window: Filter): List<IdAndTime> {
        val since = window.since
        val until = window.until
        if (sorted.isEmpty() || (since == null && until == null)) return sorted

        val lo = since ?: 0L
        val hi = until ?: Long.MAX_VALUE

        // first index with createdAt >= lo
        var start = 0
        var e = sorted.size
        while (start < e) {
            val mid = (start + e) ushr 1
            if (sorted[mid].createdAt < lo) start = mid + 1 else e = mid
        }

        // first index with createdAt > hi
        var end = start
        e = sorted.size
        while (end < e) {
            val mid = (end + e) ushr 1
            if (sorted[mid].createdAt <= hi) end = mid + 1 else e = mid
        }

        return if (start >= end) emptyList() else sorted.subList(start, end)
    }
}

/** [NegentropyLocalIndex.of] over an [IEventStore]. */
private class StoreIndex(
    private val store: IEventStore,
) : NegentropyLocalIndex {
    override suspend fun count(window: Filter): Int? =
        try {
            store.count(window)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    override suspend fun entriesFor(window: Filter): List<IdAndTime> = store.snapshotIdsForNegentropy(listOf(window))
}
