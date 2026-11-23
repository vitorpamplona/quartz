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
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap
import com.vitorpamplona.quartz.utils.isFatal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
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

    /**
     * Open REQs by sub id. Written by the reader and by a replay that fails (the relay ends its
     * REQ), hence concurrent, with a remove that takes only the entry it was given.
     */
    private val subscriptions = ConcurrentMap<String, OpenReq>()

    /**
     * COUNTs by query id, from the frame that asked until their coroutine ends, so a re-COUNT on
     * the id, a CLOSE on it or a disconnect can stop one. A namespace beside [subscriptions]: a
     * REQ never replaces a COUNT, nor the reverse, and each is capped on its own through
     * [IRelayPolicy.acceptSubscription]. A CLOSE names an id, not a kind, so it ends both a REQ
     * and a COUNT that share one.
     */
    private val counts = ConcurrentMap<String, PendingCount>()

    /**
     * REQ replays and COUNTs this connection cancelled (a CLOSE, a replaced id, a disconnect)
     * that the store has not let go of yet. A store that ignores cancellation (a blocking read)
     * keeps working, so each keeps its slot under [IRelayPolicy.acceptSubscription] until it
     * stops: a client cannot pile reads onto the store by closing and reopening.
     */
    private val drainingReplays = AtomicInt(0)
    private val drainingCounts = AtomicInt(0)

    /** Set by [cancelAllSubscriptions]: a REQ or COUNT that registers after it is stopped at once. */
    @Volatile
    private var ended = false

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
     *
     * Ordered: `Set.plus` keeps the original set's iteration order and appends,
     * so the set iterates in authentication order and `StoreQueryContext.observer`
     * (its first element) is the first key that authenticated.
     */
    @Volatile
    private var authenticatedUsers = setOf<HexKey>()

    /**
     * The per-connection scope. Handed to the [policy] at connect (so gating
     * policies can read the authenticated users) and to the [store] on every
     * REQ/COUNT (so a source can see who is asking). [RequestContext.authenticatedUsers]
     * is a live view of [authenticatedUsers], so a REQ after a NIP-42 AUTH sees
     * the freshly recorded pubkey(s). A COUNT gets a copy frozen when it arrives.
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

    /**
     * Ends this connection's work: every REQ and COUNT is cancelled, and one that registers after
     * this (a frame the reader was still handling when a disconnect came in on another thread) is
     * stopped as it registers. The session takes on no new work afterwards.
     */
    fun cancelAllSubscriptions() {
        ended = true
        subscriptions.asMap().values.forEach { it.cancel() }
        subscriptions.clear()
        // Each count's completion takes it out of [counts].
        counts.asMap().values.forEach { it.cancel() }
        negentropy.clear()
    }

    /** Counts [job], just cancelled, against [draining] until it ends. */
    private fun drain(
        job: Job,
        draining: AtomicInt,
    ) {
        if (job.isCompleted) return
        draining.addAndFetch(1)
        job.invokeOnCompletion { draining.addAndFetch(-1) }
    }

    /**
     * [IRelayPolicy.acceptSubscription]'s verdict on [id] joining the [open] REQs or COUNTs of its
     * kind, plus the [draining] reads of that kind the store still runs. One that replaces an open
     * id 1-for-1 doesn't grow the count, so it is exempt, unless cancelled reads are still
     * running: then it is weighed without the one it replaces.
     */
    private fun refusal(
        id: String,
        replacing: Boolean,
        open: Int,
        draining: Int,
    ): String? =
        if (replacing && draining == 0) {
            null
        } else {
            policy.acceptSubscription(id, open - (if (replacing) 1 else 0) + draining)
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
                // An EVENT or AUTH is owed its OK even when it does not parse (NIP-01, NIP-42):
                // a client pipelining EVENTs pairs OKs by id, and an AUTH carrying an event of
                // the wrong kind fails here, not in the auth policy. Only a frame whose id
                // cannot be read gets the NOTICE.
                val id = eventIdOfFrame(command)
                send(if (id != null) OkMessage(id, false, "invalid: could not parse message") else NoticeMessage("error: could not parse message"))
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
     * as is an AUTH (NIP-42), and a client pipelining EVENTs pairs OKs by id, so a refused EVENT
     * or AUTH whose id can be read gets `OK false`; anything else gets a NOTICE.
     */
    private fun refuseUnparsed(
        command: String,
        reason: String,
    ) {
        val id = eventIdOfFrame(command)
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

    // -- NIP-45: COUNT --------------------------------------------------------

    /**
     * Answers a COUNT off the reader, as a REQ's replay is: a count that suspends (a ranked
     * recall can take seconds) holds up no EVENT, REQ or CLOSE behind it. NIP-45 pairs the answer
     * with its query by id, so it may arrive after the answers to later frames. A count that does
     * not suspend still answers before this returns (UNDISPATCHED), as it did. How many counts a
     * connection may run at once, and in what order they reach the store, is the app's call: the
     * engine caps only how many it holds, through [IRelayPolicy.acceptSubscription].
     *
     * A COUNT on an id whose count is pending replaces it, as a REQ replaces a REQ: the older one
     * is cancelled, and an answer of it already going out is sent before the new one's. The
     * policy runs here, on the reader, and the store gets the connection's identities as they
     * stood here, so a count that waits (in the store's own gate) is still asked as of the frames
     * before it.
     *
     * The count runs in the session's scope, as a REQ's replay does, so `CoroutineContext`
     * elements a host sets around [receive] (an MDC or other `ThreadContextElement`) do not reach
     * [SessionBackend.countResult].
     */
    private fun handleCount(cmd: CountCmd) {
        val queryId = cmd.queryId

        // The policy first, so an id it finds invalid is refused as invalid rather than as over
        // the cap. A refused re-COUNT still drops the count it replaces, as a refused re-REQ does.
        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            val prior = counts[queryId]
            if (prior != null && prior.cancel()) counts.remove(queryId, prior)
            sendAfter(prior, ClosedMessage(queryId, result.reason, result.params))
            return
        }

        // Then whether a *new* count may start, as for a REQ: pending and draining counts are work
        // this connection holds. Counts are capped apart from REQs, so a client holding its REQs
        // at the cap can still COUNT, as it always could. A refused re-COUNT leaves its
        // predecessor running.
        val previous = counts[queryId]
        refusal(queryId, previous != null, counts.size(), drainingCounts.load())?.let { reason ->
            send(ClosedMessage(queryId, reason))
            return
        }

        // Policy may rewrite filters to match the user's access level.
        val filters = (result as PolicyResult.Accepted).cmd.filters
        val ctx = FrozenRequestContext(requestContext)

        val pending = PendingCount(previous?.quiet)
        previous?.cancel()
        counts[queryId] = pending
        // A disconnect on another thread may have swept [counts] before this landed in it.
        if (ended) {
            pending.cancel()
            counts.remove(queryId, pending)
            return
        }
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val answer =
                    try {
                        CountMessage(queryId, store.countResult(ctx, filters))
                    } catch (e: Throwable) {
                        // This count's own cancellation ends it unanswered, and a fatal VM error is
                        // not ours to catch. Any other failure, a store's own CancellationException
                        // (a timeout on its read) or an Error such as TODO()'s included, is the
                        // count's answer: the client would otherwise wait for good.
                        if ((e is CancellationException && !isActive) || isFatal(e)) throw e
                        ClosedMessage.of(queryId, MachineReadablePrefix.ERROR, e.message ?: "count failed")
                    }
                pending.answer(answer)
            }
        pending.attach(job)
        job.invokeOnCompletion {
            pending.finished()
            counts.remove(queryId, pending)
        }
    }

    /** Sends [message] now, or once [count]'s answer, if one is going out, has gone. */
    private fun sendAfter(
        count: PendingCount?,
        message: Message,
    ) {
        if (count == null) send(message) else count.quiet.invokeOnCompletion { send(message) }
    }

    /**
     * One COUNT, from the frame that asked until its coroutine ends: whether it may still answer,
     * and the earlier answer on its id it must not overtake. Its state only moves forward:
     * pending, then sending and sent once it answers, or cancelled. The answer is claimed
     * atomically and sent outside any lock, so a sink may end this session from inside
     * [SessionSink.message] (a transport hanging up once it has its answer) on any platform.
     */
    private inner class PendingCount(
        /** Quiet once the count this one replaced sends nothing more: this one answers after it. */
        private val after: Job?,
    ) {
        private val state = AtomicInt(COUNT_PENDING)

        @Volatile
        private var job: Job? = null
        private val stopped = AtomicBoolean(false)

        /** Completes once this count sends nothing more, and [after] is quiet too. */
        val quiet: CompletableJob = Job()

        fun attach(job: Job) {
            this.job = job
            if (state.load() == COUNT_CANCELLED) stop(job)
        }

        /** Sends [answer], after any earlier answer on the id, unless the count was cancelled. */
        suspend fun answer(answer: Message) {
            after?.join()
            if (!state.compareAndSet(COUNT_PENDING, COUNT_SENDING)) return
            try {
                send(answer)
            } finally {
                state.store(COUNT_SENT)
                becomeQuiet()
            }
        }

        /**
         * Stops the count, unless it answered or its answer is already going out. True when this
         * call stopped it: its answer will never be sent.
         */
        fun cancel(): Boolean {
            if (!state.compareAndSet(COUNT_PENDING, COUNT_CANCELLED)) return false
            job?.let(::stop)
            becomeQuiet()
            return true
        }

        /** The coroutine ended. One that never answered (its scope went away) never will. */
        fun finished() {
            if (state.compareAndSet(COUNT_PENDING, COUNT_CANCELLED)) becomeQuiet()
        }

        private fun stop(job: Job) {
            if (stopped.compareAndSet(false, true)) {
                job.cancel()
                drain(job, drainingCounts)
            }
        }

        private fun becomeQuiet() {
            if (after == null) quiet.complete() else after.invokeOnCompletion { quiet.complete() }
        }
    }

    /** [live] as it stood when made: the identities authenticated by then stay fixed. */
    private class FrozenRequestContext(
        live: RequestContext,
    ) : RequestContext by live {
        override val authenticatedUsers: Set<HexKey> = live.authenticatedUsers
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
        // The policy first, so an id it finds invalid is refused as invalid rather than as over
        // the cap. A refused re-REQ drops the subscription it replaces (NIP-01).
        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            cancelSubscription(cmd.subId)
            send(ClosedMessage(cmd.subId, result.reason, result.params))
            return
        }

        // Then whether a *new* subscription may open (e.g. a max_subscriptions cap); see
        // [refusal]. Checked before the cancel so the existing subscription isn't dropped only to
        // then reject its replacement.
        refusal(cmd.subId, subscriptions[cmd.subId] != null, subscriptions.size(), drainingReplays.load())?.let { reason ->
            send(ClosedMessage(cmd.subId, reason))
            return
        }

        // Cancel any existing subscription with the same id (NIP-01 spec).
        cancelSubscription(cmd.subId)

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
            // Registered first, so a replay that fails at once takes its own slot back out.
            subscriptions[cmd.subId] = req
            if (stopIfEnded(req)) return
            req.attachReplay(launchParkedReq(req, filters, probe, eose))
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
        subscriptions[cmd.subId] = req
        if (stopIfEnded(req)) return

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
                } catch (e: Throwable) {
                    // Unregistering is idempotent, and covers a wrapper that never reached the store.
                    subscription.cancel()
                    // The REQ's own cancellation (CLOSE, a replaced id, a disconnect) is expected,
                    // and a fatal VM error is not ours to catch. Any other failure, e.g. an event
                    // source's network I/O, a store's own CancellationException (a timeout on its
                    // read) or an Error such as TODO()'s, ends the subscription with a
                    // machine-readable CLOSED rather than silently dropping the coroutine.
                    if ((e is CancellationException && !isActive) || isFatal(e)) throw e
                    endWithError(req, e)
                }
            }
        // Held only while the replay runs, so a CLOSE mid-replay can stop it.
        if (!replay.isCompleted) {
            req.attachReplay(replay)
            replay.invokeOnCompletion { req.replay = null }
        }
    }

    /** A REQ that registered after the session ended: out again, and stopped. */
    private fun stopIfEnded(req: OpenReq): Boolean {
        if (!ended) return false
        subscriptions.remove(req.subId, req)
        req.cancel()
        return true
    }

    /**
     * The relay ends [req] on a failed read: one CLOSED, unless the client ended it first, and its
     * slot freed, so a CLOSE on the id afterwards finds no such subscription.
     */
    private fun endWithError(
        req: OpenReq,
        e: Throwable,
    ) {
        if (!req.end()) return
        subscriptions.remove(req.subId, req)
        send(ClosedMessage.of(req.subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
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

        private val closedFlag = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)

        /** Set by [cancel] (CLOSE, a replaced sub id or a disconnect) or [end] (a failed read). */
        val closed: Boolean get() = closedFlag.load()

        override fun onLive(
            event: Event,
            body: String,
        ) {
            if (framePrefix == null) sendScreened(subId, event) else sendLive(framePrefix, body)
        }

        fun attachReplay(job: Job) {
            replay = job
            if (closed) stop(job)
        }

        /** The client ended it: the replay is cancelled and, while it runs on, keeps its slot. */
        fun cancel() {
            if (!closedFlag.compareAndSet(false, true)) return
            replay?.let(::stop)
            subscription?.cancel()
        }

        /** The relay ends it on a failed read. True when this call ended it, false when it already was. */
        fun end(): Boolean {
            if (!closedFlag.compareAndSet(false, true)) return false
            subscription?.cancel()
            return true
        }

        private fun stop(job: Job) {
            if (stopped.compareAndSet(false, true)) {
                job.cancel()
                drain(job, drainingReplays)
            }
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
        // UNDISPATCHED: as above. The REQ is already in [subscriptions]; the coroutine first
        // parks at the live tail, which is when launch returns. A store that ignores
        // cancellation keeps calling back after a CLOSE: nothing it delivers then is sent.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val framePrefix = req.framePrefix
                if (framePrefix == null) {
                    store.query(
                        ctx = requestContext,
                        filters = filters,
                        onEach = { event -> if (!req.closed) sendScreened(req.subId, event) },
                        onEose = { if (!req.closed) eose() },
                    )
                } else {
                    store.queryRaw(
                        ctx = requestContext,
                        filters = filters,
                        onEachStored = { raw -> if (!req.closed) sendStored(framePrefix, raw, probe) },
                        onEachLive = { event, body -> if (!req.closed) req.onLive(event, body) },
                        onEose = { if (!req.closed) eose() },
                    )
                }
            } catch (e: Throwable) {
                // As on the live path: only the REQ's own cancellation ends it without a CLOSED.
                if ((e is CancellationException && !isActive) || isFatal(e)) throw e
                endWithError(req, e)
            }
        }

    // -- NIP-01: CLOSE --------------------------------------------------------

    /**
     * Ends the REQ with this id, and stops a COUNT with this id that has not answered yet,
     * both when a REQ and a COUNT share the id: Quartz's own client sends CLOSE on a query id to
     * drop a count it no longer needs. Neither is answered, as NIP-01 sends CLOSED only for an
     * end the relay chose, and a stopped count's answer never follows the CLOSE. An id that is
     * neither open nor counting is unknown, a COUNT already answered included.
     */
    private fun handleClose(cmd: CloseCmd) {
        val cancelledReq = cancelSubscription(cmd.subId)
        val count = counts[cmd.subId]
        val cancelledCount = count != null && count.cancel()
        if (cancelledCount) counts.remove(cmd.subId, count)
        if (cancelledReq || cancelledCount) return
        // Unknown, a COUNT that answered or whose answer is going out included: that answer
        // stays where it is (the next COUNT on the id waits for it) and goes out first.
        sendAfter(count, ClosedMessage(cmd.subId, "error: no such subscription"))
    }

    init {
        policy.onConnect(requestContext, ::send)
    }

    companion object {
        private val connectionIdSeq = AtomicLong(0L)

        // A COUNT's state: it moves only forward, to SENT or to CANCELLED.
        private const val COUNT_PENDING = 0
        private const val COUNT_SENDING = 1
        private const val COUNT_SENT = 2
        private const val COUNT_CANCELLED = 3

        /** Allocates the next process-unique connection id. */
        fun nextConnectionId(): Long = connectionIdSeq.fetchAndAdd(1L)
    }
}
