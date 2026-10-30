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
package com.vitorpamplona.quartz.nip01Core.relay.server.policies

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.AuthMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CountCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.EventCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.server.backend.RequestContext
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip42RelayAuth.tags.RelayTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Requires authentication for all EVENT, REQ, and COUNT commands.
 * Replicates the previous `requireAuth = true` behavior.
 *
 * Implements the full NIP-42 challenge/verify handshake: [onConnect] sends the
 * [challenge] and [accept] (AuthCmd) validates the returned event (expiration,
 * freshness, challenge match, and a `relay` tag naming one of [relays]). This policy runs the auth *logic*
 * but does not *own* the authenticated-identity store: the engine-owned
 * connection [scope] holds it. [accept] does NOT mutate state, and
 * [onAuthenticated] only votes `true` (after [authorize]) — the engine performs
 * the single recording into the scope once the whole chain has approved. Gating
 * decisions read [scope].`authenticatedUsers`. A rejected AUTH simply never
 * reaches [onAuthenticated], so there is no rollback to reason about.
 *
 * It does not verify the AUTH event's signature or id: stack it after [VerifyPolicy]
 * (`VerifyPolicy + FullAuthPolicy(relay)`). On its own, anyone can AUTH as any pubkey.
 *
 * To bridge to an external auth system, override [authorize] (a `suspend` hook)
 * and do the post-verification I/O there — e.g. exchange the verified event for
 * a backend session token. Throwing from it rejects the login (`OK false`) and
 * the pubkey is never recorded. [sendToClient] reaches the client from there (a
 * `NOTICE` after a login), and [connectionScope] is the connection's context.
 */
open class FullAuthPolicy(
    /**
     * Every address this relay answers at (a clearnet url and its `.onion`, say): an AUTH event
     * naming any of them authenticates, since a client signs the address it dialled. Not empty.
     * Build the policy per connection from the current set, and a newly published address is
     * accepted from the next connection on.
     */
    val relays: Set<NormalizedRelayUrl>,
) : IRelayPolicy {
    /** A relay that answers at one address. */
    constructor(relay: NormalizedRelayUrl) : this(setOf(relay))

    init {
        require(relays.isNotEmpty()) { "FullAuthPolicy needs at least one relay address" }
    }

    /** The first of [relays]: the relay's primary address (insertion order of the set). */
    val relay: NormalizedRelayUrl = relays.first()

    /** The challenge string sent to this client for NIP-42 authentication. */
    val challenge: String = RandomInstance.randomChars(32)

    /**
     * The engine-owned connection scope, captured at [onConnect]. Read-only
     * here: this policy reads [RequestContext.authenticatedUsers] to gate, while
     * the engine is the only writer. Held safely because a [FullAuthPolicy] is
     * built fresh per connection.
     */
    private lateinit var scope: RequestContext

    /** The connection's channel to the client, captured at [onConnect]. */
    private var send: ((Message) -> Unit)? = null

    /**
     * The engine-owned connection scope this policy was connected with (its id, the client's
     * address, the live [RequestContext.authenticatedUsers]), for subclasses that gate on more
     * than the identity. Read-only; valid from [onConnect] on.
     */
    protected val connectionScope: RequestContext get() = scope

    /**
     * Sends [message] to this connection's client, as the engine's own frames go. For a subclass
     * that answers a login ([authorize]) or a refusal with more than the `OK`: a `NOTICE`, a
     * fresh challenge. A no-op before [onConnect]. Must not block.
     */
    protected fun sendToClient(message: Message) {
        send?.invoke(message)
    }

    /**
     * The pubkeys authenticated on this connection, read from the engine-owned
     * scope. Exposed to subclasses so they can gate or rewrite on the caller's
     * identity (restricted content, caller-relative filters) — the same set the
     * data plane sees via [RequestContext.authenticatedUsers].
     */
    protected val authenticatedUsers: Set<HexKey> get() = scope.authenticatedUsers

    /** Returns true if at least one pubkey has authenticated on this connection. */
    fun isAuthenticated(): Boolean = authenticatedUsers.isNotEmpty()

    override fun onConnect(
        scope: RequestContext,
        send: (Message) -> Unit,
    ) {
        this.scope = scope
        this.send = send
        send(AuthMessage(challenge))
    }

    override fun accept(cmd: AuthCmd): PolicyResult<AuthCmd> {
        val event = cmd.event

        if (event.isExpired()) {
            return PolicyResult.Rejected("invalid: auth event expired")
        }

        if (!TimeUtils.withinTenMinutes(event.createdAt)) {
            return PolicyResult.Rejected("invalid: created_at is too far from the current time")
        }

        if (event.challenge() != challenge) {
            return PolicyResult.Rejected("invalid: challenge does not match")
        }

        if (!namesThisRelay(event)) {
            return PolicyResult.Rejected("invalid: relay url does not match")
        }

        return PolicyResult.Accepted(cmd)
    }

    /**
     * Whether any `relay` tag of [event] is one of [relays]. Every tag counts, not just the first:
     * [RelayAuthEvent.create] with a list writes one per relay, and the challenge, which is this
     * connection's alone, is what binds the event to this socket. Both sides are normalized, so a
     * scheme's default port spelled out (`ws://….onion:80`) is the same address.
     */
    private fun namesThisRelay(event: RelayAuthEvent): Boolean = event.tags.fastAny { tag -> RelayTag.parse(tag)?.let { it in relays } == true }

    /**
     * Votes to record the authentication. The engine calls this only after
     * [accept] and the whole policy chain have approved the AUTH. It runs
     * [authorize] first (which may throw to reject — the engine then records
     * nothing) and returns `true` so the engine records `event.pubKey` into the
     * connection scope. `final`: override [authorize], not this.
     */
    final override suspend fun onAuthenticated(event: RelayAuthEvent): Boolean {
        authorize(event)
        return true
    }

    /**
     * Hook for external authorization once the NIP-42 proof checks out — e.g.
     * exchange [event] for a backend session token. Throw to reject the login
     * (the AUTH becomes `OK false` and `event.pubKey` is not recorded). Runs
     * before the pubkey is committed. The default does nothing.
     */
    open suspend fun authorize(event: RelayAuthEvent) {}

    final override suspend fun acceptTransportIdentity(pubkey: HexKey): String? = authorizeTransport(pubkey)

    /**
     * [authorize]'s counterpart for a key the transport proved (NIP-98 on a NIP-FE command), which
     * has no AUTH event to hand it. Refuses by default: a subclass whose [authorize] checks or grants
     * anything must decide what that means without one, and opt in by returning null.
     */
    open suspend fun authorizeTransport(pubkey: HexKey): String? = TRANSPORT_IDENTITY_REFUSED

    override fun accept(cmd: EventCmd): PolicyResult<EventCmd> =
        if (isAuthenticated()) {
            PolicyResult.Accepted(cmd)
        } else {
            PolicyResult.Rejected("auth-required: this relay requires authentication")
        }

    override fun accept(cmd: ReqCmd) =
        if (isAuthenticated()) {
            PolicyResult.Accepted(cmd)
        } else {
            PolicyResult.Rejected("auth-required: this relay requires authentication")
        }

    override fun accept(cmd: CountCmd) =
        if (isAuthenticated()) {
            PolicyResult.Accepted(cmd)
        } else {
            PolicyResult.Rejected("auth-required: this relay requires authentication")
        }

    override fun canSendToSession(event: Event) = true
}
