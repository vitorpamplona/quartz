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
package com.vitorpamplona.quartz.nip01Core.cache.interning

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM/Android actual: a [ConcurrentHashMap] of weak references, pre-sized for the expected hot
 * working set and swept through a [ReferenceQueue].
 *
 * The queue is what keeps the map bounded by the live events. Most ids are seen once (a note
 * scrolls past and is never asked for again), so sweeping a dead entry only when its own id is
 * looked up again would leave every one-off event's key, reference and map node in the map for
 * the life of the process (~170 B each).
 *
 * The sweep runs on the insert path only, once every [SWEEP_EVERY] inserts, by one thread at a
 * time, and polls at most [SWEEP_MAX] references. Only inserts grow the map, so that bounds it
 * the same; and a hit stays a lock-free map read. On Android, `ReferenceQueue.poll()` takes the
 * queue's monitor on every call, even when the queue is empty, so polling on every `intern`
 * would make every ingest thread contend on one lock.
 */
actual class EventInterner {
    private val cache = ConcurrentHashMap<HexKey, Ref>(INITIAL_CAPACITY, LOAD_FACTOR)
    private val collected = ReferenceQueue<Event>()

    // Racy on purpose: a lost increment only delays a sweep by one insert.
    private var insertsSinceSweep = 0
    private val sweeping = AtomicBoolean(false)

    /** Remembers its key, so the sweep can remove exactly this entry and not a newer one. */
    private class Ref(
        val id: HexKey,
        event: Event,
        queue: ReferenceQueue<Event>,
    ) : WeakReference<Event>(event, queue)

    actual fun intern(event: Event): Event {
        // Fast path: existing canonical is still alive.
        cache[event.id]?.get()?.let { return it }

        // Race-safe install: putIfAbsent ensures only one writer
        // wins. If another thread beat us, return whatever they put
        // (resolving the rare case where their ref was already GC'd
        // by retrying through the slow path).
        while (true) {
            val existing = cache.putIfAbsent(event.id, Ref(event.id, event, collected))
            if (existing == null) {
                if (++insertsSinceSweep >= SWEEP_EVERY) sweep()
                return event
            }
            existing.get()?.let { return it }
            // Existing entry's referent was GC'd; drop it and retry.
            cache.remove(event.id, existing)
        }
    }

    actual fun get(id: HexKey): Event? {
        val ref = cache[id] ?: return null
        val event = ref.get()
        if (event != null) return event
        // Self-cleaning: drop the dead entry now; its queued reference is a no-op later.
        cache.remove(id, ref)
        return null
    }

    actual fun size(): Int = cache.size

    actual fun clear() {
        // References still queued are harmless: the sweep's two-argument remove skips an entry
        // that is gone or was re-filled. Draining them here could drop the reference of an entry
        // added after the clear, leaving that entry dead in the map.
        cache.clear()
    }

    /** Removes the entries whose event was collected. Two-argument remove spares a slot re-filled since. */
    private fun sweep() {
        if (!sweeping.compareAndSet(false, true)) return
        try {
            insertsSinceSweep = 0
            for (i in 0 until SWEEP_MAX) {
                val ref = collected.poll() as? Ref ?: return
                cache.remove(ref.id, ref)
            }
        } finally {
            sweeping.set(false)
        }
    }

    actual companion object {
        actual val Default: EventInterner = EventInterner()

        private const val INITIAL_CAPACITY = 5_000
        private const val LOAD_FACTOR = 0.75f

        /** Inserts between sweeps. Each insert adds at most one entry, so a sweep of [SWEEP_MAX] keeps up. */
        private const val SWEEP_EVERY = 64

        /** Most references one sweep polls, so a large post-GC backlog never stalls a single caller. */
        private const val SWEEP_MAX = 256
    }
}
