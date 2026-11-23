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
package com.vitorpamplona.quartz.nip01Core.relay.client.pool

import com.vitorpamplona.quartz.nip01Core.relay.client.testing.FakeRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountResult
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relay that answers a COUNT before the transport reports the send (the in-process relay, or
 * any fast one) must not leave the query looking in flight: the SENT recorded on the send used to
 * overwrite the RECEIVED its answer had already recorded, and every later change to the query was
 * then held back as if a COUNT were still pending.
 */
class PoolCountsFastReplyTest {
    private val url = NormalizedRelayUrl("wss://counts.example/")

    @Test
    fun anAnswerThatBeatsTheSendReportStillLetsTheNextChangeGo() {
        val counts = PoolCounts()
        val relay = FakeRelayClient(url)
        val sent = mutableListOf<Command>()

        counts.addOrUpdate("q", mapOf(url to listOf(Filter(kinds = listOf(1)))))
        counts.sendToRelayIfChanged("q", setOf(url)) { _, cmd ->
            sent += cmd
            // The relay answers before the client's onSent runs.
            counts.onIncomingMessage(relay, CountMessage("q", CountResult(5)))
            counts.onSent(url, cmd)
        }
        assertEquals(1, sent.size)

        counts.addOrUpdate("q", mapOf(url to listOf(Filter(kinds = listOf(7)))))
        counts.sendToRelayIfChanged("q", setOf(url)) { _, cmd -> sent += cmd }
        assertEquals(2, sent.size, "the changed query goes out")
        assertEquals(listOf(7), (sent[1] as CountCmd).filters.first().kinds)
    }
}
