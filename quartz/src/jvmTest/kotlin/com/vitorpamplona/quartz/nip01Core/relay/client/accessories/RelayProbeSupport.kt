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

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

// Shared by the opt-in probes that run the client against real relays
// (LiveSubscriptionInteropProbe, LiveSubscriptionFailureProbe).

/** A comma-separated environment variable as a list; empty when unset. */
internal fun probeEnv(name: String): List<String> =
    System
        .getenv(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

internal suspend fun waitUntil(
    timeoutMs: Long,
    condition: () -> Boolean,
): Boolean = withTimeoutOrNull(timeoutMs) { while (!condition()) delay(100) } != null

/**
 * Records every REQ the client writes, every CLOSED/NOTICE it reads and when each socket closed
 * or failed, and can drop the sockets. `RELAY_INTEROP_TRACE` prints every frame.
 */
internal class ProbeRecorder(
    private val delegate: WebsocketBuilder,
) : WebsocketBuilder {
    val reqs = CopyOnWriteArrayList<ReqCmd>()
    val notices = CopyOnWriteArrayList<String>()

    /** Epoch milliseconds at which a socket closed or failed. */
    val closedAt = CopyOnWriteArrayList<Long>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val trace = System.getenv("RELAY_INTEROP_TRACE") != null

    override fun build(
        url: NormalizedRelayUrl,
        out: WebSocketListener,
    ): WebSocket {
        val listening =
            object : WebSocketListener by out {
                override suspend fun onMessage(text: String) {
                    if (trace) println("IN  ${System.currentTimeMillis() % 100000} ${text.take(110)}")
                    if (text.startsWith("[\"CLOSED\"") || text.startsWith("[\"NOTICE\"")) notices += text.take(200)
                    out.onMessage(text)
                }

                override fun onClosed(
                    code: Int,
                    reason: String,
                ) {
                    closedAt += System.currentTimeMillis()
                    out.onClosed(code, reason)
                }

                override fun onFailure(
                    t: Throwable,
                    code: Int?,
                    response: String?,
                ) {
                    if (trace) println("FAIL ${System.currentTimeMillis() % 100000} ${t.message}")
                    closedAt += System.currentTimeMillis()
                    out.onFailure(t, code, response)
                }
            }
        val inner = delegate.build(url, listening)
        val socket =
            object : WebSocket by inner {
                override fun send(msg: String): Boolean {
                    if (trace) println("OUT ${System.currentTimeMillis() % 100000} ${msg.take(160)}")
                    if (msg.startsWith("[\"REQ\"")) (runCatching { Command.fromJson(msg) }.getOrNull() as? ReqCmd)?.let { reqs += it }
                    return inner.send(msg)
                }
            }
        sockets += socket
        return socket
    }

    fun dropAll() = sockets.forEach { it.disconnect() }

    /** The REQs of the subscription marked by [limit] on one of its filters. */
    fun reqsOf(limit: Int) = reqs.filter { req -> req.filters.any { it.limit == limit } }
}

/** PASS/FAIL/SKIP lines and notes for one relay or scenario. */
internal class ProbeReport(
    val name: String,
) {
    val checks = mutableListOf<Pair<String, Boolean?>>()
    val notes = mutableListOf<String>()

    fun check(
        name: String,
        ok: Boolean?,
    ) {
        checks += name to ok
    }

    val failed get() = checks.filter { it.second == false }.map { it.first }

    override fun toString() =
        buildString {
            append("RELAY ").append(name).append('\n')
            checks.forEach { (check, ok) ->
                append("  ")
                    .append(
                        when (ok) {
                            null -> "SKIP"
                            true -> "PASS"
                            false -> "FAIL"
                        },
                    ).append("  ")
                    .append(check)
                    .append('\n')
            }
            notes.forEach { append("  note  ").append(it).append('\n') }
        }
}
