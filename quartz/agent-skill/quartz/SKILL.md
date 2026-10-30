---
name: quartz
description: Building on Quartz, the Kotlin Multiplatform Nostr library (com.vitorpamplona.quartz): NostrClient + a WebsocketBuilder, fetchAll/fetchFirst/fetchAllPages/publishAndConfirm, typed event builders, NostrSigner, NIP-19/NIP-05/NIP-44. Use in projects that depend on Quartz.
---

# Quartz (Nostr for Kotlin Multiplatform)

Quartz is the protocol library behind the Amethyst Nostr client, published on Maven
Central as `com.vitorpamplona.quartz`. This skill is for **projects that depend on it**.
The full guides live in the repo:

- [Client guide](https://github.com/vitorpamplona/quartz/blob/main/quartz/CLIENT.md) —
  transports, subscriptions, publishing, filters, connection lifecycle.
- [Relay guide](https://github.com/vitorpamplona/quartz/blob/main/quartz/RELAY.md) —
  running a relay on Quartz: event stores, policies, NIP-11 limits and documents.
- [Client architecture](https://github.com/vitorpamplona/quartz/blob/main/quartz/README.md) —
  how to structure a feed-style app on top of it.
- [One-shot relay operations](https://github.com/vitorpamplona/quartz/blob/main/quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip01Core/relay/client/accessories/README.md) —
  the catalog of `fetch*`, `count`, publish and negentropy helpers.

## Setup

```kotlin
// commonMain, or a JVM/Android module. Take the latest version from Maven Central:
// https://central.sonatype.com/search?q=com.vitorpamplona.quartz
implementation("com.vitorpamplona.quartz:quartz:<version>")
```

Targets: Android, JVM, iosArm64, iosSimulatorArm64, macosArm64, linuxX64 (per-target
artifacts such as `quartz-jvm` and `quartz-android` exist for non-KMP builds). Ktor is not
a dependency: on JVM and Android, Quartz talks to relays through OkHttp. JSON is Quartz's
own code on every target (Kite plus kotlinx.serialization); there is no Jackson.

## Where things are

Packages are named after the NIP they implement: `nip01Core` (events, filters, relay
client, crypto, signers), `nip19Bech32`, `nip05DnsIdentifiers`, `nip17Dm`, `nip44Encryption`,
`nip57Zaps`, and so on, under `com.vitorpamplona.quartz`. To find a NIP, look for its
`nipNN…` package first; event classes expose `const val KIND` and a `build(...)` template.

## Connect and read

`NostrClient` is transport-agnostic: it needs a `WebsocketBuilder`. On JVM and Android,
use the bundled OkHttp one (add `com.squareup.okhttp3:okhttp` to construct the client);
on other targets, implement the three small interfaces shown in the client guide.

```kotlin
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import okhttp3.OkHttpClient

suspend fun read(pubkey: String) {
    val okHttp = OkHttpClient()
    val client = NostrClient(BasicOkHttpWebSocket.Builder { okHttp })

    val notes = client.fetchAll("wss://nos.lol", Filter(kinds = listOf(1), authors = listOf(pubkey), limit = 20))
    val profile = client.fetchFirst("wss://nos.lol", Filter(kinds = listOf(MetadataEvent.KIND), authors = listOf(pubkey)))
    println("${notes.size} notes; has profile: ${profile != null}")

    client.disconnect()
}
```

**Reach for the accessories before writing a REQ loop.** They are extension functions in
`nip01Core.relay.client.accessories` (live flows are in `…client.reqs`), so they don't show
in `NostrClient` completion — import them by name:

| Need | Use |
| --- | --- |
| All events for a filter | `fetchAll(relay, filter)` |
| First match | `fetchFirst(relay, filter)` |
| More than a relay's per-REQ cap | `fetchAllPages(relay, filters) { event -> }`; only `PagedFetchResult.End.DRAINED` proves there is nothing more |
| Verify/store as events arrive, per-relay results | `fetchAllWithHooks` |
| Many relays at once | `fetchAllPagesFromPool`, `fetchAllPagesFromPoolWithHooks` |
| Live subscription | `subscribeAsFlow(relay, filter)` (`…client.reqs`) |
| NIP-45 count | `count(relay, filter)`, `countMerged(relays, filter)` |
| NIP-77 sync | `negentropySync`, `negentropySyncOrFetch` |
| Publish and wait for `OK` | `publishAndConfirm(event, relays)`, `publishAndConfirmDetailed` |
| Publish many events to one relay | `publishStream(relay, events, maxInFlight) { event, ok -> }`; `publishRawStream` for events already held as JSON |
| Keep one relay's socket between bursts | `client.keepConnected(relay)` … `client.releaseConnection(relay)` (counted holds; a member method, not an extension) |

Their waits are **idle windows** (`idleTimeoutMs`) that reset on real progress, never
wall-clock deadlines, and that never count time spent in your own `onEvent` (your
backpressure is not the relay's silence); wrap a call in `withTimeoutOrNull` for a hard bound. Relay URLs are
`NormalizedRelayUrl` (`"wss://…".normalizeRelayUrl()`); most helpers also accept a `String`.
Build them through the normalizer, not the constructor: it repairs the url and interns it, so
every spelling of a relay is one shared instance.

Relay-list parsers (`allWriteRelaysNorm()`, `allReadRelaysNorm()`, `DmRelayListEvent.allRelays()`,
`WelcomeEvent.allRelays()`) return every relay, `localhost` and LAN ones included, because
whether one may be dialed depends on whose data it is. A local relay in the user's OWN list is
theirs to use. One in SOMEONE ELSE's list, hint or Welcome names their machine, so filter with
`relay.isDialableFromOthers()` before dialing it, and pass the same predicate to `HintIndexer`
and `RelayListRecommendationProcessor.reliableRelaySetFor(accept = …)`. It reads the url only:
wire `publicAddressesOnly()` into the OkHttp client that dials others' relays to refuse names
that resolve to private addresses.

## Keys, events and publishing

```kotlin
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip19Bech32.toNpub

suspend fun post(client: NostrClient) {
    val keys = KeyPair() // random; pass privKey = … to load an existing key
    val signer = NostrSignerInternal(keys)
    println("npub: ${keys.pubKey.toNpub()}")

    val event = signer.sign(TextNoteEvent.build("Hello from Quartz"))
    check(event.verify())

    val accepted = client.publishAndConfirm(event, setOf("wss://nos.lol".normalizeRelayUrl()))
    println("accepted by at least one relay: $accepted")
}
```

- Build events with the event class's `build(...)` template, then `signer.sign(template)`.
  Don't assemble tag arrays by hand when a typed builder exists.
- A few kinds must not carry the user's own key, so don't hand their `build(...)` straight to
  the user's signer:
  - **Anonymous zap requests** (NIP-57): sign the template with `ZapRequestEvent.sign(template,
    signer)`, which uses a throwaway key for an anonymous request (`ZapRequestEvent.create`
    does this for you). Signed with the user's key, the `anon` tag hides nothing.
  - **Gift wraps** (NIP-59) and NIP-17 DMs: use `GiftWrapEvent.create(...)`, which signs each
    wrap with a fresh key, or `NIP17Factory` for whole messages. A wrap signed by the user's
    key puts the sender's identity on the outside.
- **Re-publishing a replaceable or addressable event** (a profile, a relay list, your own
  NIP-66 record): stamp it `TimeUtils.nextCreatedAt(previous?.createdAt)`, i.e. now, or one
  second past the version it replaces. A replacement stamped at or before the stored one is
  rejected. A NIP-66 monitor edits its own 30166 with `RelayDiscoveryEvent.edit(relay, current,
  owns) { … }`, which keeps every tag `owns` does not claim (another writer's, or unknown ones).
- `NostrSigner` is the abstraction to code against (`sign`, `nip44Encrypt` /
  `nip44Decrypt`, `nip04Encrypt` / `nip04Decrypt`, all `suspend`).
  `NostrSignerInternal` holds a local key; remote (NIP-46) and Android external-app
  (NIP-55) signers implement the same class.
- `Event.verify()` checks the id and signature of events you receive from relays.
- Nostr JSON goes through `OptimizedJsonMapper`: Kite (`nip01Core.kite`, Quartz's own reader
  and writer) for events, relay frames, filters, tags, rumors, templates and NIP-46/NIP-55
  signer messages, and kotlinx.serialization for everything else, on every target.

## Identifiers

- **Parse anything a user pastes** (npub, nprofile, note, nevent, naddr, `nostr:` URIs):
  `Nip19Parser.uriToRoute(text)` in `nip19Bech32`.
- **Read a PUBLIC key from config or a form**: `decodePublicKeyOnlyAsHexOrNull(input)` in
  `nip19Bech32` (npub, nprofile, `nostr:` URI, or 64 hex). Not `decodePublicKeyAsHexOrNull`:
  it also accepts an nsec and returns its pubkey, so a pasted secret is silently accepted.
- **Resolve a user to a pubkey** from hex, npub, nprofile or a NIP-05 `name@domain`:
  `resolveUserHexOrNull(input, nip05Client)` in `nip05DnsIdentifiers`. Pass a
  `Nip05Client` to enable the NIP-05 lookup; without one only key formats resolve.
- **Find a remote signer for an address**: `Nip05Client.loadRemoteSigner(Nip05Id)` reads the
  domain's NIP-46 `nip46` field (both the `nip46[pubkey]` and the `name=_` shapes) into a
  `Nip05RemoteSigner`; connect to its `signerPubKey` on its `relays`, and check the account the
  signer reports against `userPubKey`. `NostrConnectURI.fillNostrConnectUrl` fills its
  `nostrConnectUrl` page with a `nostrconnect://` code.
- **Fetch what a profile points at** (a NIP-05 domain, a lightning address): build the
  `OkHttpClient` with `publicAddressesOnly(direct, …)` (`utils.okhttp`), so a profile can't aim
  the request at a private or loopback address. `direct = true` on a server; `false` on a client
  behind Tor, which keeps its proxy (`true` would bypass Tor). `OkHttpNip05Fetcher` caps the body and fails with a
  `Nip05FetchException` that says why: `Status(code)`, `TooLarge`, `Refused` or `Unreachable`.
  For zaps, `LnurlForm.toUrl(lud16OrLnurl)` gives the LNURL-pay URL and
  `LnurlEndpointInfo.parse(body)` reads its `allowsNostr` and `nostrPubkey` (`nip57Zaps.validate`).
- **Encode**: `ByteArray.toNpub()` and the other `to*` helpers in `nip19Bech32`.

## Running a relay

Quartz also contains a relay server (event stores, policies, NIP-11, NIP-42, NIP-77) and a
standalone Ktor relay built on it (`geode`). Start from the relay guide linked above rather
than wiring `NostrServer` from scratch.
