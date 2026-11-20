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
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.negentropyReconcileIds
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IdAndTime
import com.vitorpamplona.quartz.testing.RelayClientTest
import com.vitorpamplona.quartz.testing.SyntheticEvents
import com.vitorpamplona.quartz.testing.preload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every NEG-MSG the client sends stays under strfry's default 131,072-byte websocket payload,
 * however large the diff: an unlimited frame there closes the socket mid-reconcile.
 */
class NegentropyClientFrameSizeTest : RelayClientTest() {
    private fun events(seeds: IntProgression): List<Event> = seeds.map { SyntheticEvents.fakeEvent(idSeed = it, kind = 1, createdAt = it.toLong()) }

    @Test
    fun aLargeDiffIsSentInFramesAStrfryRelayAccepts() =
        runBlocking {
            // Interleaved in time, so every range of the reconcile disagrees.
            val onRelay = events(1..40_000 step 2)
            val local = events(2..40_000 step 2)
            defaultRelay.preload(onRelay)

            val diff =
                withTimeout(120_000) {
                    client.negentropyReconcileIds(
                        relay = defaultRelayUrl,
                        filter = Filter(kinds = listOf(1)),
                        localEntries = local.map { IdAndTime(it.createdAt, it.id) },
                    )
                }

            assertEquals(onRelay.size, diff.needIds.size)
            assertEquals(local.size, diff.haveIds.size)
            val sent = client.getOrCreateRelay(defaultRelayUrl).sentFrames
            assertTrue(sent != null && sent.count > 0, "the client records what it sent")
            assertTrue(sent.largestBytes <= STRFRY_MAX_WEBSOCKET_PAYLOAD, "largest frame sent: ${sent.largestBytes} bytes ($sent)")
        }

    private companion object {
        /** strfry's `relay.maxWebsocketPayloadSize` default. */
        const val STRFRY_MAX_WEBSOCKET_PAYLOAD = 131_072
    }
}
