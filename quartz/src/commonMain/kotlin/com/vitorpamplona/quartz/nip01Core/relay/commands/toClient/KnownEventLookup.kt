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
package com.vitorpamplona.quartz.nip01Core.relay.commands.toClient

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Events the app already holds, by id, so a frame carrying one is not parsed again. Set one on
 * [CachingEventDecoder.knownEvents]: when the decoder finds a frame's id and its own cache of
 * recent ids doesn't have it, it asks [find], and on an answer hands that event out (with the
 * frame's own subscription id) instead of parsing the frame. Kite's byte reader
 * (`KiteBytesReader`) asks the moment it reads the id, and on an answer reads nothing past it.
 *
 * It is called on every such frame, from every relay's delivery thread at once: it must be
 * thread-safe and fast (a map lookup, never I/O). Return only events you would hand out for that
 * id: not one you found forged.
 */
fun interface KnownEventLookup {
    fun find(id: HexKey): Event?
}
