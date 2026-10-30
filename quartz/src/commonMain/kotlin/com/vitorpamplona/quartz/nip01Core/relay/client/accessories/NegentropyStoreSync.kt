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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip01Core.store.verifyAndInsert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Two-pass NIP-77 sync of ANY filter set between a relay and a local [IEventStore],
 * with a paged fallback — the reusable engine behind `amy sync` and the GrapeRank
 * outbox updater, generalized so any caller can reconcile arbitrary
 * `relay -> filters` work against their store.
 *
 * A **group** is one `(relay, filter)`: [syncGroup] runs the full two-pass sync for it.
 *
 *  1. **Content pass** — [negentropyReconcile] diffs the relay's matched set for the
 *     filter against the store's ids, then:
 *      - [Config.down] downloads the residual **needs** (relay has, store lacks) by id
 *        and verifies+inserts them into [store];
 *      - [Config.up] uploads the residual **haves** (store has, relay lacks) as EVENTs.
 *  2. **Deletion pass** — [negentropySettleDeletions] over what the content pass could
 *     not converge ([Config.syncDeletions]). Its **applyDown** direction is the
 *     "download the deletion when our upload was rejected" case: an event pushed up
 *     that the relay keeps rejecting (it deleted it) is a residual *have*, so the
 *     relay's covering kind:5 is pulled down and applied and [store] drops the
 *     retracted event. **sendUp** publishes the store's covering deletions for records
 *     deleted locally that the relay still serves.
 *
 * If the content pass can't reconcile ([NegentropySyncException] — no NIP-77, an
 * over-cap minimal window, a mid-sync disconnect) and [Config.pageFallback] is on, the
 * group pages the same filter ([fetchAllPages]) into the store instead; only the
 * negentropy-only deletion settle is skipped there. Every group is best-effort — a
 * failure lands in [GroupResult.error], it never throws — so one bad relay can't abort
 * a multi-relay [sync].
 *
 * [sync] runs many groups: relays go up to [Config.concurrency] at once, and a single
 * relay's filters run sequentially (so one relay never opens more than one group's worth
 * of negentropy sessions at a time — keeping under its subscription budget). Progress is
 * emitted through [log].
 */
@OptIn(ExperimentalAtomicApi::class)
class NegentropyStoreSync(
    private val client: INostrClient,
    private val store: IEventStore,
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) {
    /**
     * @param down download records the relay has that the store lacks.
     * @param up upload records the store has that the relay lacks (also arms the
     *   deletion **applyDown** path — a rejected upload pulls the relay's kind:5 down).
     * @param syncDeletions run the deletion settle over the reconcile residual.
     * @param pageFallback page the filter when negentropy can't reconcile the relay.
     * @param idChunk ids per reconcile chunk and per by-id fetch.
     * @param downloadWorkers concurrent by-id download fetches per group.
     * @param reconcileConcurrency overlapped `created_at`-window reconciles after an over-cap split.
     * @param maxDeletionRounds hard cap on deletion-settle rounds (converges in 1–2).
     * @param concurrency relays synced at once by [sync] (a relay's own filters stay sequential).
     * @param idleTimeoutMs idle watchdog for reconciles / fetches / pages.
     * @param publishTimeoutSecs OK-confirmation wait per uploaded event.
     * @param targetWindow events per reconcile window, or `0` to snapshot the
     *   whole filter up front (the default, and what this class always did).
     *
     *   Above zero, the store is read one `created_at` window at a time through
     *   a [NegentropyLocalIndex] instead: the id snapshot stops being O(matched
     *   set) — it is the largest thing this class holds — at the price of an
     *   indexed count + range read per window. Worth turning on exactly when the
     *   filter matches more than fits comfortably in memory; pointless below
     *   that, where one snapshot shared by the whole group is cheaper.
     * @param shareSnapshots in [sync], take ONE id snapshot per distinct filter and hand it to
     *   every relay that syncs that filter, instead of one per `(relay, filter)` group. A mirror
     *   pushing one stream to several upstreams reads its store once per pass rather than once
     *   per upstream; each snapshot is held until [sync] returns, so the memory is one snapshot
     *   per distinct filter for the whole call. Ignored with a [targetWindow] (there is no
     *   snapshot then). A caller sharing snapshots its own way passes one to [syncGroup].
     * @param uploadAfterReconcile collect the haves (ids only) while the NIP-77 session runs and
     *   upload them after it closes. By default uploads run beside the session through a
     *   bounded queue, so a slow upload back-pressures the open session, and a relay that is
     *   slow to take events can time out its own reconcile. Costs the have-set's ids in memory.
     * @param uploadWindow `0` (the default) uploads one event at a time with [publishAndConfirm],
     *   each waiting up to [publishTimeoutSecs] for its OK. Above zero, uploads stream through
     *   [publishStream] with that many awaiting their OK at once: one listener for the batch,
     *   and the [idleTimeoutMs] wait runs once over the relay's silence instead of once per
     *   event. A relay that stops answering stalls the stream once, and the group stops
     *   uploading to it (the rest count in [GroupResult.uploadsLeft]).
     * @param uploadDeadlineMs `0` for none. Above zero, a wall-clock budget for one group's
     *   uploads: past it the group stops publishing (the reconcile still runs to the end) and
     *   counts the haves it did not get to in [GroupResult.uploadsLeft], so a pass against a
     *   relay that never OKs ends instead of stretching to `haves × publishTimeoutSecs`.
     */
    class Config(
        val down: Boolean = true,
        val up: Boolean = false,
        val syncDeletions: Boolean = true,
        val pageFallback: Boolean = true,
        val idChunk: Int = 500,
        val downloadWorkers: Int = 4,
        val reconcileConcurrency: Int = 2,
        val maxDeletionRounds: Int = 4,
        val concurrency: Int = 4,
        val idleTimeoutMs: Long = 30_000L,
        val publishTimeoutSecs: Long = 15,
        val targetWindow: Int = 0,
        val shareSnapshots: Boolean = false,
        val uploadAfterReconcile: Boolean = false,
        val uploadWindow: Int = 0,
        val uploadDeadlineMs: Long = 0,
    ) {
        @Deprecated("Kept for binary compatibility; use the constructor with the upload options.", level = DeprecationLevel.HIDDEN)
        constructor(
            down: Boolean = true,
            up: Boolean = false,
            syncDeletions: Boolean = true,
            pageFallback: Boolean = true,
            idChunk: Int = 500,
            downloadWorkers: Int = 4,
            reconcileConcurrency: Int = 2,
            maxDeletionRounds: Int = 4,
            concurrency: Int = 4,
            idleTimeoutMs: Long = 30_000L,
            publishTimeoutSecs: Long = 15,
            targetWindow: Int = 0,
        ) : this(
            down,
            up,
            syncDeletions,
            pageFallback,
            idChunk,
            downloadWorkers,
            reconcileConcurrency,
            maxDeletionRounds,
            concurrency,
            idleTimeoutMs,
            publishTimeoutSecs,
            targetWindow,
            shareSnapshots = false,
        )
    }

    /** Outcome of one `(relay, filter)` group. `error` is null on success. */
    class GroupResult(
        val relay: NormalizedRelayUrl,
        val filter: Filter,
        val need: Int,
        val have: Int,
        val downloaded: Int,
        val uploaded: Int,
        val deletionsSentUp: Int,
        val deletionsAppliedDown: Int,
        /** True when negentropy couldn't reconcile and the filter was paged instead. */
        val pagedFallback: Boolean,
        val error: String?,
        /**
         * Haves this group did not publish because its [Config.uploadDeadlineMs] ran out or the
         * relay stalled a [Config.uploadWindow] stream: what the next pass still has to send.
         */
        val uploadsLeft: Int = 0,
    ) {
        @Deprecated("Kept for binary compatibility; use the constructor with uploadsLeft.", level = DeprecationLevel.HIDDEN)
        constructor(
            relay: NormalizedRelayUrl,
            filter: Filter,
            need: Int,
            have: Int,
            downloaded: Int,
            uploaded: Int,
            deletionsSentUp: Int,
            deletionsAppliedDown: Int,
            pagedFallback: Boolean,
            error: String?,
        ) : this(relay, filter, need, have, downloaded, uploaded, deletionsSentUp, deletionsAppliedDown, pagedFallback, error, uploadsLeft = 0)
    }

    /**
     * Sync every `(relay, filter)` in [filtersByRelay]: relays run up to
     * [Config.concurrency] at once; each relay's filters run sequentially. Returns one
     * [GroupResult] per relay+filter (relay order preserved, filters in list order).
     */
    suspend fun sync(filtersByRelay: Map<NormalizedRelayUrl, List<Filter>>): List<GroupResult> {
        if (filtersByRelay.isEmpty()) return emptyList()
        val gate = Semaphore(config.concurrency.coerceAtLeast(1))
        val snapshots = if (config.shareSnapshots && config.targetWindow <= 0) SharedSnapshots(store) else null
        return coroutineScope {
            filtersByRelay.entries
                .map { (relay, filters) ->
                    async { gate.withPermit { filters.map { syncGroupSafely(relay, it, snapshots) } } }
                }.awaitAll()
                .flatten()
        }
    }

    /**
     * One id snapshot per distinct filter, taken by the first group that needs it and handed to
     * every other ([Config.shareSnapshots]). Keyed by the filter's JSON: [Filter] has no value
     * equality, and two relays asked for the same filter must land on the same entry.
     */
    private class SharedSnapshots(
        private val store: IEventStore,
    ) {
        private val lock = Mutex()
        private val byFilter = HashMap<String, CompletableDeferred<List<IdAndTime>>>()

        suspend fun of(filter: Filter): List<IdAndTime> {
            val key = filter.toJson()
            var mine = false
            val slot =
                lock.withLock {
                    byFilter.getOrPut(key) { CompletableDeferred<List<IdAndTime>>().also { mine = true } }
                }
            if (mine) {
                try {
                    slot.complete(store.snapshotIdsForNegentropy(listOf(filter)))
                } catch (e: Throwable) {
                    // Let the next group try again rather than inherit this one's failure.
                    lock.withLock { byFilter.remove(key) }
                    slot.completeExceptionally(e)
                    throw e
                }
            }
            return slot.await()
        }
    }

    /**
     * [syncGroup] with a best-effort guard so an unexpected failure in one group
     * (store I/O, a relay throwing outside the NIP-77 path, …) is recorded rather than
     * cancelling every other relay in a [sync]. Cancellation is propagated, not caught.
     */
    private suspend fun syncGroupSafely(
        relay: NormalizedRelayUrl,
        filter: Filter,
        snapshots: SharedSnapshots?,
    ): GroupResult =
        try {
            if (snapshots == null) syncGroup(relay, filter) else syncGroup(relay, filter, snapshots.of(filter))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[store-sync] ${relay.url}: group failed: ${e::class.simpleName}: ${e.message}")
            GroupResult(relay, filter, 0, 0, 0, 0, 0, 0, pagedFallback = false, error = "${e::class.simpleName}: ${e.message}")
        }

    /** Content pass + deletion settle (+ page fallback) for one relay + one filter. */
    suspend fun syncGroup(
        relay: NormalizedRelayUrl,
        filter: Filter,
    ): GroupResult {
        // Only the id+created_at snapshot is needed to reconcile — never the decoded
        // events (~40 B/entry vs ~1 KB), which matters when a relay hosts a large
        // matched set. The events the reconcile decides to UP-publish (the small
        // residual haves) are fetched by id on demand in the uploader below.
        //
        // With a targetWindow, even those 40 B/entry are read per window rather
        // than for the whole filter — on a large store that snapshot is the
        // biggest thing this class allocates, and it is allocated before the
        // first frame goes out.
        val windowed = config.targetWindow > 0
        return runGroup(relay, filter, if (windowed) null else store.snapshotIdsForNegentropy(listOf(filter)))
    }

    /**
     * [syncGroup] against an id snapshot the caller already holds: [localEntries] must be
     * [IEventStore.snapshotIdsForNegentropy] of [filter] on this store, taken once and shared by
     * however many relays the caller syncs it with (a stream pushed to several upstreams). The
     * snapshot is never modified, so one list can serve concurrent groups.
     */
    suspend fun syncGroup(
        relay: NormalizedRelayUrl,
        filter: Filter,
        localEntries: List<IdAndTime>,
    ): GroupResult = runGroup(relay, filter, localEntries)

    private suspend fun runGroup(
        relay: NormalizedRelayUrl,
        filter: Filter,
        snapshot: List<IdAndTime>?,
    ): GroupResult {
        val localIndex = if (snapshot == null) NegentropyLocalIndex.of(store) else null
        val localEntries = snapshot ?: emptyList()

        val downloaded = AtomicInt(0)
        val uploads = Uploads(relay)

        val reconcileResult =
            try {
                coroutineScope {
                    // needIds = relay has, store lacks; haveIds = store has, relay lacks.
                    // Both are bounded so a slow consumer back-pressures the reconcile
                    // instead of buffering the whole residual set. haveBatches drains
                    // through the single, network-bound uploader (publish + OK wait) far
                    // slower than the reconcile produces have-ids, so leaving it UNLIMITED
                    // let a first push of a large local store queue O(local-set) ids.
                    val needBatches = Channel<List<HexKey>>(config.downloadWorkers * 2)
                    val haveBatches = Channel<List<HexKey>>((config.downloadWorkers * 2).coerceAtLeast(2))

                    val downloaders =
                        List(config.downloadWorkers.coerceAtLeast(1)) {
                            launch {
                                for (batch in needBatches) {
                                    for (event in client.fetchAll(relay, Filter(ids = batch), config.idleTimeoutMs)) {
                                        if (store.verifyAndInsert(event)) downloaded.addAndFetch(1)
                                    }
                                }
                            }
                        }
                    // With uploadAfterReconcile the haves only collect here (ids, never events)
                    // and go up once the session has closed; otherwise this uploader drains them
                    // beside the session.
                    val deferredHaves = if (config.uploadAfterReconcile) ArrayList<HexKey>() else null
                    val uploader =
                        launch {
                            for (batch in haveBatches) {
                                if (deferredHaves != null) deferredHaves.addAll(batch) else uploads.send(batch)
                            }
                        }

                    val result =
                        try {
                            client.negentropyReconcile(
                                relay = relay,
                                filter = filter,
                                localEntries = localEntries,
                                localIndex = localIndex,
                                targetWindow = config.targetWindow,
                                batchSize = config.idChunk,
                                idleTimeoutMs = config.idleTimeoutMs,
                                reconcileConcurrency = config.reconcileConcurrency,
                                onHaveIds = if (config.up) { batch -> haveBatches.send(batch) } else null,
                                onNeedIds = { batch -> if (config.down) needBatches.send(batch) },
                            )
                        } finally {
                            needBatches.close()
                            haveBatches.close()
                        }

                    downloaders.joinAll()
                    uploader.join()
                    deferredHaves?.let { haves -> haves.chunked(config.idChunk.coerceAtLeast(1)).forEach { uploads.send(it) } }
                    result
                }
            } catch (e: NegentropySyncException) {
                // Negentropy couldn't reconcile — page the same filter so the records
                // still refresh. Deletion settle is negentropy-only, so it is skipped.
                var pageError: String? = e.message ?: "negentropy sync failed"
                if (config.pageFallback && config.down) {
                    pageError =
                        try {
                            downloaded.addAndFetch(pageDownload(relay, filter))
                            null
                        } catch (pe: CancellationException) {
                            throw pe
                        } catch (pe: Exception) {
                            "negentropy: ${e.message}; page fallback: ${pe::class.simpleName}: ${pe.message}"
                        }
                }
                log("[store-sync] ${relay.url}: paged fallback, ${downloaded.load()} stored${pageError?.let { " (error: $it)" } ?: ""}")
                return GroupResult(relay, filter, 0, 0, downloaded.load(), uploads.uploaded, 0, 0, pagedFallback = true, error = pageError, uploadsLeft = uploads.left)
            }

        val deletions =
            if (config.syncDeletions) {
                client.negentropySettleDeletions(
                    relay = relay,
                    filter = filter,
                    store = store,
                    sendUp = config.down,
                    applyDown = config.up,
                    batchSize = config.idChunk,
                    idleTimeoutMs = config.idleTimeoutMs,
                    maxRounds = config.maxDeletionRounds,
                    reconcileConcurrency = config.reconcileConcurrency,
                )
            } else {
                null
            }

        log(
            "[store-sync] ${relay.url}: down ${downloaded.load()}, up ${uploads.uploaded}" +
                (if (uploads.left > 0) " (${uploads.left} left)" else "") + ", " +
                "del↑ ${deletions?.sentUp ?: 0}, del↓ ${deletions?.appliedDown ?: 0}",
        )
        return GroupResult(
            relay = relay,
            filter = filter,
            need = reconcileResult.needCount,
            have = reconcileResult.haveCount,
            downloaded = downloaded.load(),
            uploaded = uploads.uploaded,
            deletionsSentUp = deletions?.sentUp ?: 0,
            deletionsAppliedDown = deletions?.appliedDown ?: 0,
            pagedFallback = false,
            error = null,
            uploadsLeft = uploads.left,
        )
    }

    /**
     * One group's uploads to [relay]: fetches each batch of have-ids from the store and publishes
     * it, one confirmed event at a time ([Config.uploadWindow] `0`) or as a windowed stream, until
     * [Config.uploadDeadlineMs] runs out or the relay stalls a stream. After that every batch is
     * only counted ([left]), never sent, so the reconcile feeding it still drains. Used by one
     * coroutine at a time (the uploader, then the deferred flush after it has joined).
     */
    private inner class Uploads(
        private val relay: NormalizedRelayUrl,
    ) {
        private val deadline: TimeMark? = if (config.uploadDeadlineMs > 0) TimeSource.Monotonic.markNow() + config.uploadDeadlineMs.milliseconds else null
        private var stopped = false
        private val accepted = AtomicInt(0)
        private val skipped = AtomicInt(0)

        val uploaded: Int get() = accepted.load()
        val left: Int get() = skipped.load()

        private fun outOfTime() = stopped || deadline?.hasPassedNow() == true

        suspend fun send(ids: List<HexKey>) {
            if (outOfTime()) {
                skipped.addAndFetch(ids.size)
                return
            }
            // Fetch just the residual haves from the store (not the whole matched set).
            val events = store.query<Event>(Filter(ids = ids))
            if (config.uploadWindow <= 0) {
                for ((i, event) in events.withIndex()) {
                    if (outOfTime()) {
                        skipped.addAndFetch(events.size - i)
                        return
                    }
                    if (client.publishAndConfirm(event, setOf(relay), config.publishTimeoutSecs)) accepted.addAndFetch(1)
                }
            } else {
                val answered = AtomicInt(0)
                val stream =
                    suspend {
                        client.publishStream(relay, events.asFlow(), config.uploadWindow, config.idleTimeoutMs) { _, result ->
                            if (!result.isTransportFailure) answered.addAndFetch(1)
                            if (result.accepted) accepted.addAndFetch(1)
                        }
                    }
                val outcome = if (deadline == null) stream() else withTimeoutOrNull(-deadline.elapsedNow()) { stream() }
                if (outcome == null || outcome.stalled) {
                    stopped = true
                    skipped.addAndFetch(events.size - answered.load())
                }
            }
        }
    }

    /**
     * Paged fallback: walk [relay] past its per-REQ cap for [filter], verifying and
     * inserting each event into [store]. [fetchAllPages]'s `onEvent` can't suspend, so
     * events funnel through a channel to a single inserter. Returns how many were newly stored.
     */
    private suspend fun pageDownload(
        relay: NormalizedRelayUrl,
        filter: Filter,
    ): Int {
        val stored = AtomicInt(0)
        val events = Channel<Event>(Channel.UNLIMITED)
        coroutineScope {
            val inserter =
                launch {
                    for (event in events) {
                        if (store.verifyAndInsert(event)) stored.addAndFetch(1)
                    }
                }
            try {
                // Like fetchByIds, a download keeps a finite idle bound even when the
                // whole-sync watchdog is disabled (idleTimeoutMs = 0) — a page that
                // never EOSEs must not hang the sync forever.
                val pageIdleMs = if (config.idleTimeoutMs > 0) config.idleTimeoutMs else DEFAULT_DOWNLOAD_IDLE_MS
                client.fetchAllPages(relay, listOf(filter), pageIdleMs) { event -> events.trySend(event) }
            } finally {
                events.close()
            }
            inserter.join()
        }
        return stored.load()
    }
}
