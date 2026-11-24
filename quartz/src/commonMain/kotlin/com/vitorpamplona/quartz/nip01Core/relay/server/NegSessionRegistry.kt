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

import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.NoticeMessage
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.SessionBackend
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.IRelayPolicy
import com.vitorpamplona.quartz.nip01Core.relay.server.policies.PolicyResult
import com.vitorpamplona.quartz.nip77Negentropy.NegCloseCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegErrMessage
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegentropyServerSession
import com.vitorpamplona.quartz.nip77Negentropy.NegentropySettings
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Per-connection NIP-77 negentropy state and dispatch.
 *
 * Owns the map of active reconciliation sessions keyed by NEG-OPEN
 * subId, and the open/msg/close handlers. Pulled out of [RelaySession]
 * so the connection class only routes commands while this class owns
 * the negentropy lifecycle and error mapping.
 *
 * With a [scope], a NEG-OPEN's snapshot is built off the command loop, on the IO pool: a
 * scan of a large corpus held every frame behind it on the connection until it finished, and a
 * store that never suspends (SQLite) ran it on the connection's own thread even when launched
 * from it. NEG-MSGs on that subId wait for it, in order, without holding the loop either. The map
 * is under a lock, since a disconnect clears it from another thread while an open is running.
 *
 * A build ends with its session: a NEG-CLOSE, a re-open or a disconnect cancels it, with the
 * messages queued behind it. One that keeps running after that (a store that ignores the
 * cancellation) still holds its slot under [NegentropySettings.maxSessionsPerConnection] until
 * it ends, so a client looping NEG-OPEN / NEG-CLOSE cannot queue scans without bound. A reply goes
 * out only while its session is the current one on its subId, checked under the lock it is sent
 * under, so a replaced session's answer never follows its replacement's.
 *
 * The snapshot is taken as the caller ([context]): an observer-relative store (STORE-O01)
 * answers a NEG-OPEN the same set it answers that connection's REQ and COUNT.
 *
 * Defaults match strfry (`hoytech/strfry`) so a Geode relay reconciles
 * with the same round-trip shape and the same operator-visible
 * protections — see [NegentropySettings].
 */
class NegSessionRegistry(
    private val store: SessionBackend,
    private val send: (Message) -> Unit,
    private val settings: NegentropySettings = NegentropySettings.Default,
    /** The connection the snapshots are taken for; null takes them with no caller identity. */
    private val context: RequestContext? = null,
    /** Where NEG-OPENs build their snapshot; null builds them inline, on the caller's coroutine. */
    private val scope: CoroutineScope? = null,
) {
    /** The constructor before [context] and [scope], kept so dependents compiled against it still link. */
    @Deprecated("Use the constructor with context and scope.", level = DeprecationLevel.HIDDEN)
    constructor(
        store: SessionBackend,
        send: (Message) -> Unit,
        settings: NegentropySettings = NegentropySettings.Default,
    ) : this(store, send, settings, null, null)

    /** One subId: its session once the snapshot is built, and the work queued on it. */
    private class Entry {
        @Volatile var session: NegentropyServerSession? = null

        /** The snapshot build, when it runs off the loop. */
        @Volatile var build: Job? = null

        /** The last job queued on this subId (the open, then each message); null when none ran off the loop. */
        @Volatile var tail: Job? = null

        /** Its build has not ended yet. Under [lock]. */
        var building = false

        /** Stops the build and every message queued behind it. */
        fun cancel() {
            build?.cancel()
            tail?.cancel()
        }
    }

    /**
     * Where a build runs: the IO pool when the connection's scope runs on [Dispatchers.Default]
     * (or names no dispatcher, which means it). A build is a blocking scan of up to
     * [NegentropySettings.maxSyncEvents] rows; on the cores-sized Default pool a few concurrent
     * NEG-OPENs would starve the CPU work (verification, JSON) of every connection. A dispatcher
     * the app chose for the scope is kept, as is a test's.
     */
    private val buildContext: CoroutineContext =
        when (scope?.coroutineContext?.get(ContinuationInterceptor)) {
            null, Dispatchers.Default -> Dispatchers.IO
            else -> EmptyCoroutineContext
        }

    private val lock = PlatformLock()
    private val sessions = HashMap<String, Entry>()

    /** Entries no longer in [sessions] whose build still runs: each holds a slot until it ends. Under [lock]. */
    private val draining = HashSet<Entry>()

    private fun entry(subId: String): Entry? = lock.withLock { sessions[subId] }

    /** Removes [subId]'s entry, keeping its slot while its build runs. Under [lock]. */
    private fun removeLocked(subId: String): Entry? =
        sessions.remove(subId)?.also {
            if (it.building) draining.add(it)
        }

    /**
     * Sends [message] if [entry] is still [subId]'s session, under the lock, so a re-open or a close
     * cannot land between the check and the send. With [drop], the session ends with it.
     */
    private fun sendIfCurrent(
        subId: String,
        entry: Entry,
        message: Message,
        drop: Boolean = false,
    ) {
        lock.withLock {
            if (sessions[subId] !== entry) return
            if (drop) removeLocked(subId)
            send(message)
        }
    }

    /**
     * Open a reconciliation session. The relay snapshots the matching
     * `(created_at, id)` pairs at this instant — concurrent inserts
     * during the sync are not surfaced; clients re-open if they want
     * fresh state.
     *
     * Access control is the policy's NEG-OPEN hook, which by default runs
     * the REQ rules: a relay that requires AUTH or has kind/pubkey
     * allow-deny lists applies the same rules to NEG-OPEN as it does to
     * subscription REQs. Page limits are the exception — see
     * [com.vitorpamplona.quartz.nip01Core.relay.server.policies.LimitsPolicy] — since the
     * snapshot cap below bounds a reconcile.
     *
     * Two strfry-parity protections fire here:
     *  - **Per-connection session cap.** If an OPEN would push the
     *    sessions, plus the builds still running after their session
     *    ended, past [NegentropySettings.maxSessionsPerConnection], we
     *    send a NOTICE (matching strfry's
     *    `"too many concurrent NEG requests"`) and drop the OPEN. A
     *    same-subId re-open first ends the session it replaces, so it
     *    is refused only when that one's build is still running at the cap.
     *  - **Snapshot size cap.** The store is asked for at most
     *    `maxSyncEvents + 1` entries; if the +1 sentinel comes back,
     *    the corpus exceeds the cap and we send NEG-ERR
     *    `"blocked: too many query results"` (matching strfry).
     */
    suspend fun open(
        cmd: NegOpenCmd,
        policy: IRelayPolicy,
    ) {
        val gate = policy.accept(cmd)
        if (gate is PolicyResult.Rejected) {
            send(NegErrMessage(cmd.subId, gate.reason))
            return
        }
        val filters = listOf((gate as PolicyResult.Accepted).cmd.filter)

        val entry = Entry()
        val runScope = scope
        // Lazy, so it is the entry's before anything can cancel the entry, and dispatched once
        // started: launched UNDISPATCHED, a store that never suspends ran the whole scan inline.
        val job =
            runScope?.launch(buildContext, start = CoroutineStart.LAZY) { build(cmd, filters, entry) }?.also { job ->
                entry.build = job
                entry.tail = job
                // Runs however the job ends, even cancelled before it started.
                job.invokeOnCompletion {
                    lock.withLock {
                        entry.building = false
                        draining.remove(entry)
                    }
                }
            }
        var replaced: Entry? = null
        val admitted =
            lock.withLock {
                // NIP-77: same-subId OPEN replaces any prior session; it ends here, first.
                replaced = removeLocked(cmd.subId)
                // Per-connection cap, over the sessions and the builds that outlived theirs.
                if (sessions.size + draining.size >= settings.maxSessionsPerConnection) return@withLock false
                sessions[cmd.subId] = entry
                if (job != null && !job.isCompleted) entry.building = true
                true
            }
        replaced?.cancel()
        if (!admitted) {
            job?.cancel()
            send(NoticeMessage("too many concurrent NEG requests"))
            return
        }

        if (job == null) build(cmd, filters, entry) else job.start()
    }

    private suspend fun build(
        cmd: NegOpenCmd,
        filters: List<Filter>,
        entry: Entry,
    ) {
        // Sealed storage may come from the backend's snapshot cache — a
        // repeated NEG-OPEN of the same filter with no writes in between
        // (the mirror-heartbeat pattern) skips the scan + sort entirely.
        // `null` = matching set exceeds the cap (strfry-parity error).
        val sealedStorage =
            try {
                val ctx = context
                if (ctx != null) {
                    store.sealedNegentropyStorage(ctx, filters, maxEntries = settings.maxSyncEvents)
                } else {
                    store.sealedNegentropyStorage(filters, maxEntries = settings.maxSyncEvents)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendIfCurrent(cmd.subId, entry, NegErrMessage(cmd.subId, "error: ${e.message ?: "snapshot failed"}"), drop = true)
                return
            }
        // Closed, replaced or cleared while the snapshot was built: nothing is owed.
        if (entry(cmd.subId) !== entry) return
        if (sealedStorage == null) {
            // The cap rides along with the refusal. A client cannot discover
            // this number any other way — NIP-11 has no field for it — so
            // without it the only route to a window we WILL answer is guessing,
            // halving, one refused NEG-OPEN at a time. Every one of those costs
            // us the snapshot scan that produced this rejection, which makes
            // stating it cheaper for the relay than staying quiet.
            sendIfCurrent(cmd.subId, entry, NegErrMessage(cmd.subId, "blocked: too many query results", settings.maxSyncEvents.toLong()), drop = true)
            return
        }

        val session =
            NegentropyServerSession(
                subId = cmd.subId,
                sealedStorage = sealedStorage,
                frameSizeLimit = settings.frameSizeLimit,
            )
        entry.session = session

        runMessage(cmd.subId, entry, session) { it.processMessage(cmd.initialMessage) }
    }

    /**
     * Process a follow-up NEG-MSG. strfry-parity wording for the
     * unknown-subId case: `"closed: unknown subscription handle"`.
     */
    fun msg(cmd: NegMsgCmd) {
        val entry = entry(cmd.subId)
        if (entry == null) {
            send(NegErrMessage(cmd.subId, "closed: unknown subscription handle"))
            return
        }
        val before = entry.tail
        val runScope = scope
        if (before == null || before.isCompleted || runScope == null) {
            process(cmd, entry)
        } else {
            // The open (or an earlier message) is still running: this one goes after it, in order.
            entry.tail =
                runScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    before.join()
                    process(cmd, entry)
                }
        }
    }

    private fun process(
        cmd: NegMsgCmd,
        entry: Entry,
    ) {
        val session = entry.session
        if (session == null || entry(cmd.subId) !== entry) {
            send(NegErrMessage(cmd.subId, "closed: unknown subscription handle"))
            return
        }
        runMessage(cmd.subId, entry, session) { it.processMessage(cmd.message) }
    }

    /**
     * Spec: clients send NEG-CLOSE to free server-side state.
     * Silent no-op if the session is unknown — there's no authoritative
     * error response in NIP-77 for an unknown close.
     */
    fun close(cmd: NegCloseCmd) {
        lock.withLock { removeLocked(cmd.subId) }?.cancel()
    }

    /** Dropped on `RelaySession.cancelAllSubscriptions`. */
    fun clear() {
        val dropped =
            lock.withLock {
                val all = sessions.keys.toList()
                all.mapNotNull { removeLocked(it) }
            }
        dropped.forEach { it.cancel() }
    }

    /** Test/diagnostic accessor. */
    val activeSessionCount: Int get() = lock.withLock { sessions.size }

    private inline fun runMessage(
        subId: String,
        entry: Entry,
        session: NegentropyServerSession,
        block: (NegentropyServerSession) -> Message?,
    ) {
        try {
            val response = block(session)
            if (response != null) sendIfCurrent(subId, entry, response)
        } catch (_: Exception) {
            // strfry sends `PROTOCOL-ERROR` on library reconcile()
            // parse failure and tears the session down.
            sendIfCurrent(subId, entry, NegErrMessage(subId, "PROTOCOL-ERROR"), drop = true)
        }
    }
}
