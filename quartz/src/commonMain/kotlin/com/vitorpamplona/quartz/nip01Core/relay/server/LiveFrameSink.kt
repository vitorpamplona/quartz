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
package com.vitorpamplona.quartz.nip01Core.relay.server

/**
 * A [SessionSink] that takes a live `EVENT` frame in two parts instead of as one string, so a
 * transport fanning one event out to many sockets can encode the event's JSON once and share the
 * bytes. [RelaySession] checks for it once per `REQ`; a sink without it gets the concatenated
 * frame through [raw], as before.
 *
 * Only live events on the zero-decode `REQ` path arrive here. Stored replays, and every frame on
 * the screened path (a policy that filters outgoing events), still go through [raw] and [message].
 */
interface LiveFrameSink : SessionSink {
    /**
     * One live `EVENT` frame. The wire frame is [framePrefix], then [body], then `]`.
     *
     * @param framePrefix `["EVENT","<subId>",`, built once per subscription.
     * @param body the event's JSON. On a storage-backed relay ([NostrServer]'s `LiveEventStore`)
     *   it is serialized once per event and is the same [String] instance for every subscription
     *   the event reaches, across every connection, so a per-event encode cache can key on its
     *   identity. Other backends may build one per subscription; the frame is correct either way.
     */
    fun liveEvent(
        framePrefix: String,
        body: String,
    )
}
