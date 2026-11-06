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

import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CachingEventDecoder
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.Utf8WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import okhttp3.OkHttpClient
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [BasicOkHttpWebSocket]'s session contract, held against [OkHttpBytesWebSocket]. */
class OkHttpBytesWebSocketDrainContractTest : BasicOkHttpWebSocketDrainTest() {
    override fun newSocket(
        url: NormalizedRelayUrl,
        httpClient: (NormalizedRelayUrl) -> OkHttpClient,
        out: WebSocketListener,
    ): WebSocket = OkHttpBytesWebSocket(url, httpClient, out)
}

class OkHttpBytesWebSocketCloseHandshakeContractTest : BasicOkHttpWebSocketCloseHandshakeTest() {
    override fun newSocket(
        url: NormalizedRelayUrl,
        httpClient: (NormalizedRelayUrl) -> OkHttpClient,
        out: WebSocketListener,
    ): WebSocket = OkHttpBytesWebSocket(url, httpClient, out)
}

class OkHttpBytesWebSocketDialRaceContractTest : BasicOkHttpWebSocketDialRaceTest() {
    override fun newSocket(
        url: NormalizedRelayUrl,
        httpClient: (NormalizedRelayUrl) -> OkHttpClient,
        out: WebSocketListener,
    ): WebSocket = OkHttpBytesWebSocket(url, httpClient, out)

    // Overrides OkHttpClient.newWebSocket, which this socket never calls; its own form of the
    // race (onOpen before connect() returns) is OkHttpBytesWebSocketTest's sendFromOnOpen.
    override fun whatIsSentFromOnOpenGoesOutWhenTheSocketOpensBeforeTheDialReturns() {}
}

/** Framing, the relay client's byte path, and failures, against [ScriptedRelay]. */
class OkHttpBytesWebSocketTest {
    private val client = OkHttpClient()

    @AfterTest
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private open class Recorder : WebSocketListener {
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val compression = AtomicReference<Boolean?>(null)
        val closed = AtomicReference<Pair<Int, String>?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val failureCode = AtomicReference<Int?>(null)
        val reports = AtomicInteger(0)

        fun awaitMessages(
            count: Int,
            seconds: Long = 10,
        ): Boolean {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (messages.size < count && System.nanoTime() < until) Thread.sleep(5)
            return messages.size >= count
        }

        override fun onOpen(
            pingMillis: Int,
            compression: Boolean,
        ) {
            this.compression.set(compression)
            opened.countDown()
        }

        override suspend fun onMessage(text: String) {
            messages.add(text)
        }

        override fun onClosed(
            code: Int,
            reason: String,
        ) {
            closed.set(code to reason)
            reports.incrementAndGet()
            ended.countDown()
        }

        override fun onFailure(
            t: Throwable,
            code: Int?,
            response: String?,
        ) {
            failure.set(t)
            failureCode.set(code)
            reports.incrementAndGet()
            ended.countDown()
        }
    }

    private fun dial(
        relay: ScriptedRelay,
        listener: WebSocketListener,
        okhttp: OkHttpClient = client,
        maxMessageBytes: Int = OkHttpBytesWebSocket.DEFAULT_MAX_MESSAGE_BYTES,
    ) = OkHttpBytesWebSocket(relay.url, { okhttp }, listener, maxMessageBytes).also { it.connect() }

    @Test
    fun everyLengthEncodingArrivesWhole() {
        val texts = listOf("", "a", "x".repeat(125), "y".repeat(126), "z".repeat(65_535), "w".repeat(65_536), "café 😀 ".repeat(30_000))
        ScriptedRelay {
            texts.forEach { text(it) }
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(texts.size))
            assertEquals(texts, recorder.messages.toList())
            assertEquals(false, recorder.compression.get())
            socket.disconnect()
        }
    }

    @Test
    fun fragmentsAreJoinedAndAPingBetweenThemIsAnswered() {
        val pong = AtomicReference<ScriptedRelay.Frame?>(null)
        ScriptedRelay {
            frame(1, "[\"NOTICE\",".encodeToByteArray(), fin = false)
            frame(9, "are you there".encodeToByteArray())
            frame(0, "\"fragm".encodeToByteArray(), fin = false)
            frame(0, "ented\"]".encodeToByteArray())
            text("after")
            pong.set(read())
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(2))
            assertEquals(listOf("[\"NOTICE\",\"fragmented\"]", "after"), recorder.messages.toList())
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (pong.get() == null && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(10, pong.get()?.opcode, "a PONG")
            assertEquals("are you there", pong.get()?.text)
            socket.disconnect()
        }
    }

    @Test
    fun compressedMessagesKeepTheirContextAcrossMessages() {
        val texts = (1..20).map { "[\"EVENT\",\"s\",{\"content\":\"hello é $it ${"repeat ".repeat(it * 50)}\"}]" }
        ScriptedRelay(extensions = "permessage-deflate") {
            check(requestHeaders.any { it.equals("Sec-WebSocket-Extensions: permessage-deflate", ignoreCase = true) })
            texts.forEach { compressed(it) }
            // A compressed message in two fragments: only the first carries rsv1.
            val bytes = deflate("fragmented and compressed".encodeToByteArray())
            frame(1, bytes.copyOfRange(0, 3), fin = false, rsv1 = true)
            frame(0, bytes.copyOfRange(3, bytes.size))
            text("plain after")
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(texts.size + 2))
            assertEquals(texts + "fragmented and compressed" + "plain after", recorder.messages.toList())
            assertEquals(true, recorder.compression.get())
            socket.disconnect()
        }
    }

    @Test
    fun serverNoContextTakeoverResetsTheInflaterEachMessage() {
        val texts = (1..5).map { "message $it ".repeat(100) }
        ScriptedRelay(extensions = "permessage-deflate; server_no_context_takeover; client_no_context_takeover") {
            texts.forEach { compressed(it, noContext = true) }
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(texts.size))
            assertEquals(texts, recorder.messages.toList())
            socket.disconnect()
        }
    }

    @Test
    fun aMessageOverTheLimitFailsTheSession() {
        ScriptedRelay {
            text("small")
            text("x".repeat(2_000))
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            dial(relay, recorder, maxMessageBytes = 1_000)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("small"), recorder.messages.toList())
            assertIs<ProtocolException>(recorder.failure.get())
        }
    }

    @Test
    fun aMaskedServerFrameIsAProtocolError() {
        ScriptedRelay {
            frame(1, "masked".encodeToByteArray(), masked = true)
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            dial(relay, recorder)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertIs<ProtocolException>(recorder.failure.get())
            assertTrue(recorder.messages.isEmpty())
        }
    }

    @Test
    fun aRelayCloseIsAnsweredAndReportedWithItsCodeAndReason() {
        val answer = AtomicReference<ScriptedRelay.Frame?>(null)
        ScriptedRelay {
            text("last words")
            close(4000, "bye é")
            answer.set(read())
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("last words"), recorder.messages.toList(), "messages before the close are delivered first")
            assertEquals(4000 to "bye é", recorder.closed.get())
            assertTrue(relay.awaitDone())
            assertEquals(8, answer.get()?.opcode)
            assertEquals(1000, answer.get()?.closeCode)
            socket.disconnect()
            assertEquals(1, recorder.reports.get(), "one terminal report")
            assertTrue(socket.needsReconnect())
        }
    }

    @Test
    fun aRefusedUpgradeIsAFailureWithItsStatus() {
        ScriptedRelay(status = "503 Service Unavailable") {}.use { relay ->
            val recorder = Recorder()
            dial(relay, recorder)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertIs<ProtocolException>(recorder.failure.get())
            assertEquals(503, recorder.failureCode.get())
        }
    }

    @Test
    fun aWrongAcceptIsAFailure() {
        ScriptedRelay(badAccept = true) { awaitHangUp() }.use { relay ->
            val recorder = Recorder()
            dial(relay, recorder)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertTrue(
                recorder.failure
                    .get()
                    ?.message
                    ?.contains("Sec-WebSocket-Accept") == true,
            )
            assertEquals(1L, recorder.opened.count, "never opened")
        }
    }

    @Test
    fun anExtensionNeverOfferedFailsTheSession() {
        ScriptedRelay(extensions = "x-webkit-deflate-frame") { awaitHangUp() }.use { relay ->
            val recorder = Recorder()
            dial(relay, recorder)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertIs<ProtocolException>(recorder.failure.get())
        }
    }

    @Test
    fun anInterceptorThatThrowsIsAFailureNotAStuckSession() {
        ScriptedRelay { awaitHangUp() }.use { relay ->
            val okhttp = client.newBuilder().addInterceptor { error("an interceptor bug") }.build()
            val recorder = Recorder()
            val socket = dial(relay, recorder, okhttp)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS), "the session must end, or it is never redialed")
            assertIs<IllegalStateException>(recorder.failure.get())
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!socket.needsReconnect() && System.nanoTime() < until) Thread.sleep(5)
            assertTrue(socket.needsReconnect())
        }
    }

    @Test
    fun aMessageReadBeforeAPartialFrameIsNotHeldBackByIt() {
        val firstDelivered = CountDownLatch(1)
        val deliveredBeforeTheRest = AtomicReference<Boolean?>(null)
        ScriptedRelay {
            // One write: a whole frame, then the header and half of the next one.
            raw(byteArrayOf(0x81.toByte(), 5) + "first".encodeToByteArray() + byteArrayOf(0x81.toByte(), 10) + "01234".encodeToByteArray())
            deliveredBeforeTheRest.set(firstDelivered.await(3, TimeUnit.SECONDS))
            raw("56789".encodeToByteArray())
            awaitHangUp()
        }.use { relay ->
            val recorder =
                object : Recorder() {
                    override suspend fun onMessage(text: String) {
                        super.onMessage(text)
                        if (text == "first") firstDelivered.countDown()
                    }
                }
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(2))
            assertEquals(true, deliveredBeforeTheRest.get(), "\"first\" waited for the rest of the next frame")
            assertEquals(listOf("first", "0123456789"), recorder.messages.toList())
            socket.disconnect()
        }
    }

    @Test
    fun anIdleRelayIsNotAReadTimeout() {
        ScriptedRelay {
            text("before")
            Thread.sleep(1_000)
            text("after a quiet second")
            awaitHangUp()
        }.use { relay ->
            val okhttp = client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val recorder = Recorder()
            val socket = dial(relay, recorder, okhttp)
            assertTrue(recorder.awaitMessages(2))
            assertEquals(listOf("before", "after a quiet second"), recorder.messages.toList())
            assertEquals(1L, recorder.ended.count, "still open")
            socket.disconnect()
        }
    }

    @Test
    fun aStalledFrameIsAReadTimeout() {
        ScriptedRelay {
            // A header that promises 10 bytes, then only 3 of them.
            raw(byteArrayOf(0x81.toByte(), 10, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
            awaitHangUp()
        }.use { relay ->
            val okhttp = client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
            val recorder = Recorder()
            dial(relay, recorder, okhttp)
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertIs<SocketTimeoutException>(recorder.failure.get())
        }
    }

    @Test
    fun aMissedPongFailsTheSession() {
        val pinged = CountDownLatch(1)
        ScriptedRelay {
            // Reads the client's pings and never answers.
            if (read().opcode == 9) pinged.countDown()
            awaitHangUp()
        }.use { relay ->
            val okhttp = client.newBuilder().pingInterval(200, TimeUnit.MILLISECONDS).build()
            val recorder = Recorder()
            dial(relay, recorder, okhttp)
            assertTrue(pinged.await(5, TimeUnit.SECONDS), "the client pings")
            assertTrue(recorder.ended.await(5, TimeUnit.SECONDS))
            assertIs<SocketTimeoutException>(recorder.failure.get())
        }
    }

    @Test
    fun answeredPingsKeepTheSessionOpen() {
        val pings = AtomicInteger(0)
        val stop = CountDownLatch(1)
        ScriptedRelay {
            while (stop.count > 0) {
                val f = runCatching { read() }.getOrNull() ?: break
                if (f.opcode == 9) {
                    pings.incrementAndGet()
                    frame(10, f.payload)
                }
            }
        }.use { relay ->
            val okhttp = client.newBuilder().pingInterval(100, TimeUnit.MILLISECONDS).build()
            val recorder = Recorder()
            val socket = dial(relay, recorder, okhttp)
            Thread.sleep(1_000)
            assertTrue(pings.get() >= 5, "pinged ${pings.get()} times")
            assertEquals(1L, recorder.ended.count, "still open")
            stop.countDown()
            socket.disconnect()
        }
    }

    @Test
    fun sentTextIsMaskedUtf8AndSentFromOnOpenToo() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        ScriptedRelay {
            repeat(3) { received.add(read().text) }
            awaitHangUp()
        }.use { relay ->
            lateinit var socket: WebSocket
            val fromOpen = AtomicReference<Boolean?>(null)
            val recorder =
                object : Recorder() {
                    override fun onOpen(
                        pingMillis: Int,
                        compression: Boolean,
                    ) {
                        fromOpen.set(socket.send("[\"REQ\",\"open\",{}]"))
                        super.onOpen(pingMillis, compression)
                    }
                }
            socket = OkHttpBytesWebSocket(relay.url, { client }, recorder)
            socket.connect()
            assertTrue(recorder.opened.await(5, TimeUnit.SECONDS))
            assertEquals(true, fromOpen.get())
            assertTrue(socket.send("[\"REQ\",\"café\",{}]"))
            assertTrue(socket.send("x".repeat(70_000)))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (received.size < 3 && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(listOf("[\"REQ\",\"open\",{}]", "[\"REQ\",\"café\",{}]", "x".repeat(70_000)), received.toList())
            socket.disconnect()
            assertEquals(false, socket.send("late"), "nothing is sent after disconnect")
        }
    }

    @Test
    fun binaryMessagesAreDropped() {
        ScriptedRelay {
            frame(2, byteArrayOf(1, 2, 3))
            text("text")
            awaitHangUp()
        }.use { relay ->
            val recorder = Recorder()
            val socket = dial(relay, recorder)
            assertTrue(recorder.awaitMessages(1))
            Thread.sleep(100)
            assertEquals(listOf("text"), recorder.messages.toList())
            socket.disconnect()
        }
    }

    @Test
    fun aUtf8ListenerGetsBytesOnTheReaderThreadAndMessagesInOrder() {
        val frames = (0 until 200).map { "[\"NOTICE\",\"né $it\"]" }
        ScriptedRelay {
            frames.forEach { text(it) }
            awaitHangUp()
        }.use { relay ->
            val decodedOn = Collections.synchronizedSet(mutableSetOf<String>())
            val sizes = Collections.synchronizedList(mutableListOf<Int>())
            val texts = Collections.synchronizedList(mutableListOf<String>())
            val plainCalls = AtomicInteger(0)
            val listener =
                object : Recorder(), Utf8WebSocketListener {
                    override fun decodeUtf8(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Message {
                        decodedOn.add(Thread.currentThread().name)
                        return NoticeMessage(bytes.decodeToString(offset, offset + length))
                    }

                    override suspend fun onDecodedMessage(
                        msg: Message,
                        frameBytes: Int,
                    ) {
                        texts.add((msg as NoticeMessage).message)
                        sizes.add(frameBytes)
                    }

                    override suspend fun onMessage(text: String) {
                        plainCalls.incrementAndGet()
                    }
                }
            val socket = dial(relay, listener)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (texts.size < frames.size && System.nanoTime() < until) Thread.sleep(5)
            assertEquals(frames, texts.toList())
            assertEquals(frames.map { it.encodeToByteArray().size }, sizes.toList())
            assertEquals(0, plainCalls.get(), "a byte listener never gets text")
            assertTrue(decodedOn.all { it.startsWith("Quartz WebSocket ") }, "decoded on the session's reader thread: $decodedOn")
            socket.disconnect()
        }
    }

    // ---- the relay client's byte path

    private fun eventJson(
        n: Int,
        content: String,
    ): String {
        val id = n.toString(16).padStart(64, '0')
        return """{"content":"$content","created_at":1700000000,"id":"$id","kind":1,"pubkey":"${"ab".repeat(32)}","sig":"${"cd".repeat(64)}","tags":[["t","x"]]}"""
    }

    private class RelayRecorder : RelayConnectionListener {
        val viaBytes: MutableList<Pair<Message, Int>> = Collections.synchronizedList(mutableListOf())
        val viaText = AtomicInteger(0)
        val connected = CountDownLatch(1)

        override fun onConnected(
            relay: IRelayClient,
            pingMillis: Int,
            compressed: Boolean,
        ) = connected.countDown()

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msgStr: String,
            msg: Message,
        ) {
            viaText.incrementAndGet()
        }

        override suspend fun onIncomingMessage(
            relay: IRelayClient,
            msg: Message,
            frameBytes: Int,
        ) {
            viaBytes.add(msg to frameBytes)
        }
    }

    @Test
    fun theRelayClientReadsFramesFromBytesAndTheCacheCatchesDuplicates() {
        val e1 = eventJson(1, "first café")
        val e2 = eventJson(2, "second \\n line")
        val frames =
            listOf(
                """["EVENT","a",$e1]""",
                """["EVENT","b",$e2]""",
                """["EVENT","c",$e1]""",
                """["EOSE","a"]""",
                """["EVENT","a",{"broken":""",
                """["NOTICE","still here"]""",
            )
        ScriptedRelay {
            frames.forEach { text(it) }
            awaitHangUp()
        }.use { relay ->
            val recorder = RelayRecorder()
            val decoder = CachingEventDecoder()
            val relayClient = BasicRelayClient(relay.url, OkHttpBytesWebSocket.Builder { client }, recorder, decoder = decoder)
            relayClient.connect()
            assertTrue(recorder.connected.await(5, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (recorder.viaBytes.size < 5 && System.nanoTime() < until) Thread.sleep(5)
            relayClient.disconnect()

            val got = recorder.viaBytes.toList()
            assertEquals(5, got.size, "the malformed frame is dropped: $got")
            assertEquals(0, recorder.viaText.get(), "no text path")
            val events = got.take(3).map { it.first as EventMessage }
            assertEquals(listOf("a", "b", "c"), events.map { it.subId })
            assertEquals(listOf("first café", "second \n line", "first café"), events.map { it.event.content })
            assertTrue(events[0].event === events[2].event, "the duplicate is the cached Event")
            assertEquals(1L, decoder.reusedCount)
            assertEquals(frames.take(3).map { it.encodeToByteArray().size }, got.take(3).map { it.second })
            assertIs<NoticeMessage>(got[4].first)
        }
    }

    @Test
    fun aListenerOnlyOverridingTheTextOverloadStillGetsEveryMessage() {
        ScriptedRelay {
            text("""["EVENT","a",${eventJson(7, "x")}]""")
            text("""["NOTICE","n"]""")
            awaitHangUp()
        }.use { relay ->
            val got = Collections.synchronizedList(mutableListOf<Pair<String, Message>>())
            val connected = CountDownLatch(1)
            val legacy =
                object : RelayConnectionListener {
                    override fun onConnected(
                        relay: IRelayClient,
                        pingMillis: Int,
                        compressed: Boolean,
                    ) = connected.countDown()

                    override suspend fun onIncomingMessage(
                        relay: IRelayClient,
                        msgStr: String,
                        msg: Message,
                    ) {
                        got.add(msgStr to msg)
                    }
                }
            val relayClient = BasicRelayClient(relay.url, OkHttpBytesWebSocket.Builder { client }, legacy)
            relayClient.connect()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (got.size < 2 && System.nanoTime() < until) Thread.sleep(5)
            relayClient.disconnect()
            assertEquals(2, got.size)
            // The documented cost of not migrating: the text is empty.
            assertEquals(listOf("", ""), got.map { it.first })
            assertIs<EventMessage>(got[0].second)
            assertEquals("x", (got[0].second as EventMessage).event.content)
        }
    }
}
