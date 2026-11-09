# Quartz .bit domain-resolution API

Status: stable for the Amethyst NamecoinServices integration. This document is local to the
`pr/bit-domain-resolution` branch; no publication or push is implied.

## Entry points

Package: `com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin`

```kotlin
data class NamecoinResolutionLimits(
    val maxLookups: Int = 16,
    val maxDepth: Int = 16,
)

class NamecoinNameResolver {
    constructor(
        electrumxClient: IElectrumXClient,
        lookupTimeoutMs: Long = 20_000L,
        serverListProvider: () -> List<ElectrumxServer>,
    )

    constructor(
        electrumxClient: IElectrumXClient,
        lookupTimeoutMs: Long = 20_000L,
        resolutionLimits: NamecoinResolutionLimits,
        serverListProvider: () -> List<ElectrumxServer>,
    )

    suspend fun resolveDomain(hostname: String): NamecoinNostrResult?

    suspend fun resolveDomainDetailed(hostname: String): NamecoinResolveOutcome

    // Existing NIP-05/id API, retained:
    suspend fun resolve(identifier: String): NamecoinNostrResult?
    suspend fun resolveDetailed(identifier: String): NamecoinResolveOutcome
}
```

Amethyst should use **resolveDomainDetailed** for Browser-tab navigation. Pass the already
URL-derived ASCII hostname, not a URL, path, port, userinfo, or `.onion` name. A single DNS
trailing dot and ASCII case are normalized. `www.example.bit` queries `d/example` and walks
`map.www`; it never queries `d/www.example`. Use `resolveDomain` only when a nullable pubkey
result is sufficient. Legacy `resolve`/`resolveDetailed` retain the prior NIP-05 root extraction
fallback (including the first `nostr.names` entry for a bare root); that fallback is deliberately
not available to Browser hostname resolution.

Each call creates one traversal context. `maxLookups` includes the apex lookup and counts only
actual backend calls (memoized repeats are free). `maxDepth` bounds combined import, delegate,
default-map, and subdomain traversal. The constructor timeout remains the overall coroutine
bound. No `runBlocking` is used and caller cancellation is rethrown.

## Success

```kotlin
NamecoinResolveOutcome.Success(
    result = NamecoinNostrResult(
        pubkey: String,                 // lowercase 64-character hex
        relays: List<String>,
        namecoinName: String,           // apex key, e.g. "d/example"
        localPart: String,              // "_" for apex; dotted subdomain otherwise
    ),
)
```

For Browser navigation, `result.pubkey` is the nSite owner. Quartz does not resolve IP, TLSA,
Tor/onion, or transport data.

## Outcome mapping for Amethyst

- `Success`: open the returned pubkey's root nSite.
- `NoNostrField(name)`: the blockchain name and selected record were processed successfully, but
  no usable root `nostr` pubkey exists. This is the **only** outcome that should trigger the
  localized no-pubkey/system-browser confirmation flow.
- `NameNotFound(name)`: the apex name is absent/expired. Show resolution failure; do not describe
  it as a valid site without a pubkey.
- `InvalidIdentifier(identifier)`: reject the input as a non-.bit/invalid hostname.
- `MalformedRecord(name, error)`: invalid JSON/object, import/delegate shape, reference, map, or
  selected child.
- `ImportFailed(name, target, cause)`: an import could not be completed. Do not use local records
  as a replacement.
- `DelegationFailed(name, target, cause)`: a delegate could not be completed. Do not use local
  records as a replacement.
- `CyclicReference(name, path)`: active recursion detected.
- `LookupBudgetExhausted(name, limit)`: traversal-wide backend lookup budget exhausted.
- `DepthLimitExceeded(name, limit)`: traversal depth bound exceeded.
- `ServersUnreachable(message)`: backend unavailable.
- `Timeout`: the overall lookup timeout elapsed.

Every non-`Success`/non-`NoNostrField` outcome is a fail-closed resolution error. Nullable
`resolveDomain` deliberately maps all of them to `null`, so detailed callers must not infer
`NoNostrField` from `null`.

## Namecoin interpretation pinned by tests

- Imports and delegates are resolved only on the selected path; unrelated `map` branches cause
  zero lookups.
- Import merge precedence is local object, then first import, then later imports. Import failures
  abort the resolution with a typed failure.
- Deprecated `delegate` follows ncdns's legacy exclusive-replacement behavior: local records are
  ignored. Exactly one delegate target is accepted. IFA-0001 deprecates `delegate` and does not
  define multiple-target precedence, so multiple targets are rejected as malformed rather than
  merged ambiguously.
- Imports at an imported object are processed before its subdomain selector.
- A map exact label wins over `*`. IFA-0001's `map[""]` entry supplies defaults to its immediate
  ancestor (local ancestor items win); it is not an additional wildcard. Unknown labels without an
  exact or `*` child produce `NoNostrField`, never another branch's pubkey.
- Import string/array/canonical forms are supported. The legacy two-string pair form is treated as
  name + selector when the second string is not a Namecoin key; an all-key string array is treated
  as multiple imports per IFA-0001.

References: IFA-0001 Domain Names (`namecoin/proposals`, `ifa-0001.md`) and ncdns
`ncdomain/convert.go` / `convert_test.go` (exclusive `delegate`, ancestor default map items).
