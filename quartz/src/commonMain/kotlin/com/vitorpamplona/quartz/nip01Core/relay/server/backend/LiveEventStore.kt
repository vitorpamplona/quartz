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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.negentropy.storage.IStorage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.filters.FilterIndex
import com.vitorpamplona.quartz.nip01Core.relay.server.FanoutExecutor
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.StoreQueryContext
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A reactive event store that combines historical data retrieval with live event streaming.
 *
 * This class wraps an [IEventStore] to provide real-time updates. When a [query] is executed,
 * it first replays all matching historical events from the underlying store, signals the
 * End of Stored Events (EOSE), and then continues to stream matching new events as they
 * are inserted.
 *
 * Live fanout from [submit] / [insert] to interested subscribers is index-driven via
 * [FilterIndex]: each [query] registers its filters; on accepted ingest the index returns
 * the (much smaller) candidate set and we deliver only to those whose filters actually
 * match. This avoids the quadratic O(N_subscribers × N_filters_per_sub) per-event walk
 * that a SharedFlow-based broadcast would do.
 *
 * NIP-50 `search` strings are handed to the store **verbatim**, extension
 * tokens included: whether `include:spam` is a directive, ignorable noise,
 * or poison for a text engine is a property of the store, so each
 * [IEventStore] implementation makes that call itself (see the NIP-50
 * contract on [IEventStore] — the built-in SQLite and filesystem stores
 * strip the tokens at their own boundary). This layer also installs a
 * [StoreQueryContext] around each store call so observer-relative stores
 * can read the connection's NIP-42 identity.
 *
 * @property store The underlying persistent storage for events.
 * @property ingest The writer pipeline, quartz's group-commit [IngestQueue] unless the
 *  store brings its own. Accepted events fan out via the index; rejected events do not.
 */
@OptIn(ExperimentalAtomicApi::class)
class LiveEventStore(
    private val store: IEventStore,
    private val ingest: EventIngest,
) : SessionBackend {
    /**
     * Subscriptions whose connection declares no [FanoutExecutor]: matched and delivered on
     * the thread that accepted the event, as before executors existed.
     */
    private val inline = Partition(null)

    /**
     * One partition per [FanoutExecutor] that holds subscriptions, copy-on-write. There are
     * as many as the relay has delivery threads (one per event loop), so a linear scan finds
     * an executor's partition and a fan-out visits each once.
     */
    private val partitions = AtomicReference<Array<Partition>>(emptyArray())

    /** Ids handed to [ingest] whose fan-out has not been handed out yet; see [settle]. */
    private val inFlight = InFlightIds()

    /**
     * The subscriptions one executor delivers, in their own [FilterIndex], so a fan-out
     * probes each partition's index and never builds a relay-wide candidate set.
     */
    private class Partition(
        val executor: FanoutExecutor?,
    ) {
        val index = FilterIndex<LiveSubscription>()

        /**
         * Subscriptions this executor partition holds, counted so the last one out can
         * retire it; -1 once retired, after which [joinPartition] makes a fresh one.
         */
        val members = AtomicInt(0)
    }

    /**
     * One live REQ subscription. Carries the filters (for the
     * post-index `match` re-check needed for negative constraints
     * like `since` / `until` / `tagsAll`) and the delivery callback
     * the index dispatches into. [deliver] receives the event and its
     * pre-serialized wire body (memoized once per fanout across all
     * matching subscribers). Identity-keyed inside [FilterIndex].
     */
    private class LiveSubscription(
        val filters: List<Filter>,
        val partition: Partition,
        val deliver: (Event, String) -> Unit,
    ) {
        /** The replay's dedupe set until [settle] proves nothing can arrive twice; then null. */
        @Volatile
        var seen: SeenIds? = SeenIds()

        /**
         * Cleared on unregister. A fan-out matches against an index snapshot taken when the
         * event was accepted, which can still hold a subscription closed since.
         */
        @Volatile
        var active = true

        fun offer(
            event: Event,
            body: SharedBody,
        ) {
            val dedupe = seen
            if (dedupe != null && !dedupe.firstSighting(event.id)) return
            deliver(event, body.get())
        }

        fun settle(keep: HashSet<String>?) {
            if (seen?.settle(keep) == true) seen = null
        }
    }

    /**
     * An event's wire JSON, serialized at most once per fan-out by whichever delivery
     * thread first needs it. Every subscription, on every executor, gets the same [String]
     * instance ([com.vitorpamplona.quartz.nip01Core.relay.server.LiveFrameSink] promises it):
     * a thread that loses the race drops its copy and takes the winner's.
     */
    private class SharedBody(
        private val event: Event,
    ) {
        private val json = AtomicReference<String?>(null)

        fun get(): String = json.load() ?: event.toJson().let { if (json.compareAndSet(null, it)) it else json.load()!! }
    }

    /** A spin lock for critical sections a few hash operations long; `inline`, so no closure. */
    private class SpinLock {
        private val lock = AtomicBoolean(false)

        inline fun <R> locked(block: () -> R): R {
            while (lock.exchange(true)) {
                while (lock.load()) { }
            }
            try {
                return block()
            } finally {
                lock.store(false)
            }
        }
    }

    /**
     * Replay-dedupe set for one REQ. Until EOSE both the historical replay
     * and the concurrent live path claim each id here with [firstSighting]
     * and only the first claim delivers, whichever path gets there first:
     * an event accepted mid-replay may reach the live path before the
     * replay's snapshot reads it, or after.
     *
     * At EOSE the set can't simply be dropped: an event the replay read
     * may have been committed while its fan-out had not been handed out
     * yet, or (with a [FanoutExecutor]) was handed out but not run. So
     * [settle] keeps only the replayed ids still in flight, after every
     * fan-out already queued for this subscription has run, and each of
     * those is consumed by its one live delivery. Usually nothing is in
     * flight and the set is dropped whole.
     *
     * Claimed from both the replay coroutine and the fan-out thread, so
     * every access takes a tiny spin lock — [SpinLock.locked] is `inline`,
     * so the per-row `firstSighting` calls allocate no closure. The
     * backing `HashSet` is created empty up front (so the
     * register-before-replay race guarantee holds) but the JVM defers its
     * table allocation to the first `add`, so a zero-row replay costs only
     * the empty set object, not a sized table. It MUST stay a mutable set
     * under a lock, never a copy-on-add immutable set — `set + id` per row
     * made large replays O(n²).
     */
    private class SeenIds {
        private val lock = SpinLock()
        private var ids: HashSet<String>? = HashSet()
        private var settled = false

        /** True the first time [id] is seen, and for every id [settle] did not keep. */
        fun firstSighting(id: String): Boolean =
            lock.locked {
                val set = ids
                when {
                    set == null -> {
                        true
                    }

                    !settled -> {
                        set.add(id)
                    }

                    else -> {
                        val replayed = set.remove(id)
                        if (set.isEmpty()) ids = null
                        !replayed
                    }
                }
            }

        /** The replayed ids [inFlight] still holds: the only ones a live delivery can repeat. */
        fun inFlightPart(inFlight: InFlightIds): HashSet<String>? = lock.locked { ids?.let { inFlight.intersect(it) } }

        /** Keeps only [keep] from now on; true when that leaves nothing to dedupe. */
        fun settle(keep: HashSet<String>?): Boolean =
            lock.locked {
                settled = true
                ids = if (keep.isNullOrEmpty()) null else keep
                ids == null
            }
    }

    /**
     * Ids submitted to [ingest] whose fan-out has not been handed out yet — written before the
     * submit, cleared after the fan-out. An event the store has committed but not yet fanned
     * out is always in here, which is what lets [settle] drop a replay's dedupe set safely.
     * Counted, since two connections can submit the same id at once.
     */
    private class InFlightIds {
        private val lock = SpinLock()
        private val counts = HashMap<String, Int>()

        fun add(id: String) {
            lock.locked { counts[id] = (counts[id] ?: 0) + 1 }
        }

        fun remove(id: String) {
            lock.locked {
                val n = counts[id] ?: 0
                if (n <= 1) counts.remove(id) else counts[id] = n - 1
            }
        }

        fun intersect(ids: Set<String>): HashSet<String> =
            lock.locked {
                val out = HashSet<String>()
                if (counts.size <= ids.size) {
                    for (id in counts.keys) if (id in ids) out.add(id)
                } else {
                    for (id in ids) if (counts.containsKey(id)) out.add(id)
                }
                out
            }
    }

    /**
     * Fire-and-forget enqueue: hand [event] to the [ingest] and
     * fire [onComplete] once the writer's batch has a per-row
     * decision, which the ingest reports only after the write is
     * durable. On `Accepted` the live stream is then fanned out via
     * [FilterIndex] so subscribers see the event: after [onComplete]
     * returns, so the publisher's OK never waits for live delivery.
     * Suspends only when the ingest queue is full (backpressure).
     */
    override suspend fun submit(
        event: Event,
        onComplete: (IEventStore.InsertOutcome) -> Unit,
    ) = submit(event, skipVerify = false, onComplete = onComplete)

    /**
     * [submit] variant for locally-originated traffic (mirror/sync
     * workers rather than client connections). [skipVerify] exempts
     * this event from the [ingest]'s signature-verification hook —
     * the relay-to-relay trust model: set it only for events streamed
     * from a configured upstream relay that already verified them.
     * Accepted events fan out to live subscribers exactly like a
     * client publish.
     */
    suspend fun submit(
        event: Event,
        skipVerify: Boolean,
        onComplete: (IEventStore.InsertOutcome) -> Unit,
    ) {
        inFlight.add(event.id)
        try {
            ingest.submit(event, skipVerify) { outcome ->
                try {
                    val accepted = outcome is IEventStore.InsertOutcome.Accepted
                    if (accepted) writeGeneration.addAndFetch(1L)
                    // The verdict first: the ingest only calls back once the write is
                    // durable, and the publisher's OK need not wait for live delivery.
                    try {
                        onComplete(outcome)
                    } finally {
                        if (accepted) fanout(event)
                    }
                } finally {
                    inFlight.remove(event.id)
                }
            }
        } catch (e: Throwable) {
            inFlight.remove(event.id)
            throw e
        }
    }

    /**
     * Suspending insert kept for callers that don't care about
     * pipelining (tests, scripted paths). Routes through the same
     * [ingest] as [submit] so the batch write path is exercised
     * even by tests that prefer a sequential `insert` API.
     * Throws on rejection so callers can `try` around it the way the
     * old API did.
     */
    suspend fun insert(event: Event) {
        val done = CompletableDeferred<Unit>()
        submit(event) { outcome ->
            when (outcome) {
                IEventStore.InsertOutcome.Accepted -> {
                    done.complete(Unit)
                }

                is IEventStore.InsertOutcome.Rejected -> {
                    done.completeExceptionally(IllegalStateException(outcome.reason))
                }

                is IEventStore.InsertOutcome.Failed -> {
                    done.completeExceptionally(IllegalStateException(outcome.reason))
                }
            }
        }
        done.await()
    }

    /**
     * Live fanout for an accepted event, on the [ingest] callback, after
     * the publisher's `onComplete`.
     *
     * Subscriptions without an executor are matched and delivered right
     * here, as always: each `deliver` must be cheap (typically a `trySend`
     * to a per-connection outbound queue), since a slow one blocks the
     * batch writer. Each executor partition only gets its index probed:
     * when it holds a possible match, the executor gets one task with the
     * event and that index's snapshot as of now, and matches and delivers
     * on its own thread. A subscription opened after this point is not in
     * the snapshot (its replay reads the event, which is already stored).
     *
     * The index returns a super-set; `Filter.match` enforces negative
     * constraints. The wire body is serialized at most once for this event
     * across every partition — the old path re-serialized the whole event
     * per matching subscriber, so a note landing in N live feeds paid N
     * identical Jackson passes — and lazily, so a fanout that matches
     * nothing (the index over-approximates) serializes nothing.
     */
    private fun fanout(event: Event) {
        var body: SharedBody? = null
        val local = inline.index.candidatesFor(event)
        if (local.isNotEmpty()) {
            body = SharedBody(event)
            deliver(local, event, body)
        }
        for (partition in partitions.load()) {
            val snapshot = partition.index.snapshot()
            if (!snapshot.hasCandidates(event)) continue
            val shared = body ?: SharedBody(event).also { body = it }
            try {
                partition.executor!!.execute { deliver(snapshot.candidatesFor(event), event, shared) }
            } catch (e: Exception) {
                // An executor shutting down must not cost the other partitions their delivery.
                Log.w("LiveEventStore", "fan-out executor refused a task", e)
            }
        }
    }

    private fun deliver(
        candidates: Set<LiveSubscription>,
        event: Event,
        body: SharedBody,
    ) {
        for (sub in candidates) {
            if (sub.active && sub.filters.any { it.match(event) }) {
                sub.offer(event, body)
            }
        }
    }

    /**
     * Registers [filters] in the partition of [executor] (the inline one when null) and
     * returns the subscription. Complete once this returns: an event accepted from then on
     * reaches it.
     */
    private fun register(
        filters: List<Filter>,
        executor: FanoutExecutor?,
        deliver: (Event, String) -> Unit,
    ): LiveSubscription {
        val sub = LiveSubscription(filters, if (executor == null) inline else joinPartition(executor), deliver)
        sub.partition.index.register(filters, sub)
        return sub
    }

    /** Idempotent. The last subscription out of an executor partition retires it. */
    private fun unregister(sub: LiveSubscription) {
        sub.active = false
        val partition = sub.partition
        if (!partition.index.remove(sub) || partition.executor == null) return
        if (partition.members.addAndFetch(-1) == 0 && partition.members.compareAndSet(0, -1)) retire(partition)
    }

    /** The live partition for [executor], counting one more member in it; creates it if needed. */
    private fun joinPartition(executor: FanoutExecutor): Partition {
        while (true) {
            val current = partitions.load()
            var found: Partition? = null
            for (p in current) {
                if (p.executor === executor) {
                    found = p
                    break
                }
            }
            if (found == null) {
                val created = Partition(executor)
                created.members.store(1)
                if (partitions.compareAndSet(current, current + created)) return created
                continue
            }
            val members = found.members.load()
            if (members < 0) {
                // Retired, but still listed: finish dropping it, then make a fresh one.
                retire(found)
                continue
            }
            if (found.members.compareAndSet(members, members + 1)) return found
        }
    }

    private fun retire(partition: Partition) {
        while (true) {
            val current = partitions.load()
            if (current.none { it === partition }) return
            if (partitions.compareAndSet(current, current.filter { it !== partition }.toTypedArray())) return
        }
    }

    /**
     * The replay of [sub] reached EOSE: stop deduping, except for the replayed ids a live
     * delivery can still repeat. Those are the ones in [inFlight] now — committed (so the
     * replay could read them) but not yet fanned out. Every id that has left [inFlight] was
     * handed out before this check; on an executor, the switch is queued behind those tasks,
     * so they still dedupe against the full set.
     */
    private fun settle(sub: LiveSubscription) {
        val seen = sub.seen ?: return
        val keep = seen.inFlightPart(inFlight)
        val executor = sub.partition.executor
        if (executor == null) {
            sub.settle(keep)
        } else {
            try {
                executor.execute { sub.settle(keep) }
            } catch (e: Exception) {
                Log.w("LiveEventStore", "fan-out executor refused a task", e)
            }
        }
    }

    /**
     * Runs [block] with a [StoreQueryContext] carrying the connection's
     * NIP-42-authenticated pubkeys, so observer-relative stores can read
     * the caller's identity from the coroutine context. Skipped entirely
     * for unauthenticated connections — the element's contract is
     * "present means non-empty".
     */
    private suspend inline fun <R> withCallerIdentity(
        ctx: RequestContext,
        crossinline block: suspend () -> R,
    ): R {
        val users = ctx.authenticatedUsers
        return if (users.isEmpty()) {
            block()
        } else {
            withContext(StoreQueryContext(users)) { block() }
        }
    }

    /**
     * With deferred FTS, a search query must first drain the catch-up
     * backlog — that keeps NIP-50 results exactly as fresh as the
     * synchronous path (the deferral is invisible to correctness; only
     * publishes stop paying for tokenization). Non-search filters never
     * touch the FTS index and skip this entirely.
     */
    private suspend fun drainFtsIfSearching(filters: List<Filter>) {
        if (!store.needsFtsCatchUp) return
        if (filters.none { !it.search.isNullOrEmpty() }) return
        while (!store.ftsCatchUp()) {
            // Each batch is its own write transaction; loop until caught up.
        }
    }

    /**
     * Live callbacks run on [RequestContext.liveExecutor] when the connection declares one,
     * else on the thread that accepted the event.
     */
    override suspend fun query(
        ctx: RequestContext,
        filters: List<Filter>,
        onEach: (Event) -> Unit,
        onEose: () -> Unit,
    ) {
        drainFtsIfSearching(filters)
        // The index registers *before* the replay starts (otherwise an
        // event accepted mid-replay would slip past the live path entirely
        // — same race the previous SharedFlow-based implementation closed
        // with `onSubscription`), and [SeenIds] bridges the two paths:
        // each claims ids there and only the first claim delivers, and
        // after EOSE [settle] drops the set so every live event forwards.
        val sub = register(filters, ctx.liveExecutor) { event, _ -> onEach(event) }
        try {
            val seen = sub.seen!!
            withCallerIdentity(ctx) {
                store.query<Event>(filters) { event ->
                    if (seen.firstSighting(event.id)) onEach(event)
                }
            }
            onEose()
            settle(sub)
            // Suspend until the caller's coroutine is cancelled
            // (e.g. NIP-01 CLOSE or connection drop). The `finally`
            // unregisters from the index.
            awaitCancellation()
        } finally {
            unregister(sub)
        }
    }

    /**
     * Zero-decode variant of [query]: the historical replay streams
     * [RawEvent] rows straight from storage (no tags parse, no Event
     * materialization, no re-serialization), while the live path after
     * EOSE is identical to [query]'s. Same registration-before-replay
     * ordering, and the same spin-locked mutable dedupe set — NOT a
     * copy-on-add immutable set, which made giant replays O(n²) (see
     * [SeenIds]).
     */
    override suspend fun queryRaw(
        ctx: RequestContext,
        filters: List<Filter>,
        onEachStored: (RawEvent) -> Unit,
        onEachLive: (Event, String) -> Unit,
        onEose: () -> Unit,
    ) {
        drainFtsIfSearching(filters)
        val sub = register(filters, ctx.liveExecutor, onEachLive)
        try {
            val seen = sub.seen!!
            withCallerIdentity(ctx) {
                store.rawQuery(filters) { raw ->
                    if (seen.firstSighting(raw.id)) onEachStored(raw)
                }
            }
            onEose()
            settle(sub)
            awaitCancellation()
        } finally {
            unregister(sub)
        }
    }

    override suspend fun count(
        ctx: RequestContext,
        filters: List<Filter>,
    ): Int {
        drainFtsIfSearching(filters)
        return withCallerIdentity(ctx) { store.count(filters) }
    }

    /**
     * One-shot snapshot query. Used by NIP-77 negentropy: the server
     * needs the full set of event ids matching the filter at the
     * moment the NEG-OPEN arrives, not a streamed/live result.
     */
    suspend fun snapshotQuery(filter: Filter): List<Event> = store.query(filter)

    /**
     * Multi-filter snapshot. Unions the per-filter results and
     * deduplicates by event id so an event matching N filters is
     * yielded once. Used by NIP-77 NEG-OPEN when the policy stack
     * rewrote the single incoming filter into several.
     */
    suspend fun snapshotQuery(filters: List<Filter>): List<Event> {
        if (filters.size == 1) return snapshotQuery(filters[0])
        val seen = HashSet<String>()
        val merged = ArrayList<Event>()
        for (f in filters) {
            for (e in store.query<Event>(f)) {
                if (seen.add(e.id)) merged += e
            }
        }
        return merged
    }

    /**
     * Lightweight snapshot for NIP-77 negentropy. Returns
     * `(created_at, id)` pairs only — no Event materialisation —
     * matching strfry's `MemoryView` footprint of ~40 B/entry.
     *
     * If [maxEntries] is non-null, the underlying store may return
     * up to `maxEntries + 1` entries; the +1 sentinel lets the
     * caller distinguish "exactly at cap" from "exceeds cap" without
     * scanning past the cap.
     */
    override suspend fun snapshotIdsForNegentropy(
        filters: List<Filter>,
        maxEntries: Int?,
    ): List<IdAndTime> = store.snapshotIdsForNegentropy(filters, maxEntries)

    // ------------------------------------------------------------------
    // NIP-77 snapshot cache
    // ------------------------------------------------------------------

    /**
     * Bumped after every accepted write. A cached negentropy snapshot is
     * only valid while this hasn't moved. Deletion paths that bypass the
     * ingest queue (expiration sweeps, NIP-86 admin purges) don't bump it,
     * which is why cache entries also carry a short TTL: a snapshot is a
     * point-in-time set by NIP-77's nature, and a few seconds of staleness
     * only means a peer momentarily re-offers ids the relay just dropped.
     */
    private val writeGeneration = AtomicLong(0L)

    private class CachedSnapshot(
        val filterKey: String,
        val generation: Long,
        val builtAt: Long,
        val storage: IStorage?,
    )

    private val snapshotCache = AtomicReference<CachedSnapshot?>(null)

    /**
     * Serves repeated NEG-OPENs of the same filter from one sealed
     * storage as long as no write landed in between (single slot — the
     * mirror-heartbeat pattern is many peers reconciling the same broad
     * filter, not many filters). Rebuilding on every open costs a full
     * scan + O(n log n) seal that grows with the corpus: relayBench
     * measured 342 ms per identical-set reconcile at 50k events vs
     * strfry's 26 ms off its always-current tree.
     */
    override suspend fun sealedNegentropyStorage(
        filters: List<Filter>,
        maxEntries: Int,
    ): IStorage? {
        // Full-set NEG-OPENs — a single unconstrained filter, the shape
        // relay-relay sync sends by default — are served from the store's
        // always-current index when it maintains one: no scan, no
        // O(n log n) seal, cold or not. `null` falls through to the scan
        // path, which also owns the over-cap NEG-ERR detection.
        if (filters.size == 1 && filters[0].isEmpty()) {
            store.liveNegentropySnapshot(maxEntries)?.let { return it }
        }

        val generation = writeGeneration.load()
        val key = filters.joinToString("\u0000") { it.toJson() } + "\u0000cap=$maxEntries"
        val now = TimeUtils.now()

        val cached = snapshotCache.load()
        if (cached != null &&
            cached.filterKey == key &&
            cached.generation == generation &&
            now - cached.builtAt <= SNAPSHOT_TTL_SECONDS
        ) {
            return cached.storage
        }

        val built = super.sealedNegentropyStorage(filters, maxEntries)
        snapshotCache.store(CachedSnapshot(key, generation, now, built))
        return built
    }

    private companion object {
        /**
         * Ceiling on how long a cached snapshot may serve NEG-OPENs even
         * with no observed writes — bounds staleness from delete paths
         * the generation counter can't see.
         */
        const val SNAPSHOT_TTL_SECONDS = 30L
    }
}
