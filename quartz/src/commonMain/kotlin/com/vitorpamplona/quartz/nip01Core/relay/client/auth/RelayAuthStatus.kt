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
package com.vitorpamplona.quartz.nip01Core.relay.client.auth

import androidx.collection.LruCache
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class RelayAuthStatus {
    // Keeps track of auth responses to update the relay with all filters
    // after the authentication happen.
    // Sized generously: one connection may authenticate as many identities at once — the
    // user plus every Concord plane stream key hosted on that relay (control + channels) —
    // and if older entries roll off, OK-tracking / hasFinishedAllAuths() accounting degrades.
    private val authResponseWatcher: LruCache<HexKey, AuthEventReceiptStatus> = LruCache(200)

    // Avoids sending multiple replies for each auth.
    private val uniqueAuthChallengesSent: LruCache<ChallengePair, ChallengePair> = LruCache(200)

    // Latest epoch-second at which a tracked AUTH event received a successful OK.
    // Read by RelayAuthSnapshot consumers for staleness checks (e.g. proactive
    // re-AUTH on window focus).
    @Volatile
    private var lastAuthSuccessAt: Long? = null

    // The most recent challenge the relay sent on this connection. NIP-42: the challenge
    // "is valid for the duration of the connection or until another challenge is sent",
    // and a client "must have a stored challenge associated with that relay so it can act
    // upon that in response to the auth-required CLOSED message". We keep it so a REQ that
    // is refused with `auth-required:` AFTER the initial AUTH (e.g. a Concord channel-plane
    // REQ mounted once the control plane folds and reveals new stream keys) can be
    // re-authenticated with the folded-in keys without waiting for the relay to re-challenge.
    @Volatile
    private var lastChallenge: String? = null

    // Sign attempts currently with the responder for this connection. Bumped BEFORE the
    // signing coroutine is launched and dropped in its finally, so the window is closed on
    // every exit — success, empty result, decline, throw, or cancellation.
    //
    // Deliberately separate from [authResponseWatcher]: that tracks AUTH events already SENT
    // (and drives [hasFinishedAllAuths], which the re-auth coalescing depends on), whereas
    // this covers the gap before one exists. Nothing is on the wire yet, so folding the two
    // would change the coalescing rule; keeping them apart leaves every existing decision
    // byte-identical and only adds the ability to see the gap.
    private val signingInFlight = AtomicInt(0)

    // AUTHs accepted on this connection — see [RelayAuthSnapshot.successCount].
    private val successCount = AtomicInt(0)

    // 1 while an accepted AUTH is owed a re-sync of the connection's filters and outbox. One
    // round can authenticate several identities, and re-syncing on each accepted one sent every
    // refused event once per identity. See [RelayAuthenticator]'s checkAuthResults.
    private val resyncOwed = AtomicInt(0)

    // One signing round per connection at a time. A challenge that arrives while a round signs is
    // queued (the newest wins: NIP-42 makes a challenge valid "until another challenge is sent") and
    // signed when the round ends, instead of launching a second signer prompt beside the first.
    private val roundLock = PlatformLock()
    private var signingRound = false
    private var queuedChallenge: String? = null
    private var queuedInteractive = false

    // The challenge the running round is signing, and whether it may prompt. A challenge the relay
    // repeats while it is signed is answered by that signature: queued, it signed the same round
    // again, a second prompt on an external signer for an AUTH the dedup then never sent.
    private var signingChallenge: String? = null
    private var signingInteractive = false

    // Frames (REQ/COUNT ids, EVENT ids) refused `auth-required:` after an AUTH on this connection
    // was accepted, each with the challenge it was retried on. See [firstLateRefusal].
    private val lateRefusalsRetried: LruCache<ChallengePair, ChallengePair> = LruCache(500)

    // Guards [saveAuthSubmission]'s check-then-put.
    private val submissionLock = PlatformLock()

    /**
     * Starts a signing round on [challenge]: true when the caller should sign now, false when a
     * round is already signing and [challenge] was queued behind it (see [endRound]).
     */
    fun beginRound(
        challenge: String,
        interactive: Boolean,
    ): Boolean =
        roundLock.withLock {
            if (signingRound) {
                queuedChallenge = challenge
                queuedInteractive = queuedInteractive || interactive
                false
            } else {
                signingRound = true
                signingChallenge = challenge
                signingInteractive = interactive
                true
            }
        }

    /**
     * The current round ended. Returns the challenge queued meanwhile, with whether it may prompt,
     * for the caller to sign next in the same round; null when nothing is queued (the round is over).
     * A queued challenge equal to the one just signed is dropped, unless it may prompt and the
     * round just signed could not: that signature already answered it.
     */
    fun endRound(): Pair<String, Boolean>? =
        roundLock.withLock {
            val next = queuedChallenge
            val interactive = queuedInteractive
            queuedChallenge = null
            queuedInteractive = false
            if (next == null || (next == signingChallenge && (signingInteractive || !interactive))) {
                signingRound = false
                signingChallenge = null
                signingInteractive = false
                null
            } else {
                signingChallenge = next
                signingInteractive = interactive
                next to interactive
            }
        }

    /** Ends the round and drops what was queued: the signing coroutine was cancelled. */
    fun abandonRound() {
        roundLock.withLock {
            signingRound = false
            queuedChallenge = null
            queuedInteractive = false
            signingChallenge = null
            signingInteractive = false
        }
    }

    /** An AUTH was accepted: the connection is owed one re-sync, whatever else lands first. */
    fun oweResync() {
        resyncOwed.store(1)
    }

    /** Takes the owed re-sync, if any: true for exactly one caller per [oweResync]. */
    fun takeResync(): Boolean = resyncOwed.compareAndSet(1, 0)

    /** Whether an AUTH was accepted on this connection. */
    internal fun hasAcceptedAuth(): Boolean = successCount.load() > 0

    /** Whether [eventId] is an AUTH this connection sent, as opposed to an EVENT. */
    internal fun isAuthEvent(eventId: HexKey): Boolean = authResponseWatcher[eventId] != null

    /**
     * True the first time [frame] (a subscription or event id, prefixed by its kind) is refused
     * `auth-required:` on the stored challenge, false after: a frame is retried at most once per
     * challenge, so a relay that keeps refusing it is not asked forever.
     */
    internal fun firstLateRefusal(frame: String): Boolean {
        val key = ChallengePair(frame, lastChallenge ?: return false)
        return submissionLock.withLock {
            if (lateRefusalsRetried[key] == null) {
                lateRefusalsRetried.put(key, key)
                true
            } else {
                false
            }
        }
    }

    fun rememberChallenge(challenge: String) {
        lastChallenge = challenge
    }

    fun lastChallenge(): String? = lastChallenge

    /** A sign attempt is about to start. Pair with [signingFinished] in a `finally`. */
    fun signingStarted() {
        signingInFlight.addAndFetch(1)
    }

    fun signingFinished() {
        signingInFlight.addAndFetch(-1)
    }

    fun isSigning(): Boolean = signingInFlight.load() > 0

    enum class AuthEventReceiptStatus {
        AUTHENTICATING,
        AUTHENTICATED,
        NOT_AUTHENTICATED,
    }

    data class ChallengePair(
        val user: HexKey,
        val challenge: String,
    )

    fun saveAuthSubmission(authEvent: RelayAuthEvent): Boolean {
        val challenge = authEvent.challenge() ?: return false

        val challengePair = ChallengePair(authEvent.pubKey, challenge)

        // only send replies to new challenges to avoid infinite loop. Under a lock: two rounds
        // checking at once both found the pair missing and both sent an AUTH for it.
        return submissionLock.withLock {
            if (uniqueAuthChallengesSent[challengePair] == null) {
                authResponseWatcher.put(authEvent.id, AuthEventReceiptStatus.AUTHENTICATING)
                uniqueAuthChallengesSent.put(challengePair, challengePair)
                true
            } else {
                false
            }
        }
    }

    fun checkAuthResults(
        eventId: HexKey,
        success: Boolean,
    ): Boolean {
        val wasAlreadyAuthenticated = authResponseWatcher[eventId]
        return if (wasAlreadyAuthenticated != null) {
            if (success) {
                authResponseWatcher.put(eventId, AuthEventReceiptStatus.AUTHENTICATED)
                lastAuthSuccessAt = TimeUtils.now()
                // Bumped for EVERY accepted AUTH, not only the first: a connection can
                // authenticate several identities, and each success re-syncs the filters.
                successCount.addAndFetch(1)
            } else {
                authResponseWatcher.put(eventId, AuthEventReceiptStatus.NOT_AUTHENTICATED)
            }

            wasAlreadyAuthenticated != AuthEventReceiptStatus.AUTHENTICATED && success
        } else {
            false
        }
    }

    fun hasFinishedAllAuths() = authResponseWatcher.snapshot().all { it.value != AuthEventReceiptStatus.AUTHENTICATING }

    /**
     * Build an immutable Compose-stable snapshot of the current per-relay AUTH
     * state. The phase is derived from the response watcher:
     *
     * - any AUTHENTICATING entry → [RelayAuthSnapshot.Phase.AUTHENTICATING]
     * - else a sign attempt in flight → [RelayAuthSnapshot.Phase.SIGNING]
     * - else any AUTHENTICATED entry → [RelayAuthSnapshot.Phase.AUTHENTICATED]
     * - else any NOT_AUTHENTICATED entry → [RelayAuthSnapshot.Phase.AUTH_FAILED]
     * - else (no tracked challenges) → [RelayAuthSnapshot.Phase.IDLE]
     *
     * SIGNING is checked below AUTHENTICATING (an AUTH already on the wire is the
     * more advanced fact) but above the settled verdicts, because a re-auth being
     * signed for newly-available identities is live work, and reporting the
     * previous round's AUTHENTICATED/AUTH_FAILED over it would tell a waiting
     * caller the question is settled while it is still being answered.
     *
     * The watcher LRU caps at 10 entries; a long-running connection that has
     * already AUTHed will still report AUTHENTICATED even after older entries
     * roll off, because the LRU keeps the most recent.
     */
    fun snapshot(): RelayAuthSnapshot {
        val entries = authResponseWatcher.snapshot()
        val phase =
            when {
                entries.values.any { it == AuthEventReceiptStatus.AUTHENTICATING } -> RelayAuthSnapshot.Phase.AUTHENTICATING
                isSigning() -> RelayAuthSnapshot.Phase.SIGNING
                entries.isEmpty() -> RelayAuthSnapshot.Phase.IDLE
                entries.values.any { it == AuthEventReceiptStatus.AUTHENTICATED } -> RelayAuthSnapshot.Phase.AUTHENTICATED
                else -> RelayAuthSnapshot.Phase.AUTH_FAILED
            }
        return RelayAuthSnapshot(phase, lastAuthSuccessAt, successCount.load())
    }
}
