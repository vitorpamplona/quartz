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
package com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp

import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.Utf8WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.deliverMessage
import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.Socket
import okio.buffer
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A relay socket that reads each frame as bytes and parses it there, so relay traffic builds no
 * frame-sized `String` per message.
 *
 * OkHttp still dials: the upgrade request goes through the app's [OkHttpClient], with its proxy
 * (Tor), DNS, TLS, timeouts and interceptors, and its `Response.socket` (OkHttp 5.2+, HTTP/1.1)
 * hands back the upgraded connection. On it, this reads and writes RFC 6455 frames itself
 * ([WsFrameReader], [WsFrameWriter]), into buffers shared by every connection
 * ([WsBufferPool]). When the listener is a [Utf8WebSocketListener] (the relay client's is), each
 * text frame is parsed on this session's reader thread, straight from those bytes, and the
 * parsed message is queued for delivery; any other listener gets the frame decoded to text, as
 * [BasicOkHttpWebSocket] gives it.
 *
 * It keeps [WebSocket]'s contract the way [BasicOkHttpWebSocket] does: one terminal report per
 * session, a [disconnect] that reports synchronously and silences the socket, and a relay's
 * messages delivered in order before its end.
 *
 * Differences from OkHttp's own WebSocket:
 * - The dial runs on this session's thread with `Call.execute()`, so it holds no dispatcher slot
 *   (OkHttp's holds one of `maxRequests` for the whole session) and is not under
 *   `maxRequestsPerHost` either.
 * - The app's network interceptors run on the upgrade call (OkHttp skips them for its own
 *   WebSocket calls). One that reads a response body fails the dial.
 * - Messages it sends are never compressed. A server's compressed messages are inflated.
 * - A message over [maxMessageBytes], after inflation, fails the session (close code 1009).
 * - Binary messages are dropped: Nostr is text.
 */
class OkHttpBytesWebSocket(
    val url: NormalizedRelayUrl,
    val httpClient: (NormalizedRelayUrl) -> OkHttpClient,
    val out: WebSocketListener,
    val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
) : WebSocket {
    companion object {
        private const val OPEN = 0
        private const val DRAINING = 1
        private const val CLOSED = 2

        /** The largest message read, after inflation. Relays cap events far below this. */
        const val DEFAULT_MAX_MESSAGE_BYTES = 32 * 1024 * 1024

        /** As OkHttp: past this many bytes waiting to be sent, [send] refuses. */
        private const val MAX_QUEUED_BYTES = 16L * 1024 * 1024

        private const val ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val TAG = "OkHttpBytesWebSocket"

        val exceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Delivery caught an exception", throwable)
            }

        private fun daemon(name: String) =
            ThreadFactory { r ->
                Thread(r, name).apply { isDaemon = true }
            }

        /** Each session's dial and blocking reads, one thread per open session (OkHttp's take one too). */
        private val readers = Executors.newCachedThreadPool(daemon("Quartz WebSocket reader"))

        /** Drains each session's send queue, one task per session at a time. */
        private val writers = Executors.newCachedThreadPool(daemon("Quartz WebSocket writer"))

        private val pinger =
            ScheduledThreadPoolExecutor(1, daemon("Quartz WebSocket pinger")).apply {
                removeOnCancelPolicy = true
            }

        /** Messages queued at once at most, so a long burst starts reaching the listener early. */
        private const val BATCH = 128

        private val keys = SecureRandom()
        private val EMPTY = ByteArray(0)
    }

    private val state = AtomicInteger(OPEN)

    @Volatile private var delivery: Delivery? = null

    @Volatile private var dialing = false

    @Volatile private var dialedWith: OkHttpClient? = null

    /** The upgrade call, set by [connect]: from then on the session exists. */
    @Volatile private var call: Call? = null

    /** The upgraded connection, once the relay accepted it. */
    @Volatile private var upgraded: Socket? = null

    @Volatile private var writer: WsFrameWriter? = null

    @Volatile private var pingTask: ScheduledFuture<*>? = null

    /** Why this side broke the session (a missed pong, a failed write), reported over the read error it causes. */
    @Volatile private var brokenBy: IOException? = null

    @Volatile private var awaitingPong = false
    private val pongs = AtomicInteger(0)

    private class Outgoing(
        val opcode: Int,
        val payload: ByteArray,
    )

    private val sendQueue = ConcurrentLinkedQueue<Outgoing>()
    private val queuedBytes = AtomicLong(0)
    private val drainScheduled = AtomicBoolean(false)

    private class Delivery(
        val scope: CoroutineScope,
        // Batches of messages ([Decoded] or String); a [SessionEnd] is the last item.
        val incoming: Channel<Any>,
    )

    /**
     * Messages read but not yet queued: the reader queues them as one batch right before a read
     * that has to wait for the network ([WsFrameReader.Callback.onWouldBlock]), or at [BATCH]. Queuing each one woke the delivering coroutine for each
     * message (it is faster than the reader and parks between them), and those wakeups cost more
     * CPU than the parse. Only the reader thread touches it.
     */
    private var pending = ArrayList<Any>(BATCH)

    private class Decoded(
        val msg: Message,
        val frameBytes: Int,
    )

    private class SessionEnd(
        val report: () -> Unit,
    )

    /** As [BasicOkHttpWebSocket.needsReconnect]. */
    override fun needsReconnect() =
        when (state.get()) {
            OPEN -> if (call == null) !dialing else clientChanged()
            DRAINING -> false
            else -> true
        }

    private fun clientChanged(): Boolean {
        val used = dialedWith ?: return false
        val current = httpClient(url)
        if (current === used) return false
        return used.proxy != current.proxy ||
            used.readTimeoutMillis != current.readTimeoutMillis ||
            used.writeTimeoutMillis != current.writeTimeoutMillis ||
            used.connectTimeoutMillis != current.connectTimeoutMillis ||
            used.callTimeoutMillis != current.callTimeoutMillis
    }

    private fun endDelivery() {
        delivery?.let {
            it.incoming.cancel()
            it.scope.cancel()
        }
    }

    override fun connect() {
        if (call != null || dialing || state.get() != OPEN) return
        dialing = true

        val scope = CoroutineScope(Dispatchers.IO + exceptionHandler)
        // Unbounded, as in BasicOkHttpWebSocket: the reader must keep draining the relay.
        val incoming = Channel<Any>(Channel.UNLIMITED)
        delivery = Delivery(scope, incoming)
        scope.launch {
            for (item in incoming) {
                if (item is SessionEnd) {
                    if (state.compareAndSet(DRAINING, CLOSED)) item.report()
                    break
                }
                @Suppress("UNCHECKED_CAST")
                val batch = item as ArrayList<Any>
                for (k in batch.indices) {
                    val message = batch[k]
                    // A listener that throws must not end delivery, as in BasicOkHttpWebSocket.
                    try {
                        if (message is Decoded) {
                            (out as Utf8WebSocketListener).onDecodedMessage(message.msg, message.frameBytes)
                        } else {
                            out.deliverMessage(message as String)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Listener failed on a message from ${url.url}", e)
                    }
                }
            }
            scope.cancel()
        }

        try {
            val client = httpClient(url)
            dialedWith = client
            val key = ByteArray(16).also { keys.nextBytes(it) }.toByteString().base64()
            val request =
                Request
                    .Builder()
                    .url(url.url)
                    .header("Upgrade", "websocket")
                    .header("Connection", "Upgrade")
                    .header("Sec-WebSocket-Key", key)
                    .header("Sec-WebSocket-Version", "13")
                    .header("Sec-WebSocket-Extensions", "permessage-deflate")
                    .build()
            // HTTP/1.1 only: an upgrade cannot ride HTTP/2, which ALPN would pick for wss.
            val upgradeCall =
                client
                    .newBuilder()
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .build()
                    .newCall(request)
            call = upgradeCall
            // A disconnect() that landed while dialing found no call to cancel.
            if (state.get() != OPEN) {
                upgradeCall.cancel()
                endDelivery()
                return
            }
            readers.execute { session(upgradeCall, client, key, incoming) }
            // Or one landing right now: the session sees it, or the cancel ends its dial.
            if (state.get() != OPEN) upgradeCall.cancel()
        } catch (e: Throwable) {
            endDelivery()
            throw e
        } finally {
            dialing = false
        }
    }

    /** The relay (or the network) ended the session: deliver what came, then report [report]. */
    private fun endedByRelay(
        incoming: Channel<Any>,
        report: () -> Unit,
    ) {
        if (!state.compareAndSet(OPEN, DRAINING)) return
        incoming.trySend(SessionEnd(report))
        incoming.close()
    }

    private fun session(
        upgradeCall: Call,
        client: OkHttpClient,
        key: String,
        incoming: Channel<Any>,
    ) {
        val thread = Thread.currentThread()
        val name = thread.name
        thread.name = "Quartz WebSocket ${url.url}"
        try {
            runSession(upgradeCall, client, key, incoming)
        } finally {
            thread.name = name
        }
    }

    private fun runSession(
        upgradeCall: Call,
        client: OkHttpClient,
        key: String,
        incoming: Channel<Any>,
    ) {
        val response =
            try {
                upgradeCall.execute()
            } catch (e: Throwable) {
                // Not only IOException: an interceptor may throw anything, and a session that
                // never reports its end is never redialed.
                endedByRelay(incoming) { out.onFailure(e, null, null) }
                if (e is Error) throw e
                return
            }

        val socket: Socket
        val extensions: WsExtensions
        try {
            socket = checkUpgrade(response, key)
            extensions = WsExtensions.parse(response.headers("Sec-WebSocket-Extensions"))
        } catch (e: IOException) {
            response.socket?.cancel()
            response.close()
            endedByRelay(incoming) { out.onFailure(e, response.code, response.message) }
            return
        }

        upgraded = socket
        val source = socket.source.buffer()
        val sink = socket.sink.buffer()
        val reader = WsFrameReader(source, extensions, maxMessageBytes)
        var closedWith: Pair<Int, String>? = null
        try {
            // A disconnect() while dialing: its cancel may have missed the socket.
            if (state.get() != OPEN) return
            writer = WsFrameWriter(sink)
            startPings(client.pingIntervalMillis)
            out.onOpen((response.receivedResponseAtMillis - response.sentRequestAtMillis).toInt(), extensions.perMessageDeflate)
            // REQs sent from onOpen, or before it, go out now.
            scheduleDrain()

            val callback =
                object : WsFrameReader.Callback {
                    override fun onText(
                        bytes: ByteArray,
                        length: Int,
                    ) = receive(bytes, length)

                    override fun onPing(payload: ByteString) = enqueue(Outgoing(WsOpcode.PONG, payload.toByteArray()))

                    override fun onPong() {
                        awaitingPong = false
                        pongs.incrementAndGet()
                    }

                    // About to wait on the network: hand over what was read.
                    override fun onWouldBlock() = flush(incoming)

                    override fun onClose(
                        code: Int,
                        reason: String,
                    ) {
                        // Answer the relay's CLOSE, completing the handshake, then hang up.
                        try {
                            writer?.writeClose(1000, null)
                        } catch (_: IOException) {
                            // It is ending anyway.
                        }
                        closedWith = code to reason
                    }
                }

            while (state.get() == OPEN && closedWith == null) {
                reader.readMessage(callback)
                if (pending.size >= BATCH) flush(incoming)
            }
            flush(incoming)
            closedWith?.let { (code, reason) -> endedByRelay(incoming) { out.onClosed(code, reason) } }
        } catch (e: Throwable) {
            // A read error, a protocol error, the listener's onOpen throwing, as OkHttp reports
            // them; and an Error (out of memory for a huge frame), which must still end the
            // session, or it is never redialed.
            flush(incoming)
            val cause = brokenBy ?: e
            endedByRelay(incoming) { out.onFailure(cause, null, null) }
            if (e is Error) throw e
        } finally {
            pingTask?.cancel(false)
            pingTask = null
            writer = null
            sendQueue.clear()
            queuedBytes.set(0)
            socket.cancel()
            runCatching { source.close() }
            runCatching { sink.close() }
            reader.close()
            upgraded = null
        }
    }

    /** A whole text message, on the reader thread: parsed here when the listener can take bytes, then held in [pending]. */
    private fun receive(
        bytes: ByteArray,
        length: Int,
    ) {
        if (state.get() != OPEN) return
        val listener = out
        if (listener is Utf8WebSocketListener) {
            val msg =
                try {
                    listener.decodeUtf8(bytes, 0, length)
                } catch (e: Throwable) {
                    Log.e(TAG, "Decoder failed on a message from ${url.url}", e)
                    null
                } ?: return
            pending.add(Decoded(msg, length))
        } else {
            pending.add(bytes.decodeToString(0, length))
        }
    }

    /** Queues [pending] for delivery, in order, as one item. */
    private fun flush(incoming: Channel<Any>) {
        if (pending.isEmpty() || state.get() != OPEN) return
        val batch = pending
        pending = ArrayList(BATCH)
        incoming.trySendBlocking(batch)
    }

    /** As OkHttp's `RealWebSocket.checkUpgradeSuccess`. */
    private fun checkUpgrade(
        response: Response,
        key: String,
    ): Socket {
        if (response.code != 101) {
            throw ProtocolException("Expected HTTP 101 response but was '${response.code} ${response.message}'")
        }
        val connection = response.header("Connection")
        if (!"Upgrade".equals(connection, ignoreCase = true)) {
            throw ProtocolException("Expected 'Connection' header value 'Upgrade' but was '$connection'")
        }
        val upgrade = response.header("Upgrade")
        if (!"websocket".equals(upgrade, ignoreCase = true)) {
            throw ProtocolException("Expected 'Upgrade' header value 'websocket' but was '$upgrade'")
        }
        val accept = response.header("Sec-WebSocket-Accept")
        val expected = (key + ACCEPT_MAGIC).encodeUtf8().sha1().base64()
        if (expected != accept) {
            throw ProtocolException("Expected 'Sec-WebSocket-Accept' header value '$expected' but was '$accept'")
        }
        return response.socket ?: throw ProtocolException("Web Socket socket missing: bad interceptor?")
    }

    private fun startPings(intervalMillis: Int) {
        if (intervalMillis <= 0) return
        val interval = intervalMillis.toLong()
        pingTask = pinger.scheduleAtFixedRate({ ping(intervalMillis) }, interval, interval, TimeUnit.MILLISECONDS)
    }

    private fun ping(intervalMillis: Int) {
        if (state.get() != OPEN) return
        if (awaitingPong) {
            breakSession(SocketTimeoutException("sent ping but didn't receive pong within ${intervalMillis}ms (after ${pongs.get()} successful ping/pongs)"))
            return
        }
        awaitingPong = true
        enqueue(Outgoing(WsOpcode.PING, EMPTY))
    }

    /** Ends the session from this side: the reader's blocked read fails, and reports [cause]. */
    private fun breakSession(cause: IOException) {
        if (brokenBy == null) brokenBy = cause
        upgraded?.cancel()
    }

    override fun send(msg: String): Boolean {
        if (state.get() != OPEN || call == null) return false
        val bytes = msg.encodeToByteArray()
        if (queuedBytes.addAndGet(bytes.size.toLong()) > MAX_QUEUED_BYTES) {
            queuedBytes.addAndGet(-bytes.size.toLong())
            return false
        }
        enqueue(Outgoing(WsOpcode.TEXT, bytes))
        return true
    }

    private fun enqueue(frame: Outgoing) {
        sendQueue.offer(frame)
        scheduleDrain()
    }

    /** Writes run on [writers], one drain per session at a time, so [send] never blocks. */
    private fun scheduleDrain() {
        if (writer == null || sendQueue.isEmpty()) return
        if (drainScheduled.compareAndSet(false, true)) writers.execute(::drain)
    }

    private fun drain() {
        try {
            while (state.get() == OPEN) {
                val w = writer ?: break
                val frame = sendQueue.poll() ?: break
                if (frame.opcode == WsOpcode.TEXT) queuedBytes.addAndGet(-frame.payload.size.toLong())
                try {
                    if (frame.opcode == WsOpcode.TEXT) w.writeText(frame.payload) else w.writeControl(frame.opcode, frame.payload)
                } catch (e: IOException) {
                    breakSession(e)
                    break
                }
            }
        } finally {
            drainScheduled.set(false)
            // A frame queued while this drain was finishing.
            if (state.get() == OPEN) scheduleDrain()
        }
    }

    /** As [BasicOkHttpWebSocket.disconnect]: claims the session, silences it, reports now. */
    override fun disconnect() {
        val previous = state.getAndSet(CLOSED)
        if (previous == CLOSED) return
        val started = call
        endDelivery()
        started?.cancel()
        upgraded?.cancel()
        if (started != null || previous == DRAINING) out.onClosed(1000, "client disconnect")
    }

    class Builder(
        val httpClient: (NormalizedRelayUrl) -> OkHttpClient,
        val canDial: (NormalizedRelayUrl) -> Boolean,
        val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    ) : WebsocketBuilder {
        // As BasicOkHttpWebSocket.Builder: `Builder { okHttp }` binds its lambda to httpClient.
        constructor(httpClient: (NormalizedRelayUrl) -> OkHttpClient) : this(httpClient, { true })

        override fun build(
            url: NormalizedRelayUrl,
            out: WebSocketListener,
        ): WebSocket = OkHttpBytesWebSocket(url, httpClient, out, maxMessageBytes)

        override fun canConnect(url: NormalizedRelayUrl) = canDial(url)
    }
}
