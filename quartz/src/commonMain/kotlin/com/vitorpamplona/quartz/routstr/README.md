# Routstr (`com.vitorpamplona.quartz.routstr`)

[Routstr](https://docs.routstr.com) ([routstr-core](https://github.com/Routstr/routstr-core)) is a
network of nodes, each an OpenAI-compatible AI proxy paid in sats over Lightning or Cashu. A node
announces itself on Nostr so clients can find it. Routstr publishes no NIP: these classes follow
the events live nodes publish, which differ from the docs, as seen on 2026-11-13 on
relay.routstr.com, relay.damus.io and nos.lol (82 events from 79 addresses, kept in
`jvmTest/resources/routstr/38421.json`).

| Kind | Class | Shape |
|---|---|---|
| 38421 | `node/RoutstrNodeEvent` | addressable; `d` = node id (routstr-core: the first 12 hex of its pubkey), `u` endpoints, `mint` Cashu mints, `version`, sometimes `models`, `t`, `payment`; content `{"name", "about"}`, sometimes empty |

- `RoutstrEndpoint` classifies each `u` as https, a Tor v3 onion, or other (plain http to a
  clearnet or LAN host, loopback, IP literals, `.fips` mesh names, Kubernetes service names such
  as `redis.default` or `x.default.svc`, https on a port other than 443, junk onions).
  `RoutstrNodeEvent.usableEndpoints(torEnabled)` is the list a client may call. It is a syntax
  filter: a public name can resolve to a private address, so a client must also refuse private,
  loopback and link-local addresses when it resolves the host, before it sends a request or money.
  The port rule is for announced addresses; a URL the user typed is the app's to take as written.
- `RoutstrNodeContent` reads the content leniently: anything but a JSON object with string
  fields reads as nulls.
- `RoutstrDiscovery` holds the docs' relays and the kind filter. Nodes re-announce every 24 hours,
  but live ones that still answer have been seen a week late. A client lists nodes up to a month
  old (`RoutstrNodeEvent.LISTED_MAX_AGE`, `isListed()`; `RoutstrDiscovery.listedFilter()` asks the
  relay for `since` = now − 30 days) and puts those not seen this week (`MAX_AGE`, `isFresh()`)
  behind a "not seen this week" control.
- A node's `GET /v1/info` carries its npub: compare it with the announcement's author before
  calling a node verified. Keys, balances and pricing are HTTP, not Nostr, and stay in the app.

Searchable by name and about. No `a`/`e`/`p`/`q`, so no hint providers.
