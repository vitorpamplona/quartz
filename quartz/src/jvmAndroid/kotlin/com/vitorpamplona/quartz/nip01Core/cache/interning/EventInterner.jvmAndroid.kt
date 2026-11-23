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

/**
 * JVM/Android actual: a [ConcurrentHashMap] of weak references, pre-sized for the expected hot
 * working set and swept through a [ReferenceQueue].
 *
 * The queue is what keeps the map bounded by the live events. Most ids are seen once (a note
 * scrolls past and is never asked for again), so sweeping a dead entry only when its own id is
 * looked up again would leave every one-off event's key, reference and map node in the map for
 * the life of the process (~170 B each).
 */
actual class EventInterner {
    private val cache = ConcurrentHashMap<HexKey, Ref>(INITIAL_CAPACITY, LOAD_FACTOR)
    private val collected = ReferenceQueue<Event>()

    /** Remembers its key, so the sweep can remove exactly this entry and not a newer one. */
    private class Ref(
        val id: HexKey,
        event: Event,
        queue: ReferenceQueue<Event>,
    ) : WeakReference<Event>(event, queue)

    actual fun intern(event: Event): Event {
        sweep()

        // Fast path: existing canonical is still alive.
        cache[event.id]?.get()?.let { return it }

        // Race-safe install: putIfAbsent ensures only one writer
        // wins. If another thread beat us, return whatever they put
        // (resolving the rare case where their ref was already GC'd
        // by retrying through the slow path).
        while (true) {
            val existing = cache.putIfAbsent(event.id, Ref(event.id, event, collected)) ?: return event
            existing.get()?.let { return it }
            // Existing entry's referent was GC'd; drop it and retry.
            cache.remove(event.id, existing)
        }
    }

    actual fun get(id: HexKey): Event? {
        sweep()
        val ref = cache[id] ?: return null
        val event = ref.get()
        if (event != null) return event
        // Collected but not queued yet: drop the dead entry now.
        cache.remove(id, ref)
        return null
    }

    actual fun size(): Int = cache.size

    actual fun clear() {
        cache.clear()
        while (collected.poll() != null) {
            // drains the references the GC queued
        }
    }

    /** Removes the entries whose event was collected. Two-argument remove spares a slot re-filled since. */
    private fun sweep() {
        while (true) {
            val ref = collected.poll() as? Ref ?: return
            cache.remove(ref.id, ref)
        }
    }

    actual companion object {
        actual val Default: EventInterner = EventInterner()

        private const val INITIAL_CAPACITY = 5_000
        private const val LOAD_FACTOR = 0.75f
    }
}
