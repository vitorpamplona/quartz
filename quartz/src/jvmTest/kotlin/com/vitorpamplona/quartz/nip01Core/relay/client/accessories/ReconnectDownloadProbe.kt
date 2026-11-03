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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.CaughtUpResume
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.DynamicSubscription
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * What a reconnect downloads again, before and after resumable subscriptions. On one connection
 * to each relay, two `DynamicSubscription`s with the same filters: one with `resume = null`, which
 * is what every callback subscription did before (the REQ re-sent as given), and one with the
 * default [CaughtUpResume]. After both are caught up and have tailed the relay for a while, the
 * connection drops; the probe counts, per subscription, the events and bytes the relay sends from
 * the drop until that subscription's re-sent REQ reaches EOSE.
 *
 * Skipped unless `RECONNECT_DOWNLOAD` lists relay urls. `RECONNECT_DOWNLOAD_PUBLISH=url,…` names
 * relays to fill first with `RECONNECT_DOWNLOAD_NOTES` notes (default 3,000) spread over the last
 * six hours. Gradle doesn't track environment variables: pass `--rerun`.
 */
class ReconnectDownloadProbe {
    private val relays = probeEnv("RECONNECT_DOWNLOAD")
    private val publishTo = probeEnv("RECONNECT_DOWNLOAD_PUBLISH").map { RelayUrlNormalizer.normalize(it) }.toSet()
    private val notes = System.getenv("RECONNECT_DOWNLOAD_NOTES")?.toInt() ?: 3_000
    private val tailSeconds = System.getenv("RECONNECT_DOWNLOAD_TAIL")?.toLong() ?: 20

    /** Per subscription id: events and bytes received, and when each EOSE came. */
    private class Traffic : WebsocketBuilder {
        lateinit var delegate: WebsocketBuilder
        val events = ConcurrentHashMap<String, AtomicLong>()
        val bytes = ConcurrentHashMap<String, AtomicLong>()
        val eoses = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
        private val sockets = CopyOnWriteArrayList<WebSocket>()

        private fun subIdOf(
            text: String,
            prefix: String,
        ): String? {
            if (!text.startsWith(prefix)) return null
            val end = text.indexOf('"', prefix.length)
            return if (end > 0) text.substring(prefix.length, end) else null
        }

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket {
            val counting =
                object : WebSocketListener by out {
                    override suspend fun onMessage(text: String) {
                        subIdOf(text, "[\"EVENT\",\"")?.let { sub ->
                            events.getOrPut(sub) { AtomicLong() }.incrementAndGet()
                            bytes.getOrPut(sub) { AtomicLong() }.addAndGet(text.length.toLong())
                        }
                        subIdOf(text, "[\"EOSE\",\"")?.let { sub -> eoses.getOrPut(sub) { CopyOnWriteArrayList() } += System.currentTimeMillis() }
                        out.onMessage(text)
                    }
                }
            return delegate.build(url, counting).also { sockets += it }
        }

        fun dropAll() = sockets.forEach { it.disconnect() }

        fun events(sub: String) = events[sub]?.get() ?: 0

        fun bytes(sub: String) = bytes[sub]?.get() ?: 0

        fun eoseCount(sub: String) = eoses[sub]?.size ?: 0
    }

    private suspend fun fill(relay: NormalizedRelayUrl) {
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(BasicOkHttpWebSocket.Builder { http }, scope)
        val signer = NostrSignerSync()
        val now = TimeUtils.now()
        try {
            (0 until notes).chunked(200).forEach { chunk ->
                chunk.forEach { i -> client.publish(signer.sign<Event>(now - 6 * 3600 + i * (6 * 3600L / notes), 1, emptyArray(), "reconnect download probe $i"), setOf(relay)) }
                delay(300)
            }
            delay(2_000)
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }

    private suspend fun probe(url: String): String {
        val relay = RelayUrlNormalizer.normalize(url)
        if (relay in publishTo) fill(relay)
        val http =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        val traffic = Traffic().apply { delegate = BasicOkHttpWebSocket.Builder { http } }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client = NostrClient(traffic, scope)
        val lens = if (relay.url.contains("search.brainstorm.world")) "include:spam" else null
        // An app's home feed: the last six hours of notes, reposts and reactions, capped where an app caps it.
        val filters = listOf(Filter(kinds = listOf(1, 6, 7), since = TimeUtils.now() - 6 * 3600, limit = 5_000, search = lens))
        val before = DynamicSubscription(client, { mapOf(relay to filters) }, resume = null)
        val after = DynamicSubscription(client, { mapOf(relay to filters) }, resume = CaughtUpResume())
        try {
            if (!waitUntil(60_000) { traffic.eoseCount(before.subId) > 0 && traffic.eoseCount(after.subId) > 0 }) {
                return "RESULT ${relay.url}: no EOSE for both within 60 s"
            }
            val stored = traffic.events(before.subId)
            delay(tailSeconds * 1000)
            val live = traffic.events(before.subId) - stored

            val e0 = traffic.events(before.subId) to traffic.events(after.subId)
            val b0 = traffic.bytes(before.subId) to traffic.bytes(after.subId)
            val dropAt = System.currentTimeMillis()
            traffic.dropAll()
            val eosed = waitUntil(120_000) { traffic.eoseCount(before.subId) > 1 && traffic.eoseCount(after.subId) > 1 }
            delay(500)

            fun ms(sub: String) = traffic.eoses[sub]?.getOrNull(1)?.minus(dropAt)
            return "RESULT %s: stored %d, live %d in %d s | after the drop, resume off: %d events, %,d bytes, EOSE %s ms | resume on: %d events, %,d bytes, EOSE %s ms%s".format(
                relay.url,
                stored,
                live,
                tailSeconds,
                traffic.events(before.subId) - e0.first,
                traffic.bytes(before.subId) - b0.first,
                ms(before.subId),
                traffic.events(after.subId) - e0.second,
                traffic.bytes(after.subId) - b0.second,
                ms(after.subId),
                if (eosed) "" else " (no second EOSE within 120 s)",
            )
        } finally {
            before.close()
            after.close()
            client.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun reconnectDownloads() {
        if (relays.isEmpty()) return
        runBlocking { relays.map { async(Dispatchers.Default) { probe(it) } }.awaitAll() }.forEach { println(it) }
    }
}
