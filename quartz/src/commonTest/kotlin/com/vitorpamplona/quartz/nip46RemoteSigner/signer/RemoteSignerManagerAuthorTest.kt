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
package com.vitorpamplona.quartz.nip46RemoteSigner.signer

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequest
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponse
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponsePong
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A NIP-46 client takes responses only from its remote signer's key. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSignerManagerAuthorTest {
    private object Done : IResult

    private val me = NostrSignerInternal(KeyPair())
    private val bunker = NostrSignerInternal(KeyPair())
    private val stranger = NostrSignerSync()

    private val sent = mutableListOf<Event>()
    private val client =
        object : INostrClient by EmptyNostrClient() {
            override fun publish(
                event: Event,
                relayList: Set<NormalizedRelayUrl>,
            ) {
                sent.add(event)
            }
        }

    private val manager = RemoteSignerManager(timeout = 10_000, client = client, signer = me, remoteKey = bunker.pubKey, relayList = emptySet(), maxRetries = 0)

    /** Sends a ping, delivers whatever [respond] builds from the request, and returns the response the client took. */
    private suspend fun TestScope.answer(respond: suspend (request: Event) -> NostrConnectEvent): BunkerResponse? {
        var got: BunkerResponse? = null
        val call =
            async {
                manager.launchWaitAndParse({ BunkerRequest("x", "ping", emptyArray()) }) { r ->
                    got = r
                    SignerResult.RequestAddressed.Successful(Done)
                }
            }
        runCurrent()
        manager.newResponse(respond(sent.single()))
        runCurrent()
        advanceTimeBy(20_000)
        call.await()
        return got
    }

    private suspend fun requestId(request: Event): String = OptimizedJsonMapper.fromJsonTo<BunkerRequest>(bunker.decrypt(request.content, me.pubKey)).id

    @Test
    fun theBunkersAnswerIsTaken() =
        runTest {
            val got = answer { NostrConnectEvent.create(BunkerResponsePong(requestId(it)), me.pubKey, bunker) }
            assertEquals("pong", got?.result)
        }

    @Test
    fun theBunkersCiphertextResignedByAStrangerIsIgnored() =
        runTest {
            val got =
                answer { request ->
                    val real = NostrConnectEvent.create(BunkerResponsePong(requestId(request)), me.pubKey, bunker)
                    stranger.sign(real.createdAt, NostrConnectEvent.KIND, arrayOf(arrayOf("p", me.pubKey)), real.content)
                }
            assertNull(got, "a response signed by someone other than the bunker was taken")
        }

    @Test
    fun theClientsOwnRequestEchoedByAStrangerIsIgnored() =
        runTest {
            val got = answer { request -> stranger.sign(request.createdAt, NostrConnectEvent.KIND, arrayOf(arrayOf("p", me.pubKey)), request.content) }
            assertNull(got, "the echoed request was taken as the bunker's answer")
        }
}
