# Local relays are the app's call, not the parser's

## Problem

A relay on `localhost`, the LAN or a private network is legitimate for the user's own use and
hostile in someone else's data: another user's `ws://192.168.1.10` names their LAN, and dialing it
aims our connection at ours, off Tor. Quartz decided this at parse time (`readRelaysNorm()`,
`DmRelayListEvent.relays()`, `WelcomeRelaysTag`, `HintIndexer`, …), where it cannot know whose data
it holds. The result: apps lost the user's own local relays (Amethyst's NIP-65 screen dropped them
on save; a Marmot group on a closed network got no relays), and the classifier missed whole
families of private hosts.

Measured on 49,184 relay urls from production lists: 7,750 private IP literals, 2,353 local-only
names, 305 urls in `100.64.0.0/10` and 456 single-label or `.lan`/`.home`/`.internal` names, all
dialed and none ever answered.

## Change

- **Classifier** (`RelayUrlNormalizer.isLocalHost`): adds `100.64.0.0/10`, single-label hosts
  (which also catches `wss://wss/nos.lol`, a scheme pasted twice), `.lan`, `.home`, `.home.arpa`,
  `.internal`, `.intranet`, `.localdomain`, and IPv4-mapped/compatible IPv6. IPv4 literals are
  parsed as `inet_aton` does (`Ipv4.parseInetAton`: `127.1`, `2130706433`, `0x7f.0.0.1`), the
  parser `CashuMintUrlValidator` now shares. A single-label schemeless host now gets `ws://`.
- **Policy** (`NormalizedRelayUrl.isDialableFromOthers()`): not local, IP literals public (overlay
  mesh allowed), no empty labels. Pure url check; `publicAddressesOnly()` is the resolve-time
  complement for `*.sslip.io` / `*.nip.io` names.
- **Parsers**: every filtered accessor is deprecated and keeps filtering until 3.0.0 so
  third-party apps notice before they start reading local entries (changing what a name returns
  is a breaking change, so it waits for the major). The unfiltered names
  (`allReadRelaysNorm`, `allWriteRelaysNorm`, `allRelays`, `parse*Unfiltered`, and the new
  `WelcomeEvent.allRelays`, `PublishedKeyPackage.allRelays`, `allWelcomeRelays`,
  `allKeyPackageRelays`) are the ones to use. Each `ReplaceWith` keeps the filter, so an IDE
  quick-fix never silently starts dialing a stranger's LAN. `diffFrom` compares every relay.
  The NIP-62 vanish `RelayTag.parse` changed at once: nothing dials those urls for someone else.
- **Indexers**: `HintIndexer(accept)` and `reliableRelaySetFor(accept = …)` default to every relay,
  as the brief asked. The previous `reliableRelaySetFor` and GrapeRank `Config` signatures stay as
  hidden overloads, so code compiled against 2.7.2 still links. GrapeRank only ever reads other
  people's lists, so its `acceptOthersRelay` defaults to `isDialableFromOthers`.

## 3.0.0

The short names (`readRelaysNorm()`, `writeRelaysNorm()`, `DmRelayListEvent.relays()`,
`KeyPackageRelayListEvent.relays()`, `WelcomeEvent.relays()`, …) return every relay and lose the
deprecation; the `all*` names become deprecated aliases of them.
