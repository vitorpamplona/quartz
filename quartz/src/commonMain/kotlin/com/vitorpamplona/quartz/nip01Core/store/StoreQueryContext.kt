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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The published caller-identity seam between a relay layer and an
 * [IEventStore]: who is asking, carried on the coroutine context so it
 * crosses the store boundary — and any decorator in between — without
 * widening every `query`/`count` signature.
 *
 * The storage-backed relay path (`LiveEventStore`) installs this element
 * around every REQ/COUNT-driven store call when the connection has
 * NIP-42-authenticated pubkeys. A store whose results are
 * observer-relative — web-of-trust ranking, "for-you" relevance, trust
 * floors — reads it back:
 *
 * ```
 * val observer = coroutineContext[StoreQueryContext]?.observer
 * ```
 *
 * ## What a store may do with it
 *
 * The observer is a **lens over the filter's match set**:
 *
 * - **Rank**: reorder or score results from the observer's point of view.
 * - **Gate**: drop events the filter matches but the observer's lens
 *   excludes (a trust floor, "only authors my providers rank"). The answer
 *   is then a *subset* of what the filter alone would match.
 *
 * And what it may not:
 *
 * - **Widen**: never return an event the filter does not match; the
 *   observer narrows or reorders, it never adds.
 * - **Disagree with itself**: `query`, `count` and
 *   `snapshotIdsForNegentropy` under the same observer answer the same set
 *   (a COUNT equals the REQ it counts), and the same observer, filter and
 *   corpus give the same answer every time.
 * - **Replace access control**: whether a caller may read at all is the
 *   relay policy's decision, made before the store runs, where a refusal
 *   can carry a `CLOSED` reason. A gated store answers fewer events, not a
 *   refusal, so it must not be the only thing keeping private data private.
 *
 * The element is absent for unauthenticated callers (and for direct store
 * use outside a relay), so every read needs a null-tolerant fallback such
 * as an operator-configured default observer, or no gate at all. A store
 * whose lens can also come from the search string (an `observer:`
 * extension) decides which wins and documents it.
 */
class StoreQueryContext(
    /**
     * The pubkeys authenticated on the calling connection via NIP-42, in
     * the order they authenticated (re-authenticating a key does not move
     * it). Never empty — the relay layer skips installing the element
     * instead of installing an empty one. A caller building this element
     * itself passes an insertion-ordered set (`setOf`, `linkedSetOf`) so
     * [observer] stays deterministic; a hash-ordered set makes it arbitrary.
     */
    val authenticatedUsers: Set<HexKey>,
) : AbstractCoroutineContextElement(StoreQueryContext) {
    companion object Key : CoroutineContext.Key<StoreQueryContext>

    /**
     * The identity an observer-relative store answers for: the **first**
     * of [authenticatedUsers] — the first key that authenticated on the
     * connection — or `null` when the set is empty. Deterministic, so a
     * connection that adds a second identity keeps the lens it started
     * with. A store that wants every identity reads [authenticatedUsers].
     */
    val observer: HexKey? get() = authenticatedUsers.firstOrNull()
}
