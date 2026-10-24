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
 * JVM/Android actual: ConcurrentHashMap of WeakReference, pre-sized
 * for the expected hot working set. When the GC collects an event, its
 * reference lands on [collected], and the next [intern], [get] or [size]
 * removes that map entry. Reclaiming only on a later access to the same
 * id kept an entry (the id string, the reference, the map node) for every
 * event ever interned and never asked for again.
 */
actual class EventInterner {
    private val collected = ReferenceQueue<Event>()

    /** A weak reference that remembers its id, so the queue can say which entry died. */
    private class Ref(
        val id: HexKey,
        event: Event,
        queue: ReferenceQueue<Event>,
    ) : WeakReference<Event>(event, queue)

    private val cache = ConcurrentHashMap<HexKey, Ref>(INITIAL_CAPACITY, LOAD_FACTOR)

    /** Removes the entries whose events were collected; a replaced entry stays put. */
    private fun dropCollected() {
        while (true) {
            val ref = collected.poll() as Ref? ?: return
            cache.remove(ref.id, ref)
        }
    }

    actual fun intern(event: Event): Event {
        dropCollected()
        // Fast path: existing canonical is still alive.
        cache[event.id]?.get()?.let { return it }

        // Race-safe install: putIfAbsent ensures only one writer
        // wins. If another thread beat us, return whatever they put
        // (resolving the rare case where their ref was already GC'd
        // by retrying through the slow path).
        while (true) {
            val ref = Ref(event.id, event, collected)
            val existing = cache.putIfAbsent(event.id, ref)
            if (existing == null) return event
            val canonical = existing.get()
            if (canonical != null) return canonical
            // Existing entry's referent was GC'd; drop it and retry.
            cache.remove(event.id, existing)
        }
    }

    actual fun get(id: HexKey): Event? {
        dropCollected()
        val ref = cache[id] ?: return null
        val event = ref.get()
        if (event != null) return event
        // Self-cleaning: drop the dead entry so the map doesn't bloat.
        cache.remove(id, ref)
        return null
    }

    actual fun size(): Int {
        dropCollected()
        return cache.size
    }

    actual fun clear() {
        cache.clear()
    }

    actual companion object {
        actual val Default: EventInterner = EventInterner()

        private const val INITIAL_CAPACITY = 5_000
        private const val LOAD_FACTOR = 0.75f
    }
}
