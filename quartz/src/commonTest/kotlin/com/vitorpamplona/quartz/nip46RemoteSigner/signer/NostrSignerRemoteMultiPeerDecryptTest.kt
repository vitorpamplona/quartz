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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.NostrServer
import com.vitorpamplona.quartz.nip01Core.relay.server.inprocess.InProcessWebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequest
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestConnect
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestNip44Decrypt
import com.vitorpamplona.quartz.nip46RemoteSigner.server.BunkerRequestProcessor
import com.vitorpamplona.quartz.nip46RemoteSigner.server.Nip46ConnectDecision
import com.vitorpamplona.quartz.nip46RemoteSigner.server.Nip46RequestAuthorizer
import com.vitorpamplona.quartz.nip46RemoteSigner.server.NostrConnectSignerService
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A NIP-FB task's private tags may be encrypted to any of several peers, so its author decrypts
 * peer by peer. Through a bunker, a decryption that fails on the wrong peer must read as a failed
 * decryption, so the next peer is tried; the bunker's refusal must stay final.
 */
@OptIn(ExperimentalAtomicApi::class)
class NostrSignerRemoteMultiPeerDecryptTest {
    private val relay = NormalizedRelayUrl("wss://relay.example.com/")

    private class Authorizer(
        val allowDecrypt: Boolean,
    ) : Nip46RequestAuthorizer {
        val decrypts = AtomicInt(0)

        override suspend fun isPaired(clientPubKey: HexKey) = true

        override suspend fun onConnect(
            clientPubKey: HexKey,
            request: BunkerRequestConnect,
        ) = Nip46ConnectDecision.Accept(request.secret ?: "ack")

        override suspend fun authorize(
            clientPubKey: HexKey,
            request: BunkerRequest,
        ): Boolean {
            if (request is BunkerRequestNip44Decrypt) {
                decrypts.incrementAndFetch()
                return allowDecrypt
            }
            return true
        }

        override suspend fun onLogout(clientPubKey: HexKey) {}
    }

    private suspend fun throughBunker(
        user: NostrSignerInternal,
        authorizer: Authorizer,
        block: suspend (NostrSignerRemote) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val server = NostrServer(EventStore(null))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val client =
            NostrClient(
                object : WebsocketBuilder {
                    override fun build(
                        url: NormalizedRelayUrl,
                        out: WebSocketListener,
                    ) = InProcessWebSocket(server, out)
                },
                scope,
            )
        val bunkerTransport = NostrSignerInternal(KeyPair())
        val processor = BunkerRequestProcessor(user, { setOf(relay) }, authorizer)
        val service = NostrConnectSignerService(client, bunkerTransport, processor, setOf(relay))
        scope.launch(start = CoroutineStart.UNDISPATCHED) { service.run() }

        val remote = NostrSignerRemote(NostrSignerInternal(KeyPair()), bunkerTransport.pubKey, setOf(relay), client)
        remote.bindUserPubkey(user.pubKey)
        remote.openSubscription()
        try {
            withTimeout(30_000) { block(remote) }
        } finally {
            remote.closeSubscription()
            scope.cancel()
            server.close()
        }
    }

    /** A conflict another writer encrypted to its second runner: the author's first two peers are wrong. */
    private suspend fun conflictEncryptedToTheSecondRunner(user: NostrSignerInternal): Pair<ScheduledTaskEvent, ScheduledTask> {
        val first = NostrSignerInternal(KeyPair()).pubKey
        val second = NostrSignerInternal(KeyPair()).pubKey
        val task =
            ScheduledTask(
                arrayOf(arrayOf("d", "observer"), arrayOf("p", first), arrayOf("p", second)),
                arrayOf(arrayOf("task", "observer"), arrayOf("cron", "0 6 * * *", "America/New_York", "0")),
            )
        val content = user.nip44Encrypt(PrivateTagsInContent.encode(task.privateTags), second)
        val event: Event = user.sign(EventTemplate(1_000, ScheduledTaskEvent.KIND, task.publicTags, content))
        val parsed = EventFactory.create<ScheduledTaskEvent>(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig)
        assertEquals(listOf(user.pubKey, first, second), parsed.privateTagsPeers(user.pubKey))
        return parsed to task
    }

    @Test
    fun aBunkerUserReadsATaskEncryptedToAPeerTriedLater() =
        runTest {
            val user = NostrSignerInternal(KeyPair())
            val (event, task) = conflictEncryptedToTheSecondRunner(user)
            val authorizer = Authorizer(allowDecrypt = true)
            throughBunker(user, authorizer) { remote ->
                assertEquals(task, event.task(remote))
                assertEquals(3, authorizer.decrypts.load())
            }
        }

    @Test
    fun aBunkersRefusalIsAskedOnceNotOncePerPeer() =
        runTest {
            val user = NostrSignerInternal(KeyPair())
            val (event, _) = conflictEncryptedToTheSecondRunner(user)
            val authorizer = Authorizer(allowDecrypt = false)
            throughBunker(user, authorizer) { remote ->
                assertFailsWith<SignerExceptions.ManuallyUnauthorizedException> { event.decrypt(remote) }
                assertEquals(1, authorizer.decrypts.load())
            }
        }
}
