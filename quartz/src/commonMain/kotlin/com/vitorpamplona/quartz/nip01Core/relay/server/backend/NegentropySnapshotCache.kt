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
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * [LiveEventStore]'s NIP-77 snapshot cache: a few sealed storages, least recently used out
 * first, bounded by count ([maxSlots]) and by the ids they hold together ([maxIds]; a
 * 1M-id snapshot is ~40 MB), each served for at most [ttlSeconds] after its walk started.
 * The most recent one stays even when it alone is past [maxIds], as the one slot before it did.
 *
 * **Single flight.** Opens of one key while its walk runs wait for that walk instead of
 * starting their own. The walk runs in its own coroutine, in the first opener's context minus
 * its job, so a waiter that is cancelled only stops waiting; the walk is cancelled once nobody
 * waits for it. A failed walk fails every waiter and is not cached.
 *
 * **Invalidation.** [onAccepted] runs for every accepted write before the publisher's `OK`, and
 * drops each cached snapshot the write may change ([SnapshotScope.affectedBy]), or every one when
 * the write is not filter-local. A walk running at that moment is marked dirty: its waiters get
 * it (their opens came before the `OK`), but it is not cached, and an open after the `OK` starts
 * a walk of its own instead of joining it. A walk that starts after the write's commit sees it.
 * So an open never gets a snapshot that misses a write acknowledged before it, or keeps an event
 * such a write deleted or replaced. Deletions this layer never sees (an expiry sweep, an admin
 * purge on the store itself) are bounded by [ttlSeconds] only. A `search` filter is judged by
 * its other fields only where [searchIsFilterLocal] says its search is local; it runs once per
 * search filter of each open, outside the lock, and must not throw.
 */
internal class NegentropySnapshotCache(
    private val maxSlots: Int,
    private val maxIds: Long,
    private val ttlSeconds: Long,
    private val searchIsFilterLocal: (Filter) -> Boolean = SnapshotScope.NO_SEARCH_IS_LOCAL,
    private val clock: () -> Long = { TimeUtils.now() },
) {
    init {
        require(maxSlots >= 1) { "maxSlots must be at least 1: $maxSlots" }
    }

    private class Slot(
        val scope: SnapshotScope,
        val builtAt: Long,
        val storage: IStorage?,
    ) {
        val ids: Long = storage?.size()?.toLong() ?: 0L
    }

    private class Build(
        val key: String,
        val scope: SnapshotScope,
        val startedAt: Long,
    ) {
        /** A write may have changed its set, or nobody waits for it: never cached, never joined. */
        var dirty = false
        var waiters = 0
        lateinit var job: Deferred<IStorage?>
    }

    /** Guards everything below. Never held across a suspension or a store call. */
    private val lock = PlatformLock()

    /** Insertion-ordered: the eldest is the least recently used, a hit moves to the end. */
    private val slots = LinkedHashMap<String, Slot>()
    private var heldIds = 0L

    /** The walk an open of each key joins. Every walk not here is dirty. */
    private val building = HashMap<String, Build>()

    /** For tests: how many snapshots are cached, and how many ids they hold. */
    val size: Int get() = lock.withLock { slots.size }
    val idsHeld: Long get() = lock.withLock { heldIds }

    /**
     * The cached snapshot for [key], or the one the walk running for it returns, or a new walk's
     * ([build], run once however many opens wait for it). [filters] are what the key was made
     * from, for [onAccepted].
     */
    suspend fun getOrBuild(
        key: String,
        filters: List<Filter>,
        build: suspend () -> IStorage?,
    ): IStorage? {
        val now = clock()
        val scope = SnapshotScope(filters, searchIsFilterLocal)
        val context = currentCoroutineContext().minusKey(Job)
        var started: Build? = null
        val joined: Build =
            lock.withLock {
                val hit = slots[key]
                if (hit != null) {
                    if (now - hit.builtAt <= ttlSeconds) {
                        // Most recently used now: move it to the end.
                        slots.remove(key)
                        slots[key] = hit
                        return hit.storage
                    }
                    dropLocked(key)
                }
                val running = building[key]
                if (running != null && !running.dirty) {
                    running
                } else {
                    Build(key, scope, now).also {
                        it.job = launchWalk(context, it, build)
                        building[key] = it
                        started = it
                    }
                }.also { it.waiters++ }
            }
        started?.job?.start()
        try {
            return joined.job.await()
        } catch (e: CancellationException) {
            if (!joined.job.isCompleted) {
                // This waiter was cancelled; the walk goes on for the others.
                leave(joined)
                throw e
            }
            // The walk ended itself cancelled: that is a failure to a waiter still running.
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("the negentropy snapshot walk was cancelled", e)
        }
    }

    /** Lazy, so it starts outside the lock. Detached from the opener's job: see the class KDoc. */
    private fun launchWalk(
        context: CoroutineContext,
        b: Build,
        build: suspend () -> IStorage?,
    ): Deferred<IStorage?> =
        CoroutineScope(context + SupervisorJob()).async(start = CoroutineStart.LAZY) {
            val built =
                try {
                    build()
                } catch (e: Throwable) {
                    finish(b, null, ok = false)
                    throw e
                }
            finish(b, built, ok = true)
            built
        }

    private fun finish(
        b: Build,
        storage: IStorage?,
        ok: Boolean,
    ) {
        lock.withLock {
            if (building[b.key] === b) building.remove(b.key)
            // A walk longer than the TTL is stale on arrival.
            if (ok && !b.dirty && clock() - b.startedAt <= ttlSeconds) putLocked(b.key, Slot(b.scope, b.startedAt, storage))
        }
    }

    private fun leave(b: Build) {
        val abandoned =
            lock.withLock {
                b.waiters--
                if (b.waiters > 0) return
                b.dirty = true
                if (building[b.key] === b) building.remove(b.key)
                true
            }
        if (abandoned) b.job.cancel()
    }

    /**
     * An accepted write, before its `OK` goes out. [filterLocal] says it changes a filter's set only
     * as the store rules say (see [SnapshotScope.affectedBy]); false drops every snapshot.
     */
    fun onAccepted(
        event: Event,
        filterLocal: Boolean,
    ) {
        lock.withLock {
            if (slots.isEmpty() && building.isEmpty()) return
            val it = slots.values.iterator()
            while (it.hasNext()) {
                val slot = it.next()
                if (!filterLocal || slot.scope.affectedBy(event)) {
                    heldIds -= slot.ids
                    it.remove()
                }
            }
            for (b in building.values) {
                if (!filterLocal || b.scope.affectedBy(event)) b.dirty = true
            }
        }
    }

    private fun putLocked(
        key: String,
        slot: Slot,
    ) {
        dropLocked(key)
        val now = clock()
        val it = slots.values.iterator()
        while (it.hasNext()) {
            val old = it.next()
            if (now - old.builtAt > ttlSeconds) {
                heldIds -= old.ids
                it.remove()
            }
        }
        slots[key] = slot
        heldIds += slot.ids
        while (slots.size > maxSlots || (heldIds > maxIds && slots.size > 1)) {
            dropLocked(slots.keys.first())
        }
    }

    private fun dropLocked(key: String) {
        val old = slots.remove(key) ?: return
        heldIds -= old.ids
    }
}

/**
 * Which accepted writes may change the set a snapshot of these filters holds, under the store
 * rules (`event-store-semantics` skill, `references/store-rules.md`). Over-approximates, never
 * under: a false positive costs one walk, a false negative serves a stale set.
 *
 * - The event joins every filter it matches. The NIP-01 fields are compared as the stores do,
 *   except where they may match more than [Filter.match] does: an empty list constrains nothing
 *   (F11), and `#d` is not checked at all (F05: the stores match it against the parsed d-tag, which
 *   is `""` for an addressable event without one). A filter's `limit` needs nothing more: a write
 *   that matches no filter changes no filter's top N.
 * - A replaceable or addressable event supersedes the stored version of its kind by its author
 *   (W01, W02), whose id, tags and `created_at` are unknown here: every filter that could hold an
 *   event of that kind by that author.
 * - A kind 5 deletes its author's events by id and address (D01, D02), and an in-scope kind 62
 *   everything its author owns (D07): events whose pubkey is the author, and gift wraps addressed
 *   to them (W06), whose pubkey is a one-time key. Every filter that could hold an event by the
 *   author, or a gift wrap.
 * - A `search` filter's set is the store's call: every write may change it, unless
 *   `searchIsFilterLocal` says its search only narrows what its other fields select by each
 *   event's own facts. Then it is judged by those fields, as above, since a write they cannot match
 *   cannot join the narrower set either. One filter whose search is not local is enough for every
 *   write to touch the snapshot. [searchIsFilterLocal] runs here, once per search filter, when the
 *   scope is made: never per write.
 * - Ephemeral events are never stored (W03), but are checked like any other: harmless.
 */
internal class SnapshotScope(
    filters: List<Filter>,
    searchIsFilterLocal: (Filter) -> Boolean = NO_SEARCH_IS_LOCAL,
) {
    private val parts = Array(filters.size) { Part(filters[it]) }

    /** A filter whose `search` may follow other writes: then every write may change the set. */
    private val everyWrite = filters.any { !it.search.isNullOrEmpty() && !searchIsFilterLocal(it) }

    fun affectedBy(event: Event): Boolean {
        if (everyWrite) return true
        val kind = event.kind
        val supersedes = kind.isReplaceable() || kind.isAddressable()
        val deletes = kind == DeletionRequestEvent.KIND || kind == RequestToVanishEvent.KIND
        for (part in parts) {
            if (part.mayMatch(event)) return true
            if (supersedes && part.mayHold(kind, event.pubKey)) return true
            if (deletes && (part.mayHoldBy(event.pubKey) || part.mayHoldKind(GiftWrapEvent.KIND))) return true
        }
        return false
    }

    companion object {
        /** Today's rule for a store that says nothing: no search is local. */
        val NO_SEARCH_IS_LOCAL: (Filter) -> Boolean = { false }
    }

    private class Part(
        filter: Filter,
    ) {
        val ids = filter.ids?.takeIf { it.isNotEmpty() }?.toHashSet()
        val authors = filter.authors?.takeIf { it.isNotEmpty() }?.toHashSet()
        val kinds = filter.kinds?.takeIf { it.isNotEmpty() }?.toHashSet()
        val since = filter.since
        val until = filter.until
        val anyOf = constraints(filter.tags)
        val allOf = constraints(filter.tagsAll)

        fun mayHoldKind(kind: Int) = kinds == null || kind in kinds

        fun mayHoldBy(author: String) = authors == null || author in authors

        fun mayHold(
            kind: Int,
            author: String,
        ) = mayHoldKind(kind) && mayHoldBy(author)

        fun mayMatch(event: Event): Boolean {
            if (ids != null && event.id !in ids) return false
            if (!mayHold(event.kind, event.pubKey)) return false
            if (since != null && event.createdAt < since) return false
            if (until != null && event.createdAt > until) return false
            for (c in anyOf) if (!c.hasAny(event)) return false
            for (c in allOf) if (!c.hasAll(event)) return false
            return true
        }

        private companion object {
            fun constraints(tags: Map<String, List<String>>?): Array<TagConstraint> {
                if (tags == null) return emptyArray()
                val out = ArrayList<TagConstraint>(tags.size)
                for ((name, values) in tags) {
                    if (name == "d" || values.isEmpty()) continue
                    out.add(TagConstraint(name, values.toHashSet()))
                }
                return out.toTypedArray()
            }
        }
    }

    private class TagConstraint(
        val name: String,
        val values: Set<String>,
    ) {
        fun hasAny(event: Event): Boolean {
            val tags = event.tags
            for (i in tags.indices) {
                val tag = tags[i]
                if (tag.size > 1 && tag[0] == name && tag[1] in values) return true
            }
            return false
        }

        fun hasAll(event: Event): Boolean {
            for (value in values) {
                var found = false
                val tags = event.tags
                for (i in tags.indices) {
                    val tag = tags[i]
                    if (tag.size > 1 && tag[0] == name && tag[1] == value) {
                        found = true
                        break
                    }
                }
                if (!found) return false
            }
            return true
        }
    }
}
