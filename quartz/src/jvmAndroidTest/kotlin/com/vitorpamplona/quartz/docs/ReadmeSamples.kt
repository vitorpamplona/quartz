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
package com.vitorpamplona.quartz.docs

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.subscribe
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.bech32.bechToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * The code blocks of the root README.md, compiled. [ReadmeSnippetsTest] fails when a README block
 * is not found here verbatim, so edit a sample here first and copy it into the README after
 * `spotlessApply` has formatted it. Nothing calls these functions: compiling them is the check.
 */
@Suppress("unused", "UNUSED_VARIABLE", "UNUSED_ANONYMOUS_PARAMETER")
private object ReadmeSamples {
    fun keyPairs() {
        val random = KeyPair() // creates a random key
        val fromHex = KeyPair("hex...".hexToByteArray())
        val fromNsec = KeyPair("nsec1...".bechToBytes())
        val fromMnemonic = KeyPair(Nip06().privateKeyFromMnemonic("<mnemonic>"))
        val readOnlyFromHex = KeyPair(pubKey = "hex...".hexToByteArray())
        val readOnlyFromNpub = KeyPair(pubKey = "npub1...".bechToBytes())
    }

    fun internalSigner(keyPair: KeyPair) {
        val signer = NostrSignerInternal(keyPair)
    }

    fun client() {
        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val rootClient = OkHttpClient.Builder().build()
        val socketBuilder = BasicOkHttpWebSocket.Builder { url -> rootClient }

        val client = NostrClient(socketBuilder, appScope)
    }

    fun auth(
        client: NostrClient,
        appScope: CoroutineScope,
        signer: NostrSigner,
    ) {
        val authCoordinator =
            RelayAuthenticator(client, appScope) { relay, authTemplate, interactive ->
                listOf(
                    // for each signed-in user, return an event
                    signer.sign(authTemplate),
                )
            }
    }

    fun staticSubscription(
        client: NostrClient,
        signer: NostrSigner,
    ) {
        val metadataSub =
            client.subscribe(
                relay = "wss://nos.lol",
                filter =
                    Filter(
                        kinds = listOf(MetadataEvent.KIND),
                        authors = listOf(signer.pubKey),
                    ),
            ) { event ->
                // consume event
            }
    }

    fun dynamicSubscription(client: NostrClient) {
        val metadataSub =
            client.subscribe(
                filters = {
                    // Let's say you have a list of users that need to be rendered
                    val users = pubkeysSeeingInTheScreen()
                    // And a cache repository with their outbox relays
                    val outboxRelays = outboxRelays(users)

                    val filters =
                        listOf(
                            Filter(
                                kinds = listOf(MetadataEvent.KIND),
                                authors = users,
                            ),
                        )

                    outboxRelays.associateWith { filters }
                },
            ) { event ->
                // consume event
            }
    }

    // Stand-ins for app code the README samples call.
    fun pubkeysSeeingInTheScreen(): List<String> = emptyList()

    fun outboxRelays(users: List<String>): Set<NormalizedRelayUrl> = emptySet()
}
