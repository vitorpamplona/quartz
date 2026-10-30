# NIP-85 trusted assertions in Quartz

Package `com.vitorpamplona.quartz.nip85TrustedAssertions`
([source](https://github.com/vitorpamplona/quartz/tree/main/quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip85TrustedAssertions)).
NIP-85 is a draft; this package is what Amethyst-family software writes and reads. Read it
before parsing or ranking on kind 10040 / 30382–30385, or projecting them into a store.

## The model

An assertion is **signed by the asserting party** (a trust provider, or the user) **about the
subject in its `d` tag**. Every assertion kind is addressable, so "the newest card by provider P
about subject S" is the coordinate `(kind, P, S)` and supersession is plain NIP-01. An
observer's **kind 10040** says which provider to read for which metric: *"for metric M on kind
K, I trust provider P; its cards are at relay R"*. That link is a reader-side convention: Quartz
checks signatures, not that a card's signer is someone's provider.

| Kind | Class | `d` tag = subject | Content |
|---|---|---|---|
| 10040 | `list/TrustProviderListEvent` | none (`FIXED_D_TAG = ""`) | NIP-44 private provider entries (optional) |
| 30382 | `users/UserAssertionEvent` | a pubkey (64 lowercase hex) | NIP-44 private tags (petname, summary, emoji) |
| 30383 | `events/EventAssertionEvent` | an event id | `""` |
| 30384 | `addressables/AddressableAssertionEvent` | a coordinate `kind:pubkey:d` | `""` |
| 30385 | `externalIds/ExternalIdAssertionEvent` | a NIP-73 id (`isbn:…`) | `""` |

`UserAssertionEvent` is a `SearchableEvent`: it indexes only the PUBLIC petname, summary and
topics, never the encrypted card.

## Reading a 10040

There is no fixed tag name: `tag[0]` is the service, `"<kind>:<metric>"`.

```json
["30382:rank", "<provider pubkey>", "wss://nip85.brainstorm.world"]
```

- `ServiceProviderTag.parse` needs 3 elements, a known assertion kind
  (`ServiceProviderTag.ASSERTION_KINDS`, 30382..30385: neighbouring specs reuse the shape, e.g.
  Trusted Lists' `3039x:`), a 64-character key and a relay that normalizes; anything else is
  dropped. `parseKey` hex-checks the key and ignores the relay; `parseAsHint` is the relay hint.
- `serviceProviders()` reads the public tags; `privateTags(signer)?.serviceProviders()` the
  NIP-44 half.
- **`providerFor(ServiceType)`** is the provider a list names for one service: the first usable
  public entry, which is how a lens resolves one key per metric. NIP-85 allows several per
  service and prescribes no merge; `providersFor(service)` returns them all. For private
  entries call `TagArray.providerFor` on the decrypted tags.
- `ProviderTypes` names the common services (`rank`, `followerCount` = `30382:followers`,
  `hops`, `reporters`, `muters`, per-kind `eventRank`, …). The vocabulary is open: parse any
  `kind:metric`, special-case only what you rank on.

## Reading a 30382

- **`subjectPubKey()`** is the subject when the `d` tag is exactly a pubkey (64 lowercase hex,
  `UserAssertionEvent.isSubjectPubKey`), else null. Key a pubkey index with it, not
  `aboutUser()` (the raw `d`): `"<pubkey>:v2"` or an uppercase key is another address.
  `Hex.isHex64` alone accepts both, since it reads only the first 64 characters.
- Metric accessors (`rank()`, `followerCount()`, `hops()`, `firstCreatedAt()`, `postCount()`,
  zap and report counts, `topics()`, `activeHoursStart/End()`) also exist on `TagArray`
  (`users/TagArrayExt.kt`), so they work on decrypted private tags. A missing or non-numeric
  tag reads null ("unknown"), never zero. Values are not range-checked.
- **`boundedRank()`** (`RankTag.parseBounded`) clamps the rank onto NIP-85's 0..100 scale and
  keeps 0 as a value: right for a store that ranks or floors on it. Quartz's own trust index
  reads the same tag differently on purpose: `memberRank(rank)` (`users/index/`) treats rank 0
  or no rank as removal, because Brainstorm zeroes a card before the kind 5 that deletes it.
  Pick one reading per index.
- `rank` is comparable only within one provider (GrapeRank publishes `round(score × 100)`).

The same kind has two uses, told apart by AUTHOR: a provider's public metric cards (what 10040
points at), and a user's own contact cards, whose petname, summary and their emoji always live
in the NIP-44 content (`UserAssertionEvent.build` / `updatePetNameAndSummary` strip public
copies).

## What a pointer names

`SubjectReferences.of(event)` (`nip01Core.hints`) returns what an assertion is about: the
30382 pubkey, the 30383 event id, the 30384 coordinate (canonical), nothing for 30385. It also
covers NIP-32 labels, Trusted Lists (with member scores) and NIP-51 people lists, dispatched on
the kind.

## Building

```kotlin
val tag = ServiceProviderTag(ProviderTypes.rank, providerPubKey, relay)
val list = TrustProviderListEvent.create(tag, isPrivate = false, signer)
val updated = TrustProviderListEvent.add(list, tag, isPrivate = false, signer)

val card =
    UserAssertionEvent.create(
        targetUser = subjectPubKey,
        signer = providerSigner,
        publicInitializer = {
            rank(87)
            followers(1234)
            hops(2)
        },
    )

// 30383/30384/30385 have build() only: sign the template yourself.
val eventCard = signer.sign(EventAssertionEvent.build(targetEventId) { rank(12) })
```

Writers should not churn: re-sign a card only when its values change, and retract with a
NIP-09 kind 5 `a`-tagging `30382:<provider>:<subject>` (`GrapeRankPublisher` in
`experimental/graperank/` does both).

## Downloading a provider's network

`users/index/` keeps one provider's cards as a compact `TrustNetworkIndex` (22 bytes a subject):
`INostrClient.downloadTrustNetwork(provider, relay)`, `updateTrustNetwork` and
`reconcileTrustNetwork` (`TrustNetworkSync.kt`) fetch it; `TrustNetworkBuilder` applies
supersession, rank-0/kind-5 removal and tombstones; `TrustNetworkCodec` persists it.

## Stability (2026-10)

- Settled: the kind map, the 10040 entry shape, `rank` / `followers` / `hops` on 30382,
  petname and summary in the encrypted content, the 10040 relay hint.
- Written but lightly consumed: the activity, zap and report counts, `active_hours_*` (no
  timezone defined), 30383–30385.
- Warts: `ServiceProviderTag.assemble(id: ServiceProviderTag)` builds an untyped array, don't
  use it; `SummaryTag.assemble(ip)` and `ActiveHours*Tag.assemble(count)` have misnamed
  parameters; the tests sit in `commonTest/.../experimental/nip85TrustedAssertions/`;
  `TrustProviderListEvent` extends the addressable base, so a stray `d` tag shows in `dTag()`.

Fixtures: `ServiceParser.kt` (a real 10040 pointing at Brainstorm) and `AssertionEventTest.kt`
in [the tests](https://github.com/vitorpamplona/quartz/tree/main/quartz/src/commonTest/kotlin/com/vitorpamplona/quartz/experimental/nip85TrustedAssertions).
