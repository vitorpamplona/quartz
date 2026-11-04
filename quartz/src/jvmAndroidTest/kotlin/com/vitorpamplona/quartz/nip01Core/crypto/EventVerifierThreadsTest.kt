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
package com.vitorpamplona.quartz.nip01Core.crypto

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each thread verifies with its own buffers: threads checking valid and forged events, small and
 * large, at the same time never see one another's bytes.
 */
class EventVerifierThreadsTest {
    @Test
    fun concurrentVerifiesGiveTheRightAnswers() {
        val signer = NostrSignerSync()
        val valid =
            List(16) { i ->
                val content = if (i % 4 == 0) "big $i ".repeat(5_000) else "note $i"
                signer.sign<Event>(1_700_000_000L + i, 1, emptyArray(), content)
            }
        val forged = valid.map { Event(it.id, it.pubKey, it.createdAt, it.kind, it.tags, it.content + "!", it.sig) }
        val stolenSig = valid.mapIndexed { i, e -> Event(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, valid[(i + 1) % valid.size].sig) }

        val threads = 8
        val rounds = 50
        val wrong = AtomicInteger(0)
        val barrier = CyclicBarrier(threads)
        (0 until threads)
            .map { t ->
                thread {
                    barrier.await()
                    repeat(rounds) { r ->
                        for (i in valid.indices) {
                            val k = (i + t + r) % valid.size
                            if (!valid[k].verify()) wrong.incrementAndGet()
                            if (forged[k].verify()) wrong.incrementAndGet()
                            if (stolenSig[k].verify()) wrong.incrementAndGet()
                            if (!valid[k].verifySignature()) wrong.incrementAndGet()
                            if (stolenSig[k].verifySignature()) wrong.incrementAndGet()
                        }
                    }
                }
            }.forEach { it.join() }

        assertEquals(0, wrong.get(), "wrong answers under concurrency")
    }
}
