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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.EventIngest
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveEventStore
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store whose replay ignores cancellation (a blocking cursor loop) keeps producing rows
 * after the client closed the REQ. None of them, and no EOSE, may reach the client.
 */
class NonCooperativeReplayTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "0".repeat(128)

    private class BlockingStore(
        val inner: IEventStore,
    ) : IEventStore by inner {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        override suspend fun rawQuery(
            filters: List<Filter>,
            onEach: (RawEvent) -> Unit,
        ) {
            // Off the caller's thread, then block without a suspension point.
            yield()
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            try {
                inner.rawQuery(filters, onEach)
            } finally {
                finished.countDown()
            }
        }
    }

    private object AcceptingIngest : EventIngest {
        override suspend fun submit(
            event: Event,
            skipVerify: Boolean,
            onComplete: (IEventStore.InsertOutcome) -> Unit,
        ) = onComplete(IEventStore.InsertOutcome.Accepted)

        override fun hasBacklog() = false

        override fun close() {}
    }

    @Test
    fun aClosedReqSendsNothingFromAReplayThatIgnoredTheCancel() =
        runBlocking {
            val store = BlockingStore(EventStore(null))
            store.inner.insert(Event("1".padStart(64, '0'), pubkey, 1000L, 1, emptyArray(), "stored", sig))
            val frames = CopyOnWriteArrayList<String>()
            val messages = CopyOnWriteArrayList<Message>()
            val sink =
                object : SessionSink {
                    override fun message(message: Message) {
                        messages.add(message)
                    }

                    override fun raw(json: String) {
                        frames.add(json)
                    }
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = RelaySession(LiveEventStore(store, AcceptingIngest), EmptyPolicy, scope, sink, onClose = {})

            session.receive("""["REQ","s",{"kinds":[1]}]""")
            assertTrue(store.started.await(10, TimeUnit.SECONDS))
            session.receive("""["CLOSE","s"]""")
            store.release.countDown()
            assertTrue(store.finished.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)

            assertEquals(emptyList(), frames.toList())
            assertTrue(messages.none { it is EoseMessage })
            scope.cancel()
        }
}
