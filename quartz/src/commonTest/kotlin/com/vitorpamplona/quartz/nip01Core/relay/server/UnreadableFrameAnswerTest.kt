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
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.RelayLimits
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A REQ, COUNT or CLOSE the relay cannot use, because it does not parse, has no filter or is
 * refused before parsing, is still answered on its sub id with `CLOSED` (NIP-01, NIP-45): a
 * NOTICE carries no sub id, so the client would wait for that REQ's EOSE forever. A REQ answered
 * so also ends the subscription it was sent to replace. Only a frame with no readable id gets a
 * NOTICE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadableFrameAnswerTest {
    private val pubkey = "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d"
    private val sig = "4aa5264965018fa12a326686ad3d3bd8beae3218dcc83689b19ca1e6baeb791531943c15363aa6707c7c0c8b2d601deca1f20c32078b2872d356cdca03b04cce"

    private fun event(n: Int) = Event(n.toString().padStart(64, '0'), pubkey, 1000L + n, 1, emptyArray(), "hello $n", sig)

    private class Frames : SessionSink {
        val messages = mutableListOf<Message>()

        override fun message(message: Message) {
            messages.add(message)
        }

        val raw = mutableListOf<String>()

        override fun raw(json: String) {
            raw.add(json)
        }

        fun closed() = messages.filterIsInstance<ClosedMessage>().map { it.subId to it.message }

        fun notices() = messages.filterIsInstance<NoticeMessage>().map { it.message }

        fun events() = messages.filterIsInstance<EventMessage>().map { it.toJson() } + raw.filter { it.startsWith("[\"EVENT\"") }

        fun clear() {
            messages.clear()
            raw.clear()
        }
    }

    private fun TestScope.server(limits: RelayLimits? = null) =
        NostrServer(
            EventStore(null),
            policyBuilder = { EmptyPolicy },
            parentContext = UnconfinedTestDispatcher(testScheduler),
            limits = limits,
        )

    @Test
    fun aReqThatDoesNotParseIsClosedOnItsSubId() =
        runTest {
            server().use { server ->
                val frames = Frames()
                val session = server.connect(frames)
                frames.clear()

                for (frame in listOf(
                    """["REQ","a",{"limit":"10"}]""",
                    """["REQ","b",{"kinds":[99999999999]}]""",
                    """["REQ","c",5]""",
                    """["REQ","d\"e",{"since":"yesterday"}]""",
                )) {
                    session.receive(frame)
                }

                assertEquals(emptyList(), frames.notices())
                assertEquals(listOf("a", "b", "c", "d\"e"), frames.closed().map { it.first })
                assertTrue(frames.closed().all { it.second.startsWith("invalid:") }, "${frames.closed()}")
            }
        }

    @Test
    fun aCountThatDoesNotParseIsClosedOnItsQueryId() =
        runTest {
            server().use { server ->
                val frames = Frames()
                val session = server.connect(frames)
                frames.clear()

                session.receive("""["COUNT","q",{"limit":"10"}]""")

                assertEquals(emptyList(), frames.notices())
                assertEquals(listOf("q"), frames.closed().map { it.first })
                assertTrue(
                    frames
                        .closed()
                        .single()
                        .second
                        .startsWith("invalid:"),
                )
            }
        }

    @Test
    fun aReqOrCountWithoutFiltersIsClosedOnItsId() =
        runTest {
            server().use { server ->
                val frames = Frames()
                val session = server.connect(frames)
                frames.clear()

                session.receive("""["REQ","x"]""")
                session.receive("""["COUNT","q"]""")

                assertEquals(emptyList(), frames.notices())
                assertEquals(listOf("x", "q"), frames.closed().map { it.first })
                assertTrue(frames.closed().all { it.second.startsWith("invalid:") }, "${frames.closed()}")
            }
        }

    @Test
    fun anOversizedReqIsClosedOnItsSubId() =
        runTest {
            server(RelayLimits(maxMessageLength = 40)).use { server ->
                val frames = Frames()
                val session = server.connect(frames)
                frames.clear()

                session.receive("""["REQ","big",{"kinds":[1,2,3,4,5,6,7,8,9,10,11,12]}]""")

                assertEquals(emptyList(), frames.notices())
                val (subId, reason) = frames.closed().single()
                assertEquals("big", subId)
                assertTrue(reason.contains("too large"), reason)
            }
        }

    /** The client sent [replacement] on an open sub id, so the old subscription must stop. */
    private fun aReplacementThatCannotRunEndsTheOldSubscription(
        replacement: String,
        limits: RelayLimits? = null,
    ) = runTest {
        server(limits).use { server ->
            val frames = Frames()
            val session = server.connect(frames)
            val publisher = server.connect(Frames())

            session.receive("""["REQ","live",{"kinds":[1]}]""")
            assertEquals(listOf("live"), frames.messages.filterIsInstance<EoseMessage>().map { it.subId })
            frames.clear()

            session.receive(replacement)
            assertEquals(emptyList(), frames.notices())
            assertEquals(listOf("live"), frames.closed().map { it.first })
            frames.clear()

            // The old "live" no longer streams, and is gone.
            publisher.receive("""["EVENT",${event(1).toJson()}]""")
            assertEquals(emptyList(), frames.events(), "the replaced subscription kept streaming")
            session.receive("""["CLOSE","live"]""")
            assertTrue(
                frames
                    .closed()
                    .single()
                    .second
                    .contains("no such subscription"),
                "${frames.closed()}",
            )
        }
    }

    @Test
    fun anUnparsableReplacementEndsTheOldSubscription() = aReplacementThatCannotRunEndsTheOldSubscription("""["REQ","live",{"limit":"10"}]""")

    @Test
    fun aFilterlessReplacementEndsTheOldSubscription() = aReplacementThatCannotRunEndsTheOldSubscription("""["REQ","live"]""")

    @Test
    fun anOversizedReplacementEndsTheOldSubscription() =
        aReplacementThatCannotRunEndsTheOldSubscription(
            """["REQ","live",{"kinds":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15]}]""",
            RelayLimits(maxMessageLength = 40),
        )

    @Test
    fun anUnparsableCloseStillClosesItsSubscription() = aReplacementThatCannotRunEndsTheOldSubscription("""["CLOSE","live",""")

    @Test
    fun aFrameWithNoReadableIdStillGetsANotice() =
        runTest {
            server().use { server ->
                val frames = Frames()
                val session = server.connect(frames)
                frames.clear()

                for (frame in listOf(
                    "not json",
                    """["REQ",5,{"kinds":[1]}]""",
                    """["REQ","",{"kinds":[1]}]""",
                    """["COUNT"]""",
                    """["CLOSE",{}]""",
                    """["WHAT","x"]""",
                )) {
                    session.receive(frame)
                }

                assertEquals(emptyList(), frames.closed())
                assertEquals(6, frames.notices().size, "${frames.notices()}")
            }
        }
}
