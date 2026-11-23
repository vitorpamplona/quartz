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
package com.vitorpamplona.quartz.concord.envelope

import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * How a receiver that re-reads a buffer of stream wraps opens each one ([ConcordStreamEnvelope.openOrNull]).
 *
 * A rekey receiver (CORD-06) judges its whole buffer every time it runs, since a rotation it could
 * not take yet (an incomplete set of chunks, a Grant not folded yet) may become takeable. Opening a
 * wrap is a signature check and two NIP-44 decryptions, and its result never changes: a client that
 * runs the receiver on every state change passes an opener that remembers what it opened, so each
 * wrap is opened once instead of once per run.
 */
fun interface StreamWrapOpener {
    /** [wrap] opened for [stream], or null when it does not open there. */
    fun openOrNull(
        wrap: Event,
        stream: GroupKey,
    ): OpenedStreamEvent?

    companion object {
        /** Opens every wrap afresh. */
        val DIRECT = StreamWrapOpener { wrap, stream -> ConcordStreamEnvelope.openOrNull(wrap, stream) }
    }
}
