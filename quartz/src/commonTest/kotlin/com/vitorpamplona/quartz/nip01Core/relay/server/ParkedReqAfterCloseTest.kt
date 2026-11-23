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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A REQ on the parked-coroutine path sends nothing once it is closed, even from a backend that
 * keeps answering after its cancellation (a blocking driver, a callback it cannot withdraw): the
 * live-backend path already checked `closed` before each row and the EOSE, the parked one did not.
 */
class ParkedReqAfterCloseTest {
    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /** Answers only once [release] completes, and ignores cancellation while it waits. */
    private class StubbornBackend(
        val event: Event,
    ) : SessionBackend {
        val release = CompletableDeferred<Unit>()

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            withContext(NonCancellable) { release.await() }
            onEach(event)
            onEose()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = 0
    }

    @Test
    fun aClosedParkedReqSendsNoRowsAndNoEose() =
        runTest {
            val event: Event = NostrSignerSync().sign(1_700_000_000L, 1, emptyArray(), "late")
            val backend = StubbornBackend(event)
            val sink = Frames()
            val session =
                RelaySession(
                    backend,
                    LimitsPolicy(RelayLimits()),
                    CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
                    sink,
                    onClose = {},
                )

            session.receive("""["REQ","s",{"kinds":[1]}]""")
            testScheduler.advanceUntilIdle()
            session.receive("""["CLOSE","s"]""")
            testScheduler.advanceUntilIdle()
            backend.release.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), sink.messages.filterIsInstance<EventMessage>().map { it.subId }, "a row after CLOSE")
            assertEquals(emptyList(), sink.messages.filterIsInstance<EoseMessage>().map { it.subId }, "an EOSE after CLOSE")
        }
}
