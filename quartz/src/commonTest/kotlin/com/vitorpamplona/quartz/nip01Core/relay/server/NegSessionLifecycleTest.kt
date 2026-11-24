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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySession
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 3, finding 3: a NEG-OPEN's snapshot build ends with its session. A NEG-CLOSE, a re-open
 * or a disconnect cancelled only the last job queued on the id, so once a NEG-MSG was chained
 * behind the open the scan kept running; and builds still running after their session was gone
 * did not count against the per-connection cap, so an OPEN/MSG/CLOSE loop queued scans without
 * bound.
 */
class NegSessionLifecycleTest {
    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        override fun raw(json: String) {}
    }

    /** Each snapshot waits on [gate]; with [ignoresCancellation], even once cancelled. */
    private class GatedNegBackend(
        val ignoresCancellation: Boolean,
    ) : SessionBackend {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var running = 0
        var cancelled = 0

        override suspend fun query(
            ctx: RequestContext,
            filters: List<Filter>,
            onEach: (Event) -> Unit,
            onEose: () -> Unit,
        ) {
            onEose()
            awaitCancellation()
        }

        override suspend fun count(
            ctx: RequestContext,
            filters: List<Filter>,
        ): Int = 0

        override suspend fun snapshotIdsForNegentropy(
            filters: List<Filter>,
            maxEntries: Int?,
        ): List<IdAndTime> {
            started++
            running++
            try {
                if (ignoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            } finally {
                running--
            }
            return emptyList()
        }
    }

    private fun negOpen(subId: String) = NegentropySession.fromEvents(subId, Filter(kinds = listOf(1)), emptyList()).open()

    @Test
    fun aNegCloseAfterANegMsgStopsTheSnapshotBuild() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val backend = GatedNegBackend(ignoresCancellation = false)
            val session = RelaySession(backend, PassThroughPolicy(), scope, Frames(), onClose = {})

            session.receive(negOpen("n1"))
            session.receive("""["NEG-MSG","n1","61"]""")
            session.receive("""["NEG-CLOSE","n1"]""")

            assertEquals(1, backend.cancelled, "the NEG-CLOSE cancels the build, not only the NEG-MSG queued behind it")
            assertEquals(0, backend.running)
            scope.cancel()
        }

    @Test
    fun aReOpenAfterANegMsgStopsTheReplacedBuild() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val backend = GatedNegBackend(ignoresCancellation = false)
            val session = RelaySession(backend, PassThroughPolicy(), scope, Frames(), onClose = {})

            session.receive(negOpen("n1"))
            session.receive("""["NEG-MSG","n1","61"]""")
            session.receive(negOpen("n1"))

            assertEquals(1, backend.cancelled, "the replaced build stops")
            assertEquals(1, backend.running, "only the new one runs")
            scope.cancel()
        }

    @Test
    fun buildsStillRunningAfterTheirCloseCountAgainstTheCap() =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val backend = GatedNegBackend(ignoresCancellation = true)
            val frames = Frames()
            val settings = NegentropySettings(maxSessionsPerConnection = 2)
            val session = RelaySession(backend, PassThroughPolicy(), scope, frames, onClose = {}, negentropySettings = settings)

            repeat(5) {
                session.receive(negOpen("n$it"))
                session.receive("""["NEG-MSG","n$it","61"]""")
                session.receive("""["NEG-CLOSE","n$it"]""")
            }

            assertEquals(2, backend.started, "no more scans run at once than the cap allows")
            assertEquals(3, frames.messages.count { it is NoticeMessage }, "the rest are refused as too many: ${frames.messages}")

            // Once the store lets the abandoned builds go, their slots are free again.
            backend.gate.complete(Unit)
            session.receive(negOpen("again"))
            assertEquals(3, backend.started)
            assertTrue(frames.messages.any { it.label() == "NEG-MSG" }, "${frames.messages}")
            scope.cancel()
        }
}
