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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.CountMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MachineReadablePrefix
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveListener
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveSubscription
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.LiveSubscriptionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.RawEvent
import com.vitorpamplona.quartz.nip01Core.store.RejectionReason
import com.vitorpamplona.quartz.nip77Negentropy.NegCloseCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.cache.LargeCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Represents an active session between a Nostr client and the relay.
 * Each one of these is a connection that can hold many subscriptions.
 *
 * End it with [close]. On a [LiveSubscriptionBackend] a REQ whose replay is done lives in
 * the backend's index rather than in [scope], so cancelling the scope alone leaves it
 * registered and delivering.
 */
@OptIn(ExperimentalAtomicApi::class)
class RelaySession(
    private val store: SessionBackend,
    val policy: IRelayPolicy,
    private val scope: CoroutineScope,
    /** Where every frame for this client goes; see [SessionSink] for the typed/raw split. */
    private val sink: SessionSink,
    private val onClose: (RelaySession) -> Unit,
    negentropySettings: NegentropySettings = NegentropySettings.Default,
    /**
     * Stable, process-unique identifier for this connection. Used by the
     * server classes to key their connection registry and by
     * [RelayServerListener] callbacks so observers can correlate the
     * open/close of the same connection. Defaults to a fresh monotonic id.
     */
    val id: Long = nextConnectionId(),
    /**
     * NIP-67: append a completeness hint (`"finish"` / `"more"`) to each REQ's `EOSE`
     * when the stored replay proves one — see [EoseCompletenessProbe]. Off by default:
     * the proof assumes the [store] honours `limit` exactly and returns every match
     * for an unbounded filter, which an arbitrary backend need not do.
     */
    val completenessHints: Boolean = false,
    /**
     * Consumes EVENTs addressed to the relay itself (e.g. NIP-43 join/leave
     * requests) ahead of the [policy] chain; see [EventCommandHandler].
     * Null (the default) sends every EVENT down the normal path.
     */
    private val commandHandler: EventCommandHandler? = null,
    /** The client's address as the transport saw it; see [RequestContext.clientAddress]. */
    val clientAddress: String? = null,
) : AutoCloseable {
    /** The original, string-only constructor; every frame goes to [onSend] as wire JSON. */
    constructor(
        store: SessionBackend,
        policy: IRelayPolicy,
        scope: CoroutineScope,
        onSend: (String) -> Unit,
        onClose: (RelaySession) -> Unit,
        negentropySettings: NegentropySettings = NegentropySettings.Default,
        id: Long = nextConnectionId(),
    ) : this(store, policy, scope, SessionSink.of(onSend), onClose, negentropySettings, id)

    /** Open REQs by sub id. */
    private val subscriptions = LargeCache<String, OpenReq>()

    /** [sink] when it takes live frames in parts, decided once per connection. */
    private val liveFrameSink = sink as? LiveFrameSink

    /**
     * The authenticated-identity store for this connection. The engine is the
     * only writer (committed in [handleAuth] on a successful NIP-42 AUTH); the
     * policy and the data plane read it through [requestContext].
     *
     * Copy-on-write on purpose: readers run concurrently with the engine —
     * a REQ replay coroutine can hold the set (via `StoreQueryContext` or an
     * `EventSource` reading [RequestContext.authenticatedUsers]) while a
     * later AUTH frame commits a new identity. Each read hands out the
     * current **immutable** set, so an in-flight query keeps a consistent
     * snapshot instead of racing a mutating `LinkedHashSet`; `@Volatile`
     * makes the swapped reference visible across threads. AUTH is rare, so
     * the copy costs nothing on the hot path.
     */
    @Volatile
    private var authenticatedUsers = setOf<HexKey>()

    /**
     * The per-connection scope. Handed to the [policy] at connect (so gating
     * policies can read the authenticated users) and to the [store] on every
     * REQ/COUNT (so a source can see who is asking). [RequestContext.authenticatedUsers]
     * is a live view of [authenticatedUsers], so a REQ after a NIP-42 AUTH sees
     * the freshly recorded pubkey(s).
     */
    val requestContext: RequestContext =
        object : RequestContext {
            override val connectionId = id
            override val policy = this@RelaySession.policy
            override val authenticatedUsers: Set<HexKey> get() = this@RelaySession.authenticatedUsers
            override val clientAddress: String? get() = this@RelaySession.clientAddress
            override val liveExecutor: FanoutExecutor? = sink.liveExecutor
        }

    /** NIP-77 negentropy state for this connection. */
    private val negentropy = NegSessionRegistry(store, ::send, negentropySettings)

    private fun cancelSubscription(subId: String): Boolean =
        subscriptions.remove(subId)?.let {
            it.cancel()
            true
        } ?: false

    fun cancelAllSubscriptions() {
        subscriptions.forEach { _, req -> req.cancel() }
        subscriptions.clear()
        negentropy.clear()
    }

    fun send(message: Message) {
        try {
            sink.message(message)
        } catch (e: Exception) {
            Log.w("ClientSession") { "Failed to send to ${e.message}" }
        }
    }

    /** [send] for frames that are already wire-format JSON (the raw REQ path). */
    private fun sendRaw(json: String) {
        try {
            sink.raw(json)
        } catch (e: Exception) {
            Log.w("ClientSession") { "Failed to send to ${e.message}" }
        }
    }

    /**
     * A live `EVENT` frame whose [body] is already serialized (once per event, shared across
     * every matching subscription). A [LiveFrameSink] takes the prefix and the shared body
     * apart, so it can encode the body once per event across all its sockets; any other sink
     * gets them spliced into one frame, with no per-event EventMessage or re-serialize.
     */
    private fun sendLive(
        framePrefix: String,
        body: String,
    ) {
        val parts = liveFrameSink
        if (parts == null) {
            sendRaw(
                buildString(framePrefix.length + body.length + 1) {
                    append(framePrefix)
                    append(body)
                    append(']')
                },
            )
            return
        }
        try {
            parts.liveEvent(framePrefix, body)
        } catch (e: Exception) {
            Log.w("ClientSession") { "Failed to send to ${e.message}" }
        }
    }

    /** An event on the screened path: materialized, and sent only if the policy allows it. */
    private fun sendScreened(
        subId: String,
        event: Event,
    ) {
        if (policy.canSendToSession(event)) send(EventMessage(subId, event))
    }

    override fun close() {
        cancelAllSubscriptions()
        onClose(this)
    }

    /**
     * Processes a raw JSON message from a client.
     *
     * Parses the message as a NIP-01 command and dispatches it.
     */
    suspend fun receive(command: String) {
        policy.acceptMessage(command)?.let { reason ->
            refuseUnparsed(command, reason)
            return
        }

        val cmd =
            try {
                OptimizedJsonMapper.fromJsonToCommand(command)
            } catch (_: Exception) {
                send(NoticeMessage("error: could not parse message"))
                return
            }

        receive(cmd)
    }

    /**
     * [receive] for a transport that already parsed [parsed] out of [command]
     * (NIP-FE's HTTP bodies): [IRelayPolicy.acceptMessage] still judges the
     * wire text, as it would on the socket, but the frame is not parsed twice.
     */
    suspend fun receive(
        command: String,
        parsed: Command,
    ) {
        policy.acceptMessage(command)?.let { reason ->
            refuseUnparsed(command, reason)
            return
        }
        receive(parsed)
    }

    /**
     * Answers a frame [IRelayPolicy.acceptMessage] refused. An EVENT is owed its `OK` (NIP-01),
     * and a client pipelining EVENTs pairs OKs by id, so a refused EVENT whose id can be read
     * gets `OK false`; anything else gets a NOTICE.
     */
    private fun refuseUnparsed(
        command: String,
        reason: String,
    ) {
        val id = eventIdOfEventFrame(command)
        send(if (id != null) OkMessage(id, false, reason) else NoticeMessage(reason))
    }

    /**
     * Dispatches an already-parsed command. [IRelayPolicy.acceptMessage] is
     * not run here — it judges wire text — so a caller that has the text
     * uses the overload that takes both.
     */
    suspend fun receive(cmd: Command) {
        if (!cmd.isValid()) {
            send(NoticeMessage("error: invalid command"))
            return
        }

        when (cmd) {
            is AuthCmd -> handleAuth(cmd)
            is EventCmd -> handleEvent(cmd)
            is ReqCmd -> handleReq(cmd)
            is CloseCmd -> handleClose(cmd)
            is CountCmd -> handleCount(cmd)
            is NegOpenCmd -> negentropy.open(cmd, policy)
            is NegMsgCmd -> negentropy.msg(cmd)
            is NegCloseCmd -> negentropy.close(cmd)
            else -> send(NoticeMessage("error: unsupported command ${cmd.label()}"))
        }
    }

    private suspend fun handleEvent(cmd: EventCmd) {
        if (commandHandler != null) {
            val handled =
                try {
                    commandHandler.handle(cmd.event, requestContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OkMessage.rejected(cmd.event.id, MachineReadablePrefix.ERROR, e.message ?: "request failed")
                }
            if (handled != null) {
                send(handled)
                return
            }
        }

        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            send(OkMessage(cmd.event.id, false, result.reason, result.params))
            return
        }

        // Fire-and-forget: hand the event to the group-commit writer
        // and continue reading from the WebSocket without waiting on
        // SQLite. The OK frame is sent from the writer's callback,
        // possibly out of arrival order — NIP-01 pairs OKs to events
        // by id, so reordering is fine.
        try {
            store.submit(cmd.event) { outcome ->
                when (outcome) {
                    IEventStore.InsertOutcome.Accepted -> {
                        send(OkMessage(cmd.event.id, true, ""))
                    }

                    is IEventStore.InsertOutcome.Rejected -> {
                        // NIP-01: an event the relay already holds is acknowledged with
                        // `OK true` and the `duplicate:` prefix. Every real client
                        // (amethyst's outbox included) resends an event whose OK has not
                        // landed yet, and treats OK false as a rejection to surface —
                        // so answering false here turns a routine resend into an error.
                        val duplicate = outcome.reason.startsWith(RejectionReason.PREFIX_DUPLICATE)
                        send(OkMessage(cmd.event.id, duplicate, outcome.reason))
                    }

                    is IEventStore.InsertOutcome.Failed -> {
                        // The store's error, not the event's — NIP-01's
                        // machine-readable prefix for that is "error:".
                        val reason = outcome.reason
                        send(OkMessage(cmd.event.id, false, if (reason.startsWith("error:")) reason else "error: $reason"))
                    }
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Server is shutting down — the queue is closed. Reply
            // with a transient failure so a client re-trying against
            // the next instance gets a sane signal; the WS itself is
            // about to be torn down by the server-stop path.
            send(OkMessage(cmd.event.id, false, "error: relay shutting down"))
        }
    }

    private suspend fun handleCount(cmd: CountCmd) {
        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            send(ClosedMessage(cmd.queryId, result.reason, result.params))
            return
        }

        // Policy may rewrite filters to match the user's access level.
        val filters = (result as PolicyResult.Accepted).cmd.filters

        val countResult =
            try {
                store.countResult(requestContext, filters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(ClosedMessage.of(cmd.queryId, MachineReadablePrefix.ERROR, e.message ?: "count failed"))
                return
            }

        send(CountMessage(cmd.queryId, countResult))
    }

    /**
     * Records [pubkey], proved by the transport rather than a NIP-42 AUTH (NIP-FE's NIP-98 header),
     * once the policy chain has no objection ([IRelayPolicy.acceptTransportIdentity]). Returns the
     * refusal, or null when the key is now authenticated on this connection exactly as AUTH would.
     */
    suspend fun authenticateByTransport(pubkey: HexKey): String? {
        val refused =
            try {
                policy.acceptTransportIdentity(pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MachineReadablePrefix.ERROR.format(e.message ?: "authentication failed")
            }
        if (refused == null) authenticatedUsers = authenticatedUsers + pubkey
        return refused
    }

    // -- NIP-42: AUTH ---------------------------------------------------------
    private suspend fun handleAuth(cmd: AuthCmd) {
        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            send(OkMessage(cmd.event.id, false, result.reason, result.params))
            return
        }

        // The whole policy chain validated the AUTH. onAuthenticated runs any
        // post-verification I/O (e.g. exchanging the verified event for a
        // backend token) and votes on whether to record the identity. A throw
        // here cleanly fails the login — the engine records nothing.
        val record =
            try {
                policy.onAuthenticated(cmd.event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(OkMessage.rejected(cmd.event.id, MachineReadablePrefix.ERROR, e.message ?: "authentication failed"))
                return
            }

        // Single, engine-side commit into the connection scope — after the full
        // chain approved and a verifying policy voted to record.
        if (record) authenticatedUsers = authenticatedUsers + cmd.event.pubKey

        send(OkMessage(cmd.event.id, true, ""))
    }

    // -- NIP-01: REQ ----------------------------------------------------------
    private fun handleReq(cmd: ReqCmd) {
        // Ask the policy whether a *new* subscription may open (e.g. a
        // max_subscriptions cap). A re-REQ on an existing id replaces it
        // 1-for-1 and doesn't grow the count, so it's exempt. Checked before
        // the cancel so the existing subscription isn't dropped only to then
        // reject its replacement.
        if (!subscriptions.containsKey(cmd.subId)) {
            policy.acceptSubscription(cmd.subId, subscriptions.size())?.let { reason ->
                send(ClosedMessage(cmd.subId, reason))
                return
            }
        }

        // Cancel any existing subscription with the same id (NIP-01 spec).
        cancelSubscription(cmd.subId)

        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            send(ClosedMessage(cmd.subId, result.reason, result.params))
            return
        }

        // Policy may rewrite filters to match the user's access level.
        val acceptedFilters = (result as PolicyResult.Accepted).cmd.filters

        // NIP-67: may raise a single filter's limit by one to detect "more"; the extra
        // stored row is counted but never sent. Zero-decode path only: the screened path's
        // single `onEach` also carries live events accepted mid-replay, so stored rows can't
        // be told apart there, and a policy that vetoes rows could not honestly say "finish".
        val screened = policy.filtersOutgoingEvents
        val probe = if (completenessHints && !screened) EoseCompletenessProbe.of(acceptedFilters) else null
        val filters = probe?.queryFilters ?: acceptedFilters
        val eose = { send(EoseMessage(cmd.subId, probe?.hints())) }

        // The `["EVENT","<subId>",` prefix is built once per subscription, not per row.
        val framePrefix =
            if (screened) {
                null
            } else {
                buildString {
                    append("[\"EVENT\",")
                    RawEvent.appendJsonQuoted(this, cmd.subId)
                    append(',')
                }
            }

        val req = OpenReq(cmd.subId, framePrefix)
        val live = store as? LiveSubscriptionBackend
        if (live == null) {
            req.replay = launchParkedReq(req, filters, probe, eose)
            subscriptions.put(cmd.subId, req)
            return
        }

        // Registered before this returns, so the slot is in [subscriptions] before any
        // CLOSE can look for it; live events may already be arriving on the listener.
        val subscription =
            try {
                live.openSubscription(requestContext, filters, req)
            } catch (e: Exception) {
                // A wrapper refusing the REQ outright (a gate) answers like a failed replay.
                send(ClosedMessage.of(cmd.subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
                return
            }
        req.subscription = subscription
        subscriptions.put(cmd.subId, req)

        // UNDISPATCHED: the stored replay runs inline on this coroutine —
        // the reader-pool acquire doesn't suspend when a connection is
        // free, so EVENT frames and EOSE go out without a scheduler hop
        // (SmallReqFloorBenchmark: the hop was most of the dispatch
        // slice on small REQs). The coroutine ends at EOSE: from then on
        // the subscription is the [OpenReq] and its registration.
        val replay =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    // A replay that ignores cancellation keeps producing rows after a
                    // CLOSE: none of them, nor its EOSE, may reach the client.
                    if (framePrefix == null) {
                        // Screened path: every event is materialized so the
                        // policy can veto it per session.
                        subscription.replay(
                            onEach = { event -> if (!req.closed) sendScreened(cmd.subId, event) },
                            onEose = { if (!req.closed) eose() },
                        )
                    } else {
                        subscription.replayRaw(
                            onEachStored = { raw -> if (!req.closed) sendStored(framePrefix, raw, probe) },
                            onEose = { if (!req.closed) eose() },
                        )
                    }
                } catch (e: CancellationException) {
                    // Subscription was closed – this is expected. Unregistering is
                    // idempotent, and covers a wrapper that never reached the store.
                    subscription.cancel()
                    throw e
                } catch (e: Exception) {
                    // A backend failure (e.g. an event source's network I/O)
                    // ends the subscription with a machine-readable CLOSED
                    // rather than silently dropping the coroutine.
                    subscription.cancel()
                    send(ClosedMessage.of(cmd.subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
                }
            }
        // Held only while the replay runs, so a CLOSE mid-replay can stop it.
        if (!replay.isCompleted) {
            req.replay = replay
            replay.invokeOnCompletion { req.replay = null }
        }
    }

    /**
     * One open REQ, and the listener its live events arrive on. On a
     * [LiveSubscriptionBackend] it holds the backend's registration, plus the replay coroutine
     * until EOSE; after that it and the registration are all the REQ keeps. On any other
     * backend it holds the coroutine parked at the live tail.
     *
     * @param framePrefix `["EVENT","<subId>",` on the zero-decode path; null when the policy
     *   screens every event, so events go out as [EventMessage]s after
     *   [IRelayPolicy.canSendToSession].
     */
    private inner class OpenReq(
        val subId: String,
        val framePrefix: String?,
    ) : LiveListener {
        /** The backend's registration, on a [LiveSubscriptionBackend]. */
        var subscription: LiveSubscription? = null

        /** The replay coroutine until it ends, or the parked one on any other backend. */
        @Volatile
        var replay: Job? = null

        /** Set by [cancel]: CLOSE, a replaced sub id or a disconnect. */
        @Volatile
        var closed = false

        override fun onLive(
            event: Event,
            body: String,
        ) {
            if (framePrefix == null) sendScreened(subId, event) else sendLive(framePrefix, body)
        }

        fun cancel() {
            closed = true
            replay?.cancel()
            subscription?.cancel()
        }
    }

    /** A stored row as one `EVENT` frame; the NIP-67 probe's one extra row is counted, not sent. */
    private fun sendStored(
        framePrefix: String,
        raw: RawEvent,
        probe: EoseCompletenessProbe?,
    ) {
        if (probe == null || probe.onStored()) {
            sendRaw(
                buildString(framePrefix.length + raw.jsonObjectLengthEstimate() + 1) {
                    append(framePrefix)
                    raw.appendJsonObjectTo(this)
                    append(']')
                },
            )
        }
    }

    /**
     * The REQ path for a backend that is not a [LiveSubscriptionBackend]: one coroutine per
     * subscription, parked inside [SessionBackend.queryRaw] / [SessionBackend.query] for the
     * live tail until cancelled. A finite backend returns after EOSE and the job completes.
     */
    private fun launchParkedReq(
        req: OpenReq,
        filters: List<Filter>,
        probe: EoseCompletenessProbe?,
        eose: () -> Unit,
    ): Job =
        // UNDISPATCHED: as above. The coroutine first parks at the live
        // tail, which is when launch returns and the job lands in
        // [subscriptions]; commands on this connection are processed
        // sequentially, so nothing can target the sub earlier.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val framePrefix = req.framePrefix
                if (framePrefix == null) {
                    store.query(
                        ctx = requestContext,
                        filters = filters,
                        onEach = { event -> sendScreened(req.subId, event) },
                        onEose = { eose() },
                    )
                } else {
                    store.queryRaw(
                        ctx = requestContext,
                        filters = filters,
                        onEachStored = { raw -> sendStored(framePrefix, raw, probe) },
                        onEachLive = { event, body -> req.onLive(event, body) },
                        onEose = { eose() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(ClosedMessage.of(req.subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
            }
        }

    // -- NIP-01: CLOSE --------------------------------------------------------
    private fun handleClose(cmd: CloseCmd) {
        val cancelled = cancelSubscription(cmd.subId)
        if (!cancelled) {
            send(ClosedMessage(cmd.subId, "error: no such subscription"))
        }
    }

    init {
        policy.onConnect(requestContext, ::send)
    }

    companion object {
        private val connectionIdSeq = AtomicLong(0L)

        /** Allocates the next process-unique connection id. */
        fun nextConnectionId(): Long = connectionIdSeq.fetchAndAdd(1L)
    }
}
