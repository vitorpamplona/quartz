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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Per-connection NIP-77 negentropy state and dispatch.
 *
 * Owns the map of active reconciliation sessions keyed by NEG-OPEN
 * subId, and the open/msg/close handlers. Pulled out of [RelaySession]
 * so the connection class only routes commands while this class owns
 * the negentropy lifecycle and error mapping.
 *
 * With a [scope], a NEG-OPEN's snapshot is built off the command loop, like a COUNT: a scan of a
 * large corpus held every frame behind it on the connection until it finished. NEG-MSGs on that
 * subId wait for it, in order, without holding the loop either. The map is under a lock, since a
 * disconnect clears it from another thread while an open is running.
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

        /** The last job queued on this subId (the open, then each message); null when none ran off the loop. */
        @Volatile var tail: Job? = null
    }

    private val lock = PlatformLock()
    private val sessions = HashMap<String, Entry>()

    private fun entry(subId: String): Entry? = lock.withLock { sessions[subId] }

    /** Drops [subId]'s entry if it is still [entry]. */
    private fun dropIfCurrent(
        subId: String,
        entry: Entry,
    ) {
        lock.withLock { if (sessions[subId] === entry) sessions.remove(subId) }
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
     *    map past [NegentropySettings.maxSessionsPerConnection], we
     *    send a NOTICE (matching strfry's
     *    `"too many concurrent NEG requests"`) and drop the OPEN.
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
        val replaced =
            lock.withLock {
                // Per-connection cap. Only fires when this is a NEW subId —
                // a same-subId re-open replaces the prior session 1-for-1.
                val isReopen = sessions.containsKey(cmd.subId)
                if (!isReopen && sessions.size >= settings.maxSessionsPerConnection) return@withLock null
                // NIP-77: same-subId OPEN replaces any prior session.
                sessions.put(cmd.subId, entry) ?: Entry()
            }
        if (replaced == null) {
            send(NoticeMessage("too many concurrent NEG requests"))
            return
        }
        replaced.tail?.cancel()

        val runScope = scope
        if (runScope == null) {
            build(cmd, filters, entry)
        } else {
            entry.tail = runScope.launch(start = CoroutineStart.UNDISPATCHED) { build(cmd, filters, entry) }
        }
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
                dropIfCurrent(cmd.subId, entry)
                send(NegErrMessage(cmd.subId, "error: ${e.message ?: "snapshot failed"}"))
                return
            }
        // Closed, replaced or cleared while the snapshot was built: nothing is owed.
        if (entry(cmd.subId) !== entry) return
        if (sealedStorage == null) {
            dropIfCurrent(cmd.subId, entry)
            // The cap rides along with the refusal. A client cannot discover
            // this number any other way — NIP-11 has no field for it — so
            // without it the only route to a window we WILL answer is guessing,
            // halving, one refused NEG-OPEN at a time. Every one of those costs
            // us the snapshot scan that produced this rejection, which makes
            // stating it cheaper for the relay than staying quiet.
            send(NegErrMessage(cmd.subId, "blocked: too many query results", settings.maxSyncEvents.toLong()))
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
        lock.withLock { sessions.remove(cmd.subId) }?.tail?.cancel()
    }

    /** Dropped on `RelaySession.cancelAllSubscriptions`. */
    fun clear() {
        val dropped =
            lock.withLock {
                val all = sessions.values.toList()
                sessions.clear()
                all
            }
        dropped.forEach { it.tail?.cancel() }
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
            if (response != null) send(response)
        } catch (_: Exception) {
            // strfry sends `PROTOCOL-ERROR` on library reconcile()
            // parse failure and tears the session down.
            dropIfCurrent(subId, entry)
            send(NegErrMessage(subId, "PROTOCOL-ERROR"))
        }
    }
}
