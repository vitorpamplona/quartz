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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.MachineReadablePrefix
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the default AUTH warm-up read of [publishAwaitingOkThroughAuth] may stay idle. */
const val DEFAULT_AUTH_WARM_UP_MS = 5_000L

/**
 * How long [publishAwaitingOkThroughAuth] keeps listening after the retry, when a relay has answered
 * but not accepted. After an AUTH the pool may re-send the event more than once, and the copies race
 * on the relay: one is applied (a Buzz DM command, with its side effects, answers tens of
 * milliseconds later) while another is answered at once, `duplicate: already processed` or a refusal
 * the first copy caused (`invalid: deletion target not found` for a delete the other copy is
 * applying). The accepted OK is the relay's answer, and the one with the data.
 */
const val IN_FLIGHT_OK_GRACE_MS = 1_000L

/**
 * Publishes [event] to [relays] and returns each relay's real answer, across a NIP-42 challenge.
 *
 * The write race this closes: on a cold connection an auth-gated relay answers the first publish
 * `auth-required:`. The pool keeps the event and, once our AUTH lands, sends it again by itself,
 * and **that** re-send's OK is the relay's answer: on a relay that puts data in it (a Buzz DM
 * command's `OK` carries the channel the relay made) it is the only place the data is. A caller
 * that sees `auth-required:`, authenticates and publishes again gets `duplicate:` instead, with
 * nothing in it. So one OK listener stays open from before the first send until this returns:
 *
 *  1. publish and wait as [publishAndCollectResults] does;
 *  2. for the relays that answered `auth-required:`, run [warmUp] (by default a one-event read
 *     of [event]'s kind, which makes the auth coordinator finish the handshake), ending it early
 *     once each of them has answered;
 *  3. publish once more to those still without an accepted OK, on the now-authed socket.
 *
 * An accepted OK seen at any point wins over a later `duplicate:` or refusal from the same relay,
 * and a relay that answered the retry without accepting gets [IN_FLIGHT_OK_GRACE_MS] more for a
 * copy it is still applying. A relay
 * that refuses our AUTH `blocked:` (a banned key) answers with that refusal at once instead of
 * an `auth-required:` loop. Relays that answered anything else keep their first answer.
 */
suspend fun INostrClient.publishAwaitingOkThroughAuth(
    event: Event,
    relays: Set<NormalizedRelayUrl>,
    timeoutInSeconds: Long = 15,
    warmUp: suspend (needAuth: Set<NormalizedRelayUrl>) -> Unit = { needAuth -> warmUpAuth(needAuth, event.kind) },
): Map<NormalizedRelayUrl, PublishResult> {
    if (relays.isEmpty()) return emptyMap()
    // The first accepted OK each relay gave [event] that is not a duplicate notice.
    val accepted = MutableStateFlow<Map<NormalizedRelayUrl, PublishResult>>(emptyMap())
    // Relays that refused one of our AUTH events `blocked:`.
    val blocked = MutableStateFlow<Map<NormalizedRelayUrl, PublishResult>>(emptyMap())
    val authSent = MutableStateFlow<Set<HexKey>>(emptySet())
    // Relays that answered [event] `duplicate:`: it was applied, or is being applied, elsewhere.
    val duplicated = MutableStateFlow<Set<NormalizedRelayUrl>>(emptySet())
    val watcher =
        object : RelayConnectionListener {
            override fun onSent(
                relay: IRelayClient,
                cmdStr: String,
                cmd: Command,
                success: Boolean,
            ) {
                if (cmd is AuthCmd && relay.url in relays) authSent.update { it + cmd.event.id }
            }

            override suspend fun onIncomingMessage(
                relay: IRelayClient,
                msgStr: String,
                msg: Message,
            ) {
                if (msg !is OkMessage || relay.url !in relays) return
                val duplicate = MachineReadablePrefix.parse(msg.message) == MachineReadablePrefix.DUPLICATE
                if (msg.success && msg.eventId == event.id && !duplicate) {
                    accepted.update { if (relay.url in it) it else it + (relay.url to PublishResult(true, msg.message)) }
                } else if (msg.eventId == event.id && duplicate) {
                    duplicated.update { it + relay.url }
                } else if (isAuthBlocked(msg, relay.url, relays, authSent.value)) {
                    blocked.update { if (relay.url in it) it else it + (relay.url to PublishResult(false, msg.message)) }
                }
            }
        }

    fun merged(results: Map<NormalizedRelayUrl, PublishResult>): Map<NormalizedRelayUrl, PublishResult> =
        relays.associateWith { relay ->
            accepted.value[relay]
                ?: results[relay]?.takeIf { it.accepted }
                ?: blocked.value[relay]
                ?: results[relay]
                ?: PublishResult(false, PublishResult.NO_RESPONSE)
        }

    addConnectionListener(watcher)
    try {
        val first = publishAndCollectResults(event, relays, timeoutInSeconds = timeoutInSeconds)

        fun answered(relay: NormalizedRelayUrl) = relay in accepted.value || relay in blocked.value

        val needAuth =
            first
                .filter { (relay, result) -> !result.accepted && !answered(relay) && MachineReadablePrefix.parse(result.message) == MachineReadablePrefix.AUTH_REQUIRED }
                .keys
        if (needAuth.isEmpty()) return merged(first)

        coroutineScope {
            val warming = launch { warmUp(needAuth) }
            val settled =
                launch {
                    combine(accepted, blocked) { a, b -> needAuth.all { it in a || it in b } }.first { it }
                    warming.cancel()
                }
            warming.join()
            settled.cancel()
        }

        val stillOpen = needAuth.filterNot(::answered).toSet()
        if (stillOpen.isEmpty()) return merged(first)

        val retry = publishAndCollectResults(event, stillOpen, timeoutInSeconds = timeoutInSeconds)
        // No accepted OK yet, but an answer: a copy the relay is still applying may yet accept it.
        val inFlight = stillOpen.filter { it !in accepted.value && it !in blocked.value && (it in duplicated.value || retry[it]?.isTransportFailure == false) }
        if (inFlight.isNotEmpty()) {
            withTimeoutOrNull(IN_FLIGHT_OK_GRACE_MS) { accepted.first { m -> inFlight.all { it in m } } }
        }
        return merged(first + retry)
    } finally {
        removeConnectionListener(watcher)
    }
}

/**
 * The default warm-up of [publishAwaitingOkThroughAuth]: a one-event read of [kind] on [relays]
 * that waits out an `auth-required:` refusal, which makes the auth coordinator answer the
 * challenge on the socket the publish will reuse.
 */
suspend fun INostrClient.warmUpAuth(
    relays: Set<NormalizedRelayUrl>,
    kind: Int,
    idleTimeoutMs: Long = DEFAULT_AUTH_WARM_UP_MS,
) {
    val filter = listOf(Filter(kinds = listOf(kind), limit = 1))
    fetchAllWithHooks(
        filters = relays.associateWith { filter },
        idleTimeoutMs = idleTimeoutMs,
        pendingOnAuthRequired = true,
    ) { _, _ -> true }
}
