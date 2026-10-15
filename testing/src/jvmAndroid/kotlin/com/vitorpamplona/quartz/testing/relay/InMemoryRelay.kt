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
package com.vitorpamplona.quartz.testing.relay

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.NostrServer
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.EmptyPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.DefaultIndexingStrategy
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * A relay that lives in the test JVM: an in-memory SQLite [store] served by a
 * [NostrServer]. Reach it through [InProcessRelays] (no sockets) or
 * `TestWebSocketRelay` (real ws://, jvmTest only).
 *
 * The default policy is [EmptyPolicy], so tests can publish hand-built events
 * without signing them. Pass `policyBuilder = { VerifyPolicy }` to check
 * signatures like a production relay does.
 */
class InMemoryRelay(
    val url: NormalizedRelayUrl,
    val store: IEventStore = EventStore(dbName = null, relay = url, indexStrategy = RelayIndexingStrategy),
    policyBuilder: () -> IRelayPolicy = { EmptyPolicy },
    parentContext: CoroutineContext = SupervisorJob(),
    parallelVerify: Boolean = false,
    negentropySettings: NegentropySettings = NegentropySettings.Default,
) : AutoCloseable {
    val server =
        NostrServer(
            store,
            policyBuilder = policyBuilder,
            parentContext = parentContext,
            parallelVerify = parallelVerify,
            negentropySettings = negentropySettings,
        )

    /** Closes the server, which also closes [store]. */
    override fun close() = server.close()

    companion object {
        /** The index set a relay keeps: every NIP-01 filter shape, FTS for NIP-50 and the live NIP-77 index. */
        val RelayIndexingStrategy =
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
