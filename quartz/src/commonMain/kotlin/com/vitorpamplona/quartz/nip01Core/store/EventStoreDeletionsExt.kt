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
package com.vitorpamplona.quartz.nip01Core.store

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.addressTagOrNull
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent

/**
 * The local deletion events that would make [relay] remove one of [serverEvents] — the
 * events the relay HAS that we LACK. Used by sync to push *only* the deletions that
 * actually apply to what the relay holds, and nothing else (not other deletions by the
 * same author). Covers every way a stored deletion can reach an event:
 *
 *  - **NIP-09, id-based** — a kind-5 by a server event's author with an `e` tag naming
 *    that event's id.
 *  - **NIP-09, address-based** — a kind-5 by a server event's author with an `a` tag
 *    naming that event's addressable/replaceable coordinate, at or after that event's
 *    `created_at` (NIP-09 only deletes `created_at <= deletion.created_at`).
 *
 * A kind-5 signed by anyone else covers nothing: NIP-09 lets only an author delete.
 *  - **NIP-62 vanish** — a kind-62 by a server event's author, targeting [relay] (its
 *    `relay` tags name the URL or `ALL_RELAYS`), issued after that event (a vanish
 *    deletes `created_at < vanish.created_at`).
 *
 * Deduped by event id; a single deletion covering several events is returned once.
 *
 * [query] is where the deletions are looked up — it is source-agnostic on purpose, so
 * the same coverage rule runs in both sync directions:
 *  - **up** (send our deletions): `events` are the relay's, `query` is the local store —
 *    which of OUR deletions would delete what the relay still holds.
 *  - **down** (apply the relay's deletions): `events` are ours, `query` fetches from the
 *    relay — which of the RELAY'S deletions would delete what we still hold.
 */
suspend fun deletionsCovering(
    events: List<Event>,
    relay: NormalizedRelayUrl,
    query: suspend (Filter) -> List<Event>,
): List<Event> {
    if (events.isEmpty()) return emptyList()
    val covering = LinkedHashMap<HexKey, Event>()

    // NIP-09: only an event's author can delete it, so a kind-5 by anyone else covers nothing.
    val authors = events.mapTo(HashSet()) { it.pubKey }.toList()

    // 1. id-based NIP-09: a kind-5 `e`-tagging the id of one of its author's events.
    val byId = events.associateBy { it.id }
    query(Filter(kinds = listOf(DeletionRequestEvent.KIND), authors = authors, tags = mapOf("e" to events.map { it.id })))
        .forEach { del ->
            if (del.tags.any { it.size > 1 && it[0] == "e" && byId[it[1]]?.pubKey == del.pubKey }) covering[del.id] = del
        }

    // 2. address-based NIP-09: a kind-5 `a`-tagging an event's coordinate, cutoff-checked.
    val byAddress = HashMap<String, MutableList<Event>>()
    events.forEach { event -> event.addressTagOrNull()?.let { byAddress.getOrPut(it) { ArrayList(1) }.add(event) } }
    if (byAddress.isNotEmpty()) {
        query(Filter(kinds = listOf(DeletionRequestEvent.KIND), authors = authors, tags = mapOf("a" to byAddress.keys.toList())))
            .forEach { del ->
                if (del !is DeletionRequestEvent) return@forEach
                for (addr in del.deleteAddresses()) {
                    if (addr.pubKeyHex != del.pubKey) continue
                    val hit = byAddress[addr.toValue()] ?: continue
                    if (hit.any { it.createdAt <= del.createdAt }) {
                        covering[del.id] = del
                        break
                    }
                }
            }
    }

    // 3. NIP-62 vanish: a kind-62 by an event's author, targeting this relay, issued after it.
    query(Filter(kinds = listOf(RequestToVanishEvent.KIND), authors = authors))
        .forEach { vanish ->
            if (vanish !is RequestToVanishEvent || !vanish.shouldVanishFrom(relay)) return@forEach
            if (events.any { it.pubKey == vanish.pubKey && it.createdAt < vanish.createdAt }) covering[vanish.id] = vanish
        }

    return covering.values.toList()
}

/** [deletionsCovering] with the local store as the deletion source (the "up" direction). */
suspend fun IEventStore.deletionsCovering(
    serverEvents: List<Event>,
    relay: NormalizedRelayUrl,
): List<Event> = deletionsCovering(serverEvents, relay) { query<Event>(it) }
