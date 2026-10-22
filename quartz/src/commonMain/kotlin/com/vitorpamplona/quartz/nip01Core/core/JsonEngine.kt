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
package com.vitorpamplona.quartz.nip01Core.core

import kotlin.concurrent.Volatile

/**
 * Which code reads and writes Nostr JSON: events, tags, templates, rumors, filters, relay
 * messages, client commands, NIP-46 and NIP-55 messages, and the event-id preimage.
 *
 * Every entry point ([OptimizedJsonMapper], [com.vitorpamplona.quartz.nip01Core.crypto.EventHasher],
 * [com.vitorpamplona.quartz.nip01Core.crypto.EventHasherSerializer], the NIP-55 intents) reads
 * [current] on each call, so it can be switched at any time, typically once at startup.
 *
 * Not covered: NIP-47 and CLINK go through kotlinx under either engine, and pretty-printing
 * is whatever the caller picks (`JacksonMapper.toJsonPretty` or [com.vitorpamplona.quartz.nip01Core.kite.KiteWriter.toPrettyJson]).
 *
 * On JVM and Android both engines write the same bytes, so an event has the same id under
 * either. On native they differ for content or tags holding a control character with a hex
 * letter in its escape (U+000B, U+000E, U+000F, U+001A-U+001F) or a lone surrogate: kotlinx
 * escapes in lowercase and replaces a lone surrogate with U+FFFD, Kite writes what Jackson
 * writes on the JVM. Switching engines on native changes those ids.
 */
enum class JsonEngine {
    /** Jackson on JVM and Android, kotlinx on native. */
    DEFAULT,

    /**
     * Kite: Quartz's own Nostr JSON reader and writer ([com.vitorpamplona.quartz.nip01Core.kite.KiteWriter] and its internal
     * reader, in `nip01Core.kite`), the same code on every target. Opt-in while it is being
     * tested.
     */
    KITE,
    ;

    companion object {
        @Volatile
        var current: JsonEngine = DEFAULT

        val isKite: Boolean get() = current === KITE
    }
}
