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
package com.vitorpamplona.quartz.nip01Core.relay.server.backend

import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A submit cancelled while it waits for room in a full queue (the connection closed mid-flood)
 * must not stay counted as pending: [IngestQueue.hasBacklog] would then read true for good, and
 * the deferred FTS catch-up, which waits for the backlog to clear, would never run again.
 */
class IngestQueueCancelledSubmitTest {
    private val signer = NostrSignerSync()

    @Test
    fun aCancelledSubmitDoesNotLeaveABacklogBehind() =
        runBlocking {
            val store = EventStore(dbName = null)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val queue =
                IngestQueue(
                    store = store,
                    parentContext = Dispatchers.IO,
                    maxBatch = 1,
                    capacity = 1,
                    verify = {
                        release.await(10, TimeUnit.SECONDS)
                        true
                    },
                    verifyDispatcher = Dispatchers.IO,
                )
            val events = (1..3).map { signer.sign(TextNoteEvent.build("n $it", createdAt = 1_000L + it)) }
            // The first is taken by the verifier, which blocks; the second fills the queue.
            queue.submit(events[0], false) { completed.countDown() }
            Thread.sleep(200)
            queue.submit(events[1], false) { completed.countDown() }
            // The third waits for room and is cancelled while it does.
            val waiting = launch(Dispatchers.IO) { queue.submit(events[2], false) { } }
            Thread.sleep(200)
            waiting.cancelAndJoin()

            release.countDown()
            completed.await(10, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 5_000
            while (queue.hasBacklog() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(queue.hasBacklog())
            store.close()
        }
}
