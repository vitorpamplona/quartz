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
package com.vitorpamplona.quartz.nip01Core.relay.client.pool

import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.EmptyConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.BasicRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.basic.RateLimitGate
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MessageDecoder
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.utils.cache.LargeCache
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RelayPool manages a collection of Nostr relays, abstracting individual connections and providing
 * unified methods for sending events, managing subscriptions, and tracking relay states.
 *
 * Key features:
 * - Maintains a cache of relays using LargeCache for efficient relay management
 * - Propagates event notifications to a shared listener across all relays
 * - Provides state tracking through RelayPoolStatus (connected/available relays)
 * - Supports relay lifecycle operations like connect/disconnect/reconnect
 * - Maintains an immutable createNewRelay function for custom relay creation
 *
 * Listens to relay events and:
 * 1. Forwards callbacks to parent listener
 * 2. Updates statusFlow when relay connectivity or events change
 * 3. Automatically reconnects relays that need reconnection
 *
 * Common use cases:
 * - Sending events to multiple relays simultaneously (send method)
 * - Managing subscriptions across the relay pool (sendRequest/closed methods)
 * - Maintaining optimal relay connections (updatePool/addRelay/removeRelay methods)
 */
class RelayPool(
    val websocketBuilder: WebsocketBuilder,
    val listener: RelayConnectionListener = EmptyConnectionListener,
    /** Shared per-frame decode strategy for every relay in the pool. */
    val decoder: MessageDecoder = MessageDecoder.Default,
    /** Runs each relay's rate-limit gate; see [BasicRelayClient]. Without one, back-offs are not honored. */
    val scope: CoroutineScope? = null,
    /** Told what each relay's rate-limit gate holds, and asked what to send on release. See [RateLimitGate]. */
    val heldCommands: RateLimitGate.Owner? = null,
) : RelayConnectionListener {
    private val relays = LargeCache<NormalizedRelayUrl, IRelayClient>()

    /**
     * Each relay's rate-limit back-offs, kept apart from its client: a relay dropped from the
     * pool while a back-off runs (its last subscription ended) and dialed again gets a new
     * client, which must still wait the back-off out. Idle gates of relays no longer in the
     * pool are dropped on the next pool change.
     */
    private val gates = LargeCache<NormalizedRelayUrl, RateLimitGate>()

    private val _connectedRelays = MutableStateFlow<Set<NormalizedRelayUrl>>(emptySet())
    val connectedRelays = _connectedRelays.asStateFlow()

    private val _availableRelays = MutableStateFlow<Set<NormalizedRelayUrl>>(emptySet())
    val availableRelays = _availableRelays.asStateFlow()

    /**
     * Serializes the two snapshots below. Both flows are derived from the pool's own state, the
     * members and each member's [IRelayClient.isConnected], and every change to that state is
     * followed by a publish. Taken one at a time, the last publish reads every change made before
     * it, so no interleaving of reports, removals and joins can leave a stale value published.
     *
     * A compare-and-set loop is not enough: [MutableStateFlow] compares by equality, so a snapshot
     * read before a change still lands once an equal value was written over the one it read.
     */
    private val publishLock = PlatformLock()

    /**
     * Publishes a snapshot of the pool's membership. A copy, not `relays.keys()`: on JVM that is
     * the map's live key view, the same object on every call, so the flow saw an equal value and
     * never emitted again after the first, while its `value` kept changing under collectors.
     */
    private fun publishMembers() = publishLock.withLock { _availableRelays.value = relays.keys().toSet() }

    /**
     * Publishes the urls whose current client is connected, read from the clients themselves. Each
     * client changes its own state before it reports (BasicRelayClient marks itself ready before
     * onConnected and closes its session before onDisconnected), so the report's publish reads it.
     *
     * Derived, not patched with each report's url: the pool swaps clients for one url (a sample
     * drops an idle relay just as a publish wants it again), and a replaced client's late close
     * took the url out while its successor was connected; a NIP-77 sync waiting for the relay to
     * show as connected then waited out its whole connect bound on a relay that was up.
     */
    private fun publishConnected() =
        publishLock.withLock {
            val connected = HashSet<NormalizedRelayUrl>()
            relays.forEach { url, relay -> if (relay.isConnected()) connected.add(url) }
            _connectedRelays.value = connected
        }

    fun getRelay(url: NormalizedRelayUrl): IRelayClient? = relays.get(url)

    private fun createNewRelay(url: NormalizedRelayUrl) =
        BasicRelayClient(
            url = url,
            socketBuilder = websocketBuilder,
            listener = this,
            decoder = decoder,
            rateLimits = scope?.let { scope -> gates.getOrCreate(url) { newGate(scope, url) } },
        )

    private fun newGate(
        scope: CoroutineScope,
        url: NormalizedRelayUrl,
    ): RateLimitGate {
        val owner = heldCommands ?: return RateLimitGate(scope)
        return RateLimitGate(
            scope,
            refresh = { owner.onRelease(url, it) },
            onHold = { owner.onHeld(url, it) },
        )
    }

    /** Clears every relay's reconnect backoff. See [IRelayClient.resetBackoff]. */
    fun resetBackoff() {
        relays.forEach { url, relay ->
            relay.resetBackoff()
        }
    }

    fun reconnectIfNeedsTo(ignoreRetryDelays: Boolean = false) {
        relays.forEach { url, relay ->
            if (relay.isConnected()) {
                if (relay.needsToReconnect()) {
                    // network has changed, force reconnect
                    relay.disconnect("the transport changed: reconnecting")
                    relay.connect()
                }
            } else {
                // relay is not connected. Connect if it is time
                relay.connectAndSyncFiltersIfDisconnected(ignoreRetryDelays)
            }
        }
    }

    /**
     * Dials every relay that is not connected and whose reconnect backoff has run out. It used to
     * dial every relay at once, ignoring the backoff: each time the app came back to the
     * foreground, every dead relay in the pool was dialed again. A change that makes the backoff
     * moot (a new network, Tor coming up) clears it first with [resetBackoff].
     */
    fun connect() {
        relays.forEach { url, relay ->
            relay.connectAndSyncFiltersIfDisconnected()
        }
    }

    /** The relays in the pool with no open connection. */
    fun disconnectedRelays(): List<IRelayClient> {
        val down = ArrayList<IRelayClient>()
        relays.forEach { _, relay -> if (!relay.isConnected()) down.add(relay) }
        return down
    }

    /** The relays in the pool with no connection open nor being opened: those waiting out a backoff. */
    internal fun relaysWaitingToRedial(): List<IRelayClient> {
        val down = ArrayList<IRelayClient>()
        relays.forEach { _, relay ->
            if (!relay.isConnected() && !(relay is BasicRelayClient && relay.isConnectionStarted())) down.add(relay)
        }
        return down
    }

    fun connectIfDisconnected() {
        relays.forEach { url, relay ->
            relay.connectAndSyncFiltersIfDisconnected()
        }
    }

    fun connectIfDisconnected(relay: NormalizedRelayUrl) = relays.get(relay)?.connectAndSyncFiltersIfDisconnected()

    fun disconnect() {
        relays.forEach { url, relay ->
            relay.disconnect("the client disconnected")
        }
        // We just tore every socket down; say so here rather than leaving it to each socket's
        // own report. The transports do report a disconnect synchronously (see [WebSocket]),
        // but this flow is what the app reads as "connected", and it must not depend on that:
        // disconnect() leaves each client disconnected, and the snapshot reads the clients.
        publishConnected()
    }

    fun sendOrConnectAndSync(
        relay: NormalizedRelayUrl,
        cmd: Command,
    ) = getOrCreateRelay(relay).sendOrConnectAndSync(cmd)

    fun sendIfConnected(
        relay: NormalizedRelayUrl,
        cmd: Command,
    ) = getOrCreateRelay(relay).sendIfConnected(cmd)

    fun sendOrConnectAndSync(
        list: Set<NormalizedRelayUrl>,
        cmd: Command,
    ) {
        list.forEach {
            getOrCreateRelay(it).sendOrConnectAndSync(cmd)
        }
    }

    fun sendIfConnected(
        list: Set<NormalizedRelayUrl>,
        cmd: Command,
    ) {
        list.forEach {
            getOrCreateRelay(it).sendIfConnected(cmd)
        }
    }

    // --------------------
    // Pool Maintenance
    // --------------------

    /**
     * Called on every send, so the common case is one lookup. It used to compare the published
     * size with `relays.size()` each time, which walks the whole map on a skip list.
     */
    fun getOrCreateRelay(relay: NormalizedRelayUrl): IRelayClient {
        relays.get(relay)?.let { return it }
        if (createRelayIfAbsent(relay)) publishMembers()
        // Created here or by a racing caller; recreated if a pool update dropped it in between.
        return relays.getOrCreate(relay, ::createNewRelay)
    }

    fun createRelayIfAbsent(relay: NormalizedRelayUrl): Boolean = relays.createIfAbsent(relay, ::createNewRelay)

    /**
     * Updates the pool of relays without disconnecting the existing ones. Returns whether it
     * added any relay: a new member is not dialed here, so a caller with work for it connects it.
     *
     * [stillWanted] is asked again right before each relay is dropped. [newRelays] can be a
     * snapshot that is already stale: the client samples its wanted set, and a publish that
     * came in after the sample has put its EVENT on this very socket. Dropping it then
     * disconnects the socket under the in-flight EVENT, whose OK is lost.
     */
    fun updatePool(
        newRelays: Set<NormalizedRelayUrl>,
        stillWanted: (NormalizedRelayUrl) -> Boolean = { false },
    ): Boolean {
        val toRemove = relays.keys() - newRelays
        var added = false
        var removed = false

        newRelays.forEach { relay ->
            if (createRelayIfAbsent(relay)) {
                added = true
            }
        }

        toRemove.forEach { relay ->
            if (!stillWanted(relay) && removeRelayInner(relay)) {
                removed = true
            }
        }

        if (added || removed) {
            publishMembers()
        }
        dropIdleGates()
        return added
    }

    /** Forgets the gates of relays that left the pool once their back-offs ran out. */
    private fun dropIdleGates() {
        if (gates.size() == relays.size()) return
        gates.forEach { url, gate ->
            if (relays.get(url) == null && gate.isIdle()) gates.remove(url)
        }
    }

    fun addRelay(relay: NormalizedRelayUrl): IRelayClient {
        if (createRelayIfAbsent(relay)) {
            publishMembers()
        }
        return getOrCreateRelay(relay)
    }

    fun addAllRelays(relayList: List<NormalizedRelayUrl>) {
        var atLeastOne = false
        relayList.forEach { relay ->
            if (createRelayIfAbsent(relay)) {
                atLeastOne = true
            }
        }

        if (atLeastOne) {
            publishMembers()
        }
    }

    private fun removeRelayInner(relay: NormalizedRelayUrl): Boolean {
        val relayInPool = relays.remove(relay)
        if (relayInPool != null) {
            relayInPool.disconnect("removed from the pool")
            // A relay that is no longer a member cannot be connected, whatever its socket
            // layer reports (or fails to report) later: the snapshot reads only members.
            publishConnected()
            return true
        }
        return false
    }

    fun removeRelay(relay: NormalizedRelayUrl) {
        if (removeRelayInner(relay)) {
            publishMembers()
        }
        dropIdleGates()
    }

    fun removeAllRelays() {
        if (relays.size() > 0) {
            disconnect()
            relays.clear()
            publishMembers()
            publishConnected()
        }
    }

    // --------------------
    // Listener Interceptor
    // --------------------
    override fun onConnecting(relay: IRelayClient) = listener.onConnecting(relay)

    override fun onConnected(
        relay: IRelayClient,
        pingMillis: Int,
        compressed: Boolean,
    ) {
        publishConnected()
        listener.onConnected(relay, pingMillis, compressed)
    }

    override fun onDisconnected(relay: IRelayClient) {
        publishConnected()
        listener.onDisconnected(relay)
    }

    override suspend fun onIncomingMessage(
        relay: IRelayClient,
        msgStr: String,
        msg: Message,
    ) = listener.onIncomingMessage(relay, msgStr, msg)

    override fun onCannotConnect(
        relay: IRelayClient,
        errorMessage: String,
    ) = listener.onCannotConnect(relay, errorMessage)

    override fun onSent(
        relay: IRelayClient,
        cmdStr: String,
        cmd: Command,
        success: Boolean,
    ) = listener.onSent(relay, cmdStr, cmd, success)

    fun connectedRelaysCount(): Int = relays.count { url, relay -> relay.isConnected() }
}
