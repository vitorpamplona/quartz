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
package com.vitorpamplona.quartz.nip50Search

/**
 * Tag names any kind may carry whose values describe the event rather than say anything its
 * author wrote: fallback text, the client, media attachments and URLs, expiry, proof of work,
 * bridging, relays and zap routing.
 *
 * Search code that walks a tag set it does not know name by name skips these: the decentralized
 * lists' index walk, which indexes every other tag its NIP does not define, and
 * [EventSearchMatcher]'s fallback for kinds that are not [SearchableEvent]s. Keep it the one list,
 * so local search and store indexing agree on what is metadata.
 */
val METADATA_TAG_NAMES: Set<String> =
    hashSetOf(
        "alt", // NIP-31: fallback text restating other tags ("Song: … by …")
        "client", // NIP-89: the app that published the event
        "imeta", // NIP-92: `key value` pairs of URLs and hashes
        "expiration", // NIP-40
        "emoji", // NIP-30: shortcode and image URL
        "nonce", // NIP-13
        "proxy", // NIP-48: the bridged original's id
        "published_at", // NIP-23: a timestamp
        "content-warning", // NIP-36
        "relay", // NIP-42 and relay hints
        "relays", // NIP-57 and other relay lists
        "zap", // NIP-57 splits: pubkey, relay, weight
        "image", // NIP-23, NIP-52, NIP-58: media URLs, often content hashes
        "thumb",
        "picture",
        "banner",
        "icon",
    )
