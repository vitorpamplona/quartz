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
package com.vitorpamplona.quartz.nip01Core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #166 measured on a real device: an idle [NostrClient] must not keep waking the
 * coroutine scheduler. The relay-set watcher used `sample(300)`, whose ticker woke
 * `kotlinx.coroutines.DefaultExecutor` ~3.3 times a second for the client's whole life.
 * This counts that thread's voluntary context switches, as the issue did.
 */
@RunWith(AndroidJUnit4::class)
class NostrClientIdleWakeupsTest {
    private class SilentBuilder : WebsocketBuilder {
        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket =
            object : WebSocket {
                override fun needsReconnect() = false

                override fun connect() {}

                override fun disconnect() {}

                override fun send(msg: String) = true
            }
    }

    /**
     * Voluntary context switches of the threads whose name (`comm`, cut to 15 chars) starts
     * with [prefix]: `DefaultExecutor` runs the coroutine timers, `DefaultDispatch` the workers.
     */
    private fun wakeups(prefix: String): Long =
        File("/proc/self/task").listFiles().orEmpty().sumOf { task ->
            val comm = runCatching { File(task, "comm").readText().trim() }.getOrNull()
            if (comm == null || !comm.startsWith(prefix)) return@sumOf 0L
            runCatching {
                File(task, "status")
                    .readLines()
                    .first { it.startsWith("voluntary_ctxt_switches:") }
                    .substringAfter(':')
                    .trim()
                    .toLong()
            }.getOrDefault(0L)
        }

    /** DefaultExecutor wakeups over [millis] of an idle client, after [setup] and two seconds to settle. */
    private fun executorWakeupsOver(
        label: String,
        millis: Long,
        setup: (NostrClient) -> Unit,
    ): Long {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(SilentBuilder(), scope)
        try {
            setup(client)
            Thread.sleep(2_000)
            val executor = wakeups("DefaultExecutor")
            val workers = wakeups("DefaultDispatch")
            Thread.sleep(millis)
            val executorDelta = wakeups("DefaultExecutor") - executor
            val workersDelta = wakeups("DefaultDispatch") - workers
            Log.i(TAG) { "$label: DefaultExecutor $executorDelta, DefaultDispatcher workers $workersDelta wakeups in ${millis}ms" }
            return executorDelta
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun anIdleConnectedClientDoesNotWakeTheScheduler() {
        val relay = RelayUrlNormalizer.normalize("wss://relay.example/")
        val wakeups = executorWakeupsOver("connected, idle", 10_000) { it.subscribe("idle", mapOf(relay to listOf(Filter(kinds = listOf(1)))), null) }
        // A 300 ms ticker costs ~33 in 10 s.
        assertTrue("DefaultExecutor woke $wakeups times in 10 s of idle", wakeups < 5)
    }

    @Test
    fun aDisconnectedClientDoesNotWakeTheScheduler() {
        val wakeups = executorWakeupsOver("disconnected", 10_000) { it.disconnect() }
        assertTrue("DefaultExecutor woke $wakeups times in 10 s after disconnect()", wakeups < 5)
    }

    companion object {
        private const val TAG = "NostrClientIdleWakeups"
    }
}
