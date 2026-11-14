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
package com.vitorpamplona.quartz.nip01Core.relay

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PassThroughPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.testing.RelayClientTest
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.TestRelay
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the pool learned about a relay's refusals is readable from the client, so an app that
 * plans its own requests (EOSE cursors that remember which kinds they covered) knows the pool
 * strips a kind from what it sends there and from what its answers report.
 */
class NostrClientRefusedKindsTest : RelayClientTest() {
    private val longForm = 30023

    /** A relay with a kind allowlist: it refuses the whole REQ over one kind it does not serve. */
    private object RefusesLongForm : PassThroughPolicy() {
        override fun accept(cmd: ReqCmd): PolicyResult<ReqCmd> =
            if (cmd.filters.any { 30023 in it.kinds.orEmpty() }) {
                PolicyResult.Rejected("blocked: kind 30023 not allowed")
            } else {
                PolicyResult.Accepted(cmd)
            }
    }

    private val relay = hub.register(TestRelay(defaultRelayUrl, policyBuilder = { RefusesLongForm })).url

    private class Recorder : SubscriptionListener {
        val eoses = Channel<List<Filter>?>(UNLIMITED)
        val eventFilters = mutableListOf<List<Filter>?>()

        override suspend fun onEvent(
            event: Event,
            isLive: Boolean,
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            synchronized(eventFilters) { eventFilters.add(forFilters) }
        }

        override fun onEose(
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            eoses.trySend(forFilters)
        }
    }

    @Test
    fun theKindsARelayRefusedAndItsFilterCapAreReadableFromTheClient() =
        runBlocking {
            assertEquals(emptySet(), client.disallowedKinds(relay))
            assertNull(client.maxFilters(relay))
            defaultRelay.preload(SyntheticEvents.batch(3, kind = 1))

            val recorder = Recorder()
            client.subscribe("sub", mapOf(relay to listOf(Filter(kinds = listOf(1, longForm)))), recorder)

            // The pool learns the refusal from the CLOSED, sends the REQ again without the kind, and
            // the relay answers that one.
            val answered = withTimeout(10_000) { recorder.eoses.receive() }
            assertEquals(setOf(longForm), client.disallowedKinds(relay))
            assertEquals(listOf(1), answered?.single()?.kinds, "the answer reports the filters as sent")
            client.unsubscribe("sub")
        }

    @Test
    fun everyAnswerToOneRequestReportsTheSameFilterList() =
        runBlocking {
            defaultRelay.preload(SyntheticEvents.batch(5, kind = 1))

            val recorder = Recorder()
            client.subscribe("sub", mapOf(relay to listOf(Filter(kinds = listOf(1)))), recorder)
            val atEose = withTimeout(10_000) { recorder.eoses.receive() }

            val reported = synchronized(recorder.eventFilters) { recorder.eventFilters.toList() }
            assertEquals(5, reported.size)
            assertTrue(atEose != null)
            reported.forEach { assertSame(atEose, it, "one list per request, so a listener can cache by identity") }
            client.unsubscribe("sub")
        }
}
