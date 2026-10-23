# NIP-19 bech32 in Quartz

All under `quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip19Bech32/`.

| File | Holds |
|---|---|
| `Nip19Parser.kt` | `object Nip19Parser`: `uriToRoute(uri): ParseReturn?` (`entity`, `nip19raw`, `additionalChars`), `tryParseAndClean(uri): String?` (bare `npub1…` out of dirty input), `parseAll(content)`, `parseAllStandalone`, `parseAllEvents`, `hasAny`. Top-level `decodePublicKey`, `decodePublicKeyAsHexOrNull`, `decodePrivateKeyAsHexOrNull`, `decodeEventIdAsHexOrNull`. |
| `ByteArrayExt.kt` | `toNpub()`, `toNsec()`, `toNote()`, `toNEvent()`, `toNProfile()`, `toNAddress()`, `toNEmbed()`, `toLnUrl()` on raw bytes |
| `entities/` | `Entity` (plain interface) and `NPub`, `NSec`, `NNote`, `NEvent`, `NProfile`, `NAddress`, `NRelay`, `NEmbed`; each has `parse(bytes)` and a companion `create(...)` that returns the bech32 string. `NPub`/`NEvent`/`NProfile` add `createNostrUri(...)`; `NAddress` adds `createOrNull(...)` and `createNostrUriOrNull(...)` |
| `EventExt.kt` | `Event.toNIP19(relayHint)` — `naddr` for addressable events, `nevent` otherwise (also when the d-tag is too long for an naddr) |
| `ATagExt.kt` | `ATag.toNAddr()`, `ATag.parseAny`, `ATag.parseNAddr`, `ATag.isATag` |
| `ListEntityExt.kt` | `List<Entity>.eventHints()/eventIds()/addressHints()/pubKeys()…` for parsed content |
| `tlv/`, `TlvTypes.kt` | `TlvBuilder` (`addHex`, `addString`, `addInt`, `…IfNotNull`; throws past `MAX_VALUE_SIZE` = 255 bytes), `Tlv.parse`, `TlvTypes { SPECIAL, RELAY, AUTHOR, KIND }` |
| `bech32/Bech32Util.kt` | `object Bech32`: single-pass `encodeBytes` (optional `prefix`), `encodeHex`, `decodeBytes`, `decodeBytesOrNull` (no exception, optional hrp check and range); the 5-bit `encode`/`decode`/`eight2five`/`five2eight`; `String.bechToBytes()` |

## Encoding

```kotlin
val npub = pubkeyBytes.toNpub()                        // or NPub.create(hex)
val nevent = NEvent.create(idHex, authorHex, kind, relays)   // relays: List<NormalizedRelayUrl> (or one, nullable)
val nprofile = NProfile.create(pubkeyHex, relays)
val link = event.toNIP19(relayHint)
val uri = NEvent.createNostrUri(idHex, authorHex, kind, relays)  // "nostr:nevent1…" in one buffer
val naddr = NAddress.createOrNull(kind, pubkeyHex, dTag, relays) // null: d-tag over 255 bytes
```

Entities have no `toBech32()` methods; use `create(...)`, `createNostrUri(...)`, the `ByteArray`
extensions, `toNIP19` or `Event.toNostrUri` (`nip21UriScheme/`). Don't build a URI as
`"nostr:" + create(...)`: that copies the whole string again.

## Decoding

```kotlin
when (val entity = Nip19Parser.uriToRoute(input)?.entity) {   // accepts nostr: URIs
    is NPub -> entity.hex
    is NEvent -> entity.hex to entity.relay                      // relay: List<NormalizedRelayUrl>
    is NAddress -> entity.aTag()                                  // or entity.address()
    null -> /* not bech32 */
}
```

Just need a pubkey from hex/npub/nprofile/nsec? `decodePublicKeyAsHexOrNull(input)`, or
`resolveUserHexOrNull` to also accept NIP-05 (see `nip05-identifiers.md`).

## Gotchas

- Relay hints in `nevent`/`nprofile`/`naddr` are hints; the outbox model decides where to
  fetch.
- A TLV value holds at most 255 bytes. `NAddress.create` throws for a longer d-tag (use
  `createOrNull` for d-tags from other people's events); relay hints that long are dropped.
- Decoding untrusted text in a loop: `Bech32.decodeBytesOrNull` costs no exception per bad
  candidate; `decodeBytes`/`bechToBytes` throw.
- `NEmbed` is a gzip-compressed full event (`NEmbed.create(event)`), an Amethyst extension
  beyond NIP-19.
- Never log an `NSec` or the input string it came from. `PubKeysNeverLeakNsecTest`
  (commonTest `nip19Bech32/`) guards the decode helpers.
