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
package com.vitorpamplona.quartz.nip01Core.relay.normalizer

import androidx.compose.runtime.Stable

@Stable
data class NormalizedRelayUrl(
    val url: String,
) : Comparable<NormalizedRelayUrl> {
    override fun compareTo(other: NormalizedRelayUrl) = url.compareTo(other.url)
}

fun NormalizedRelayUrl.displayUrl() =
    url
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")

/**
 * The relay's HTTP address, where its NIP-11 document and NIP-86 / NIP-FE endpoints answer:
 * `wss://` becomes `https://` and `ws://` becomes `http://`, with the host, port, path and query
 * kept as they are. A normalized url always carries a ws scheme and never a default port, so
 * the result never carries `:443` / `:80` either.
 */
fun NormalizedRelayUrl.toHttp() =
    if (url.startsWith("wss://")) {
        "https${url.drop(3)}"
    } else if (url.startsWith("ws://")) {
        "http${url.drop(2)}"
    } else {
        "https://$url"
    }

// Delegates rather than re-implementing `contains(".onion/")`: that copy missed
// `wss://host.onion:8080/`, so an onion relay on an explicit port was never forced onto Tor.
fun NormalizedRelayUrl.isOnion() = RelayUrlNormalizer.isOnion(this.url)

fun NormalizedRelayUrl.isLocalHost() = RelayUrlNormalizer.isLocalHost(this.url)

/** True for a relay inside an encrypted IPv6 overlay mesh. See [RelayUrlNormalizer.isOverlayNetwork]. */
fun NormalizedRelayUrl.isOverlayNetwork() = RelayUrlNormalizer.isOverlayNetwork(this.url)

/**
 * Whether this relay, found in SOMEONE ELSE's data, may be dialed: another user's NIP-65 or
 * NIP-17 list, a relay hint in their event, a recommendation, a Welcome.
 *
 * A relay on `localhost`, the LAN or a private network is legitimate for the user's OWN use:
 * their own relay, their own LAN, a closed network with no internet at all. The same url in
 * someone else's data names *their* machine or *their* LAN, so dialing it aims our connection at
 * our own machine or LAN, chosen by a stranger. Apps also exempt local hosts from Tor (Tor cannot
 * reach them), which makes such a url an off-Tor connection that reveals the user's address.
 *
 * So the question is not "is this url local?" but "whose data did it come from?", and only the
 * app knows that. Quartz's parsers return every relay a tag holds; the app calls this at the
 * point where someone else's data becomes a connection, and never on the user's own lists.
 *
 * Refuses:
 * - every host [isLocalHost] matches: loopback, RFC 1918, link-local, `100.64.0.0/10`
 *   (CGNAT, Tailscale), IPv6 loopback/ULA/link-local and IPv4-mapped forms, `.local`, `.lan`,
 *   `.home`, `.internal`, `.home.arpa`, single-label names;
 * - IP literals outside the public internet (multicast, documentation, benchmarking blocks);
 * - names that cannot be a host: an empty label (`ws://.tailed9020.ts.net/`) or a scheme pasted
 *   twice (`wss://wss/nos.lol`, whose host is the single label `wss`). Refused, never repaired.
 *
 * Allows `.onion` (whether the app can reach Tor is its own, separate flag) and Yggdrasil
 * overlay addresses ([isOverlayNetwork]), which are reachable from anywhere on the mesh.
 *
 * This reads the url only; it cannot see DNS. A public name that resolves to a private address
 * (`192-168-0-12.sslip.io`, `10.0.2.2.nip.io`, or plain DNS rebinding) passes here. The
 * resolve-time complement is `publicAddressesOnly()` (`utils/okhttp/PublicAddressGuard.kt`),
 * which refuses the connection once the name resolves.
 */
fun NormalizedRelayUrl.isDialableFromOthers() = RelayUrlNormalizer.isDialableFromOthers(this.url)
