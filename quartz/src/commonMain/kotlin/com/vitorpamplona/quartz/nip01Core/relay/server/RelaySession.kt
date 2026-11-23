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
import kotlin.concurrent.atomics.incrementAndFetch

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

    /**
     * How many REQs [OpenReq.fail] ended since [dropFailedReqs] last ran. A failure happens on
     * the replay's coroutine, which may not touch [subscriptions]: the command loop may be
     * reusing that sub id at the same moment. So it only flags the REQ, and the command loop
     * drops it before it next counts open subscriptions.
     */
    private val failedReqs = AtomicInt(0)

    /**
     * COUNTs by query id, from the frame that asked until the command loop drops them once they
     * ended ([noteAdded]), so a re-COUNT, a CLOSE or a refusal on the id, or a disconnect, can
     * cancel one. Only the command loop adds or removes entries (a disconnect on another thread
     * only cancels and clears them), so the one under an id is the latest COUNT there.
     */
    private val counts = LargeCache<String, PendingCount>()

    /**
     * Per id, what the command loop cancelled there (or found answering) that may still be
     * sending a frame: the next frame on that id, a replacement REQ's or COUNT's, a refusal's
     * CLOSED or a late CLOSE's reply, goes out after it, so nothing from the old request can land
     * after it. Command loop only.
     */
    private val tails = LargeCache<String, Job>()

    /** Entries the command loop added to [counts] and [tails] since it last dropped ended ones. */
    private var entriesAdded = 0

    /**
     * Set by [end]: the session is over. A disconnect handled on another thread can end the
     * session while the command loop is registering a REQ or COUNT; what registers after that
     * checks this and stops itself, and the command loop takes no frame once it is set.
     */
    @Volatile
    private var sessionEnded = false

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

    /** Command loop only: the REQ on [subId] is cancelled, and what follows on the id waits for its last frame. */
    private fun cancelSubscription(subId: String): Boolean =
        subscriptions.remove(subId)?.let {
            it.cancel()
            waitOn(subId, it.quiet)
            true
        } ?: false

    /**
     * Command loop only: cancels the COUNT on [id], unless its answer was already claimed, and
     * makes what follows on the id wait for that answer. True when it stopped one, which then
     * never answers.
     */
    private fun cancelCount(id: String): Boolean {
        val count = counts.get(id) ?: return false
        val stopped = count.cancel()
        if (stopped) counts.remove(id)
        waitOn(id, count.quiet)
        return stopped
    }

    /** A job that completes once both [a] and [b] have. */
    private fun bothOf(
        a: Job,
        b: Job,
    ): Job {
        val both = Job()
        val left = AtomicInt(2)
        a.invokeOnCompletion { if (left.addAndFetch(-1) == 0) both.complete() }
        b.invokeOnCompletion { if (left.addAndFetch(-1) == 0) both.complete() }
        return both
    }

    /** What may still be sending on [id], or null when nothing is. */
    private fun tailOf(id: String): Job? = tails.get(id)?.takeUnless { it.isCompleted }

    /** Adds [quiet], the end of an earlier request's sending, to what the next frame on [id] waits for. */
    private fun waitOn(
        id: String,
        quiet: Job,
    ) {
        if (quiet.isCompleted) return
        val tail = tailOf(id)
        tails.put(id, if (tail == null) quiet else bothOf(tail, quiet))
        noteAdded()
    }

    /**
     * Sends [message], a reply on [id], now, or once what may still be sending there has gone:
     * deferred, never awaited, so the command loop goes on. Later frames on the id wait for it.
     */
    private fun sendOn(
        id: String,
        message: Message,
    ) {
        val tail = tailOf(id)
        if (tail == null) {
            send(message)
            return
        }
        val sent = Job()
        tail.invokeOnCompletion {
            if (!sessionEnded) send(message)
            sent.complete()
        }
        tails.put(id, sent)
    }

    /**
     * Drops ended COUNTs and quiet tails, so ids a client used once do not pile up. Runs once the
     * command loop added as many entries as the two maps hold (and at least 64), so each add pays
     * for O(1) of the scan.
     */
    private fun noteAdded() {
        if (++entriesAdded < maxOf(64, counts.size() + tails.size())) return
        entriesAdded = 0
        val endedCounts = ArrayList<String>()
        counts.forEach { id, count -> if (count.isEnded) endedCounts.add(id) }
        endedCounts.forEach { counts.remove(it) }
        val quietTails = ArrayList<String>()
        tails.forEach { id, tail -> if (tail.isCompleted) quietTails.add(id) }
        quietTails.forEach { tails.remove(it) }
    }

    fun cancelAllSubscriptions() {
        subscriptions.forEach { _, req -> req.cancel() }
        subscriptions.clear()
        counts.forEach { _, count -> count.cancel() }
        counts.clear()
        negentropy.clear()
    }

    /**
     * Ends the session: nothing it holds keeps running, and nothing registered after this (a frame
     * the command loop was still handling, on another thread) runs or answers. [close] and the
     * server's shutdown end a session; [cancelAllSubscriptions] alone leaves it serving.
     */
    internal fun end() {
        // First, so a REQ or COUNT registering meanwhile either is swept below or sees it.
        sessionEnded = true
        cancelAllSubscriptions()
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
        end()
        onClose(this)
    }

    /**
     * Processes a raw JSON message from a client.
     *
     * Parses the message as a NIP-01 command and dispatches it.
     */
    suspend fun receive(command: String) {
        if (sessionEnded) return
        policy.acceptMessage(command)?.let { reason ->
            refuseFrame(command, reason)
            return
        }

        val cmd =
            try {
                OptimizedJsonMapper.fromJsonToCommand(command)
            } catch (_: Exception) {
                // An AUTH carrying an event of the wrong kind fails here, not in the auth
                // policy, and a REQ whose filter has a bad field fails here too: each is still
                // owed its answer by id.
                refuseFrame(command, "invalid: could not parse message", notice = "error: could not parse message")
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
        if (sessionEnded) return
        policy.acceptMessage(command)?.let { reason ->
            refuseFrame(command, reason)
            return
        }
        receive(parsed)
    }

    /**
     * Answers a frame that [IRelayPolicy.acceptMessage] refused or that did not parse, by the id
     * it carries wherever that id can be read. An EVENT is owed its `OK` (NIP-01), as is an AUTH
     * (NIP-42), and a client pipelining EVENTs pairs OKs by id; a REQ, COUNT or CLOSE is answered
     * with `CLOSED` on its sub id, since a NOTICE names no subscription and the client would wait
     * for that REQ's EOSE or COUNT forever. Only a frame with no readable id gets a NOTICE, with
     * [notice] as its text.
     */
    private fun refuseFrame(
        command: String,
        reason: String,
        notice: String = reason,
    ) {
        val eventId = eventIdOfFrame(command)
        if (eventId != null) {
            send(OkMessage(eventId, false, reason))
            return
        }
        val frame = subIdOfFrame(command)
        if (frame == null) {
            send(NoticeMessage(notice))
            return
        }
        closeRefused(frame.subId, reason)
    }

    /**
     * Answers a REQ, COUNT or CLOSE that cannot run with `CLOSED` [reason] on [subId]. A client
     * takes that CLOSED as the end of whatever it holds under [subId], so an open subscription
     * there ends here too: the one a REQ would have replaced, the one a CLOSE named, and the
     * one a COUNT reusing a REQ's id would otherwise leave streaming to a client that dropped it.
     */
    private fun closeRefused(
        subId: String,
        reason: String,
    ) {
        cancelSubscription(subId)
        cancelCount(subId)
        sendOn(subId, ClosedMessage(subId, reason))
    }

    /**
     * Dispatches an already-parsed command. [IRelayPolicy.acceptMessage] is
     * not run here — it judges wire text — so a caller that has the text
     * uses the overload that takes both.
     */
    suspend fun receive(cmd: Command) {
        // An ended session takes no frame: work that answers inline would otherwise still answer.
        if (sessionEnded) return
        if (!cmd.isValid()) {
            // A REQ or COUNT with a sub id but no filter is still answered on that id.
            when {
                cmd is ReqCmd && cmd.subId.isNotEmpty() -> closeRefused(cmd.subId, "invalid: a REQ needs at least one filter")
                cmd is CountCmd && cmd.queryId.isNotEmpty() -> closeRefused(cmd.queryId, "invalid: a COUNT needs at least one filter")
                else -> send(NoticeMessage("error: invalid command"))
            }
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

    /**
     * Answers a COUNT off the command loop, like a REQ's replay: a backend that limits a
     * connection's concurrent queries (one search permit per connection) would otherwise hold
     * the loop here, waiting for a running REQ's permit, with the CLOSE that frees it unread
     * behind this COUNT. Its one answer, COUNT or CLOSED, goes out when the backend returns.
     *
     * A COUNT is cancelled at once by a COUNT on its id (a replacement, as a REQ replaces a REQ),
     * a CLOSE or a refusal on its id, or a disconnect, and a cancelled COUNT never answers, even
     * when its backend ignores the cancellation and returns anyway: the answer is claimed against
     * the cancel and sent outside any lock. One whose answer was claimed first is not cancelled:
     * that answer goes out, and whatever follows on the id (the replacement's answer, the
     * refusal's CLOSED, the CLOSE's "no such subscription") goes after it, without the command
     * loop waiting for it. How many COUNTs a connection may run at once, and a backend that keeps
     * working after its cancellation, are the backend's to bound, as it bounds REQ replays.
     */
    private fun handleCount(cmd: CountCmd) {
        val queryId = cmd.queryId

        // Before the policy, so the replaced COUNT's answer follows neither this one's nor its refusal.
        cancelCount(queryId)

        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            sendOn(queryId, ClosedMessage(queryId, result.reason, result.params))
            return
        }

        // Policy may rewrite filters to match the user's access level.
        val filters = (result as PolicyResult.Accepted).cmd.filters

        if (sessionEnded) return
        val count = PendingCount(tailOf(queryId))
        counts.put(queryId, count)
        noteAdded()
        // UNDISPATCHED: a count that does not suspend answers inline, as it did on the loop.
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val answer =
                    try {
                        CountMessage(queryId, store.countResult(requestContext, filters))
                    } catch (e: CancellationException) {
                        // A cancelled COUNT owes nothing. A backend's own CancellationException
                        // (a withTimeout inside it) is a failed count, still owed its one answer.
                        if (!isActive) throw e
                        ClosedMessage.of(queryId, MachineReadablePrefix.ERROR, e.message ?: "count failed")
                    } catch (e: Throwable) {
                        // An Error from the backend (TODO()'s NotImplementedError) is a failed
                        // count too, rather than an escape into the server's scope; only a fatal
                        // VM error is rethrown.
                        if (isFatal(e)) throw e
                        ClosedMessage.of(queryId, MachineReadablePrefix.ERROR, e.message ?: "count failed")
                    }
                count.answer(answer)
            }
        count.attach(job)
        // A disconnect on another thread may have swept [counts] before this landed in it.
        if (sessionEnded) count.cancel()
    }

    /**
     * One COUNT, from the frame that asked until its coroutine ends: whether it may still answer,
     * and what on its id it must answer after. Its state only moves forward: pending, then
     * sending and sent once it claims its answer, or cancelled.
     */
    private inner class PendingCount(
        /** What on this id may still be sending: this COUNT answers after it. */
        private val after: Job?,
    ) {
        private val state = AtomicInt(COUNT_PENDING)

        @Volatile
        private var job: Job? = null

        /** Completes once this COUNT sends nothing more, and [after] has gone too. */
        val quiet: CompletableJob = Job()

        /** Its coroutine ended: nothing of it runs any more. */
        val isEnded: Boolean get() = job?.isCompleted ?: (state.load() == COUNT_CANCELLED)

        fun attach(job: Job) {
            this.job = job
            if (state.load() == COUNT_CANCELLED) job.cancel()
            // One that ended without answering (its scope went away) never will.
            job.invokeOnCompletion { if (state.compareAndSet(COUNT_PENDING, COUNT_CANCELLED)) becomeQuiet() }
        }

        /** Claims and sends [answer], after what it must follow, unless the COUNT was cancelled. */
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

        /** Cancels the COUNT unless its answer was claimed. True when this call stopped it. */
        fun cancel(): Boolean {
            if (!state.compareAndSet(COUNT_PENDING, COUNT_CANCELLED)) return false
            job?.cancel()
            becomeQuiet()
            return true
        }

        private fun becomeQuiet() {
            if (after == null) quiet.complete() else after.invokeOnCompletion { quiet.complete() }
        }
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

    /**
     * Gives back the slots of REQs their backend failed, which the client has been told are
     * CLOSED and so will never CLOSE. Runs on the command loop, the only place [subscriptions]
     * gains or replaces entries, so the entry under a failed REQ's sub id is still that REQ.
     */
    private fun dropFailedReqs() {
        if (failedReqs.load() == 0) return
        // Reset before scanning: a REQ failing meanwhile is flagged before it counts, so it is
        // either seen by this scan or counted for the next one.
        failedReqs.store(0)
        subscriptions.filter { _, req -> req.failed }.forEach {
            subscriptions.remove(it.subId)
            // Its CLOSED may still be going out: what follows on the id goes after it.
            waitOn(it.subId, it.quiet)
        }
    }

    // -- NIP-01: REQ ----------------------------------------------------------
    private fun handleReq(cmd: ReqCmd) {
        dropFailedReqs()

        // Ask the policy whether a *new* subscription may open (e.g. a
        // max_subscriptions cap). A re-REQ on an existing id replaces it
        // 1-for-1 and doesn't grow the count, so it's exempt. Checked before
        // the cancel so the existing subscription isn't dropped only to then
        // reject its replacement, and before [IRelayPolicy.accept], so a REQ the
        // cap refuses spends none of the client's rate limit.
        if (!subscriptions.containsKey(cmd.subId)) {
            policy.acceptSubscription(cmd.subId, subscriptions.size())?.let { reason ->
                sendOn(cmd.subId, ClosedMessage(cmd.subId, reason))
                return
            }
        }

        // Cancel any existing subscription with the same id (NIP-01 spec).
        cancelSubscription(cmd.subId)

        val result = policy.accept(cmd)
        if (result is PolicyResult.Rejected) {
            sendOn(cmd.subId, ClosedMessage(cmd.subId, result.reason, result.params))
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

        if (sessionEnded) return
        // The REQ this one replaced, or one closed or failed on its id, may still be sending a
        // frame: this one's replay starts after it, so no old frame lands after a new one.
        val after = tailOf(cmd.subId)
        val req = OpenReq(cmd.subId, framePrefix)
        val live = store as? LiveSubscriptionBackend
        if (live == null) {
            req.replay = launchParkedReq(req, filters, probe, eose, after)
            subscriptions.put(cmd.subId, req)
            stopIfEnded(req)
            return
        }

        // Registered before this returns, so the slot is in [subscriptions] before any
        // CLOSE can look for it; live events may already be arriving on the listener.
        val subscription =
            try {
                live.openSubscription(requestContext, filters, req)
            } catch (e: Exception) {
                // A wrapper refusing the REQ outright (a gate) answers like a failed replay.
                sendOn(cmd.subId, ClosedMessage.of(cmd.subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
                return
            }
        req.subscription = subscription
        subscriptions.put(cmd.subId, req)
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
                    // Live events accepted meanwhile wait for the replay, so they follow it too.
                    after?.join()
                    // A replay that ignores cancellation keeps producing rows after a
                    // CLOSE: none of them, nor its EOSE, may reach the client.
                    if (framePrefix == null) {
                        // Screened path: every event is materialized so the
                        // policy can veto it per session.
                        subscription.replay(
                            onEach = { event -> req.emit { sendScreened(cmd.subId, event) } },
                            onEose = { req.emit { eose() } },
                        )
                    } else {
                        subscription.replayRaw(
                            onEachStored = { raw -> req.emit { sendStored(framePrefix, raw, probe) } },
                            onEose = { req.emit { eose() } },
                        )
                    }
                } catch (e: CancellationException) {
                    // Unregistering is idempotent, and covers a wrapper that never reached the store.
                    subscription.cancel()
                    // Closed (a CLOSE, a replaced sub id, a disconnect): expected. A backend's own
                    // CancellationException (a withTimeout inside it) is a failed replay, which
                    // ends with a CLOSED and gives its slot back like any other.
                    if (!isActive) throw e
                    req.fail(e)
                } catch (e: Throwable) {
                    // A backend failure (e.g. an event source's network I/O, or an Error such
                    // as TODO()'s) ends the subscription with a machine-readable CLOSED rather
                    // than silently dropping the coroutine; only a fatal VM error is rethrown.
                    subscription.cancel()
                    if (isFatal(e)) throw e
                    req.fail(e)
                }
            }
        // Held only while the replay runs, so a CLOSE mid-replay can stop it.
        if (!replay.isCompleted) {
            req.replay = replay
            replay.invokeOnCompletion { req.replay = null }
        }
    }

    /**
     * Stops [req] if a disconnect swept the session while the command loop was registering it.
     * The command loop is the only place [subscriptions] gains entries, so the one under its sub
     * id is still [req].
     */
    private fun stopIfEnded(req: OpenReq): Boolean {
        if (!sessionEnded) return false
        subscriptions.remove(req.subId)
        req.cancel()
        return true
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

        /** Set by [cancel] (a CLOSE, a replaced sub id, a refusal on it, a disconnect) or [fail]. */
        @Volatile
        var closed = false

        /** Set by [fail]: the backend failed, the client has its CLOSED, and the slot is to go. */
        @Volatile
        var failed = false

        /** Claimed once, by [cancel] or [fail], whichever ends this REQ first. */
        private val ended = AtomicBoolean(false)

        /** This REQ's frames being sent right now, on any thread. */
        val sending = AtomicInt(0)

        /**
         * Completes once this REQ is closed and its last frame has gone: nothing of it is sent
         * after, and what follows on its id waits for it.
         */
        val quiet: CompletableJob = Job()

        /**
         * Sends a frame of this REQ ([send]) unless it is closed. Every frame goes through here,
         * so once [cancel] has run no new frame starts, and [quiet] waits for one already started.
         */
        inline fun emit(send: () -> Unit) {
            sending.addAndFetch(1)
            try {
                if (!closed) send()
            } finally {
                if (sending.addAndFetch(-1) == 0 && closed) quiet.complete()
            }
        }

        override fun onLive(
            event: Event,
            body: String,
        ) = emit {
            if (framePrefix == null) sendScreened(subId, event) else sendLive(framePrefix, body)
        }

        fun cancel() {
            closed = true
            ended.store(true)
            // A frame on its way out completes [quiet] as it leaves [emit].
            if (sending.load() == 0) quiet.complete()
            replay?.cancel()
            subscription?.cancel()
        }

        /**
         * Ends this REQ on a backend failure with a machine-readable `CLOSED`, and flags its
         * slot for [dropFailedReqs]. Silent when a CLOSE, a replaced sub id or a disconnect
         * ended it first: the client already let go of it, and a reused sub id's CLOSED would
         * read as the new subscription's.
         */
        fun fail(e: Throwable) {
            sending.addAndFetch(1)
            try {
                if (!ended.compareAndSet(expectedValue = false, newValue = true)) return
                closed = true
                failed = true
                failedReqs.incrementAndFetch()
                send(ClosedMessage.of(subId, MachineReadablePrefix.ERROR, e.message ?: "query failed"))
            } finally {
                if (sending.addAndFetch(-1) == 0 && closed) quiet.complete()
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
        after: Job?,
    ): Job =
        // UNDISPATCHED: as above. The coroutine first parks at the live
        // tail, which is when launch returns and the job lands in
        // [subscriptions]; commands on this connection are processed
        // sequentially, so nothing can target the sub earlier.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // After what may still be sending on its id, as on the live path.
                after?.join()
                val framePrefix = req.framePrefix
                if (framePrefix == null) {
                    store.query(
                        ctx = requestContext,
                        filters = filters,
                        // Guarded as the live-backend path is: a backend that answers after its
                        // cancellation must not reach a client that closed this sub.
                        onEach = { event -> req.emit { sendScreened(req.subId, event) } },
                        onEose = { req.emit { eose() } },
                    )
                } else {
                    store.queryRaw(
                        ctx = requestContext,
                        filters = filters,
                        onEachStored = { raw -> req.emit { sendStored(framePrefix, raw, probe) } },
                        onEachLive = { event, body -> req.onLive(event, body) },
                        onEose = { req.emit { eose() } },
                    )
                }
            } catch (e: CancellationException) {
                // As on the live path: only this REQ's own cancellation is a close.
                if (!isActive) throw e
                req.fail(e)
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                req.fail(e)
            }
        }

    // -- NIP-01: CLOSE --------------------------------------------------------

    /**
     * Cancels the REQ and the COUNT on this id, and sends nothing, as NIP-01 sends CLOSED only
     * for an end the relay chose. An id that holds neither is unknown, a COUNT whose answer was
     * already claimed included: that reply goes out after the answer.
     */
    private fun handleClose(cmd: CloseCmd) {
        val cancelledReq = cancelSubscription(cmd.subId)
        val cancelledCount = cancelCount(cmd.subId)
        if (!cancelledReq && !cancelledCount) {
            sendOn(cmd.subId, ClosedMessage(cmd.subId, "error: no such subscription"))
        }
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
