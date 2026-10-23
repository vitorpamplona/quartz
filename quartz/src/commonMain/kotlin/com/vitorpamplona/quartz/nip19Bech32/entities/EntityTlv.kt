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
package com.vitorpamplona.quartz.nip19Bech32.entities

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer

/** The NIP-21 scheme the `createNostrUri` helpers write in front of the entity. */
internal const val NOSTR_URI_PREFIX = "nostr:"

/**
 * Normalizes the relay hint at `bytes[from, to)` and appends it to [relays], allocating the list
 * on the first valid hint. Returns the list, still null when no hint was valid.
 */
internal fun addRelayHint(
    relays: ArrayList<NormalizedRelayUrl>?,
    bytes: ByteArray,
    from: Int,
    to: Int,
): ArrayList<NormalizedRelayUrl>? {
    val relay = RelayUrlNormalizer.normalizeHintOrNull(bytes.decodeToString(from, to)) ?: return relays
    val list = relays ?: ArrayList(2)
    list.add(relay)
    return list
}
