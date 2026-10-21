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
package com.vitorpamplona.quartz.testing

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.NostrServer
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.DefaultIndexingStrategy
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import kotlinx.coroutines.SupervisorJob

/**
 * A Nostr relay for tests, scoped to one URL: a [NostrServer] over an in-memory SQLite
 * [EventStore]. Clients reach it through [InProcessRelays], with no socket.
 *
 * Speaks NIP-01, NIP-42 (with an auth policy from [policyBuilder]), NIP-45, NIP-50 and
 * NIP-77. The store indexes like a public relay does (created_at alone, authors alone,
 * tag + kind + author, FTS and the live negentropy set) because relay clients are tested
 * against what relays run, not against a client-side store.
 */
class TestRelay(
    val url: NormalizedRelayUrl,
    val store: IEventStore = EventStore(dbName = null, relay = url, indexStrategy = RelayIndexing),
    policyBuilder: () -> IRelayPolicy = { EmptyPolicy },
    negentropySettings: NegentropySettings = NegentropySettings.Default,
) : AutoCloseable {
    val server =
        NostrServer(
            store,
            policyBuilder = policyBuilder,
            parentContext = SupervisorJob(),
            negentropySettings = negentropySettings,
        )

    override fun close() {
        server.close()
    }

    companion object {
        val RelayIndexing =
            DefaultIndexingStrategy(
                indexEventsByCreatedAtAlone = true,
                indexEventsByPubkeyAlone = true,
                indexTagsWithKindAndPubkey = true,
                indexFullTextSearch = true,
                deferFullTextSearchIndexing = true,
                maintainLiveNegentropyIndex = true,
            )
    }
}
