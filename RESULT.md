# Quartz bounded .bit domain resolution — RESULT

## Delivered

- Added Browser-oriented `resolveDomain` / `resolveDomainDetailed` while retaining the legacy
  NIP-05 `resolve` / `resolveDetailed` entry points.
- Reworked Namecoin traversal into one per-resolution context with lazy map selection, record and
  resolution memoization, active recursion detection, a global backend lookup budget, a combined
  depth bound, and the existing overall coroutine timeout.
- Imports, delegates, malformed records, cycles, and resource exhaustion fail closed with typed
  outcomes. `NoNostrField` remains distinct from resolution failures.
- Preserved legacy NIP-05 root-name fallback behavior. Browser hostname extraction accepts only a
  direct root pubkey or `nostr.names["_"]`.
- Added hermetic JVM coverage using an in-memory `IElectrumXClient`; resolver tests open no
  backend sockets.
- Added no transport, UI, IP, TLSA, or Tor/onion handling.
- Follow-up hardening rejects nested import/delegate tuples unless they contain exactly one or two
  items, and memoizes non-object top-level JSON failures consistently with other lookup outcomes.

## API contract

Stable integration contract:
`/Users/m/workspace-demo/quartz-worktrees/bit-domain-resolution/API-SHAPE.md`

It contains the exact public signatures, defaults, outcome mapping, legacy compatibility note, and
Namecoin interpretation decisions (including ncdns exclusive `delegate` behavior and rejection of
ambiguous multiple delegate targets).

## Implementation commits

- `b7408282e1a0272ca9527e4e5b8d0a67cf76a32f` — `Bound Namecoin domain resolution`
- `69b3291806b0aadce77ae034532567a50807455b` — `Close Namecoin traversal failure gaps`

No push or publication was performed.

## Verification

Gradle environment:
`JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home`.
No custom `PATH` was supplied.

Final scoped verification command:

```sh
./gradlew :quartz:spotlessCheck :quartz:jvmTest --tests "com.vitorpamplona.quartz.nip05.namecoin.*"
```

Outcome: **exit 0, BUILD SUCCESSFUL**. Both `:quartz:spotlessCheck` and `:quartz:jvmTest`
passed. After the follow-up hardening, the focused filter ran **78 tests, 0 failures, 0 errors,
0 skipped** across:

- `CompositeNamecoinBackendTest`: 8
- `ElectrumXClientPinningTest`: 5
- `NamecoinBitDomainResolverTest`: 12
- `NamecoinCoreRpcClientTest`: 10
- `NamecoinImportTest`: 21
- `NamecoinNameResolverExceptionTest`: 7
- `NamecoinNameResolverTest`: 15

The new tests cover apex/subdomain normalization and lookup, exact/wildcard/default selection,
imports/delegates and precedence, fail-closed failures, malformed records, cycles, global budget
and depth limits, diamond memoization, the 24-label self-import shape with one backend lookup,
zero lookups for unrelated map branches, invalid input without I/O, timeout, caller cancellation,
and the Browser-vs-legacy extraction distinction. Three follow-up regressions prove that oversized
nested import/delegate tuples fail before target lookup and that repeated references to a non-object
JSON value reuse one memoized backend lookup failure.

Formatting was applied with:

```sh
./gradlew :quartz:spotlessApply
```

Outcome: **exit 0, BUILD SUCCESSFUL**; only the six implementation/API/test files in the original
implementation commit were changed. The follow-up used the same formatting command, followed by the
final scoped verification command above; both exited 0, and only the resolver and its test file were
changed before the hardening commit.

Earlier verification history:

- `./gradlew :quartz:compileKotlinJvm --stacktrace`: passed after supplying `JAVA_HOME`; an
  initial attempt without a discoverable Java runtime failed before compilation.
- The first focused test execution compiled successfully and completed 75 tests with one failure.
  The failure was a reversed label order in the new 24-label fixture, not an implementation
  failure. After correcting the fixture, `./gradlew :quartz:jvmTest --tests
  "com.vitorpamplona.quartz.nip05.namecoin.*"` exited 0 with all 75 tests passing.
- The first combined formatting/test invocation stopped at formatting violations in the new
  Kotlin files. `spotlessApply` fixed them, and the final combined command above passed.

## Remaining risks / scope limits

- No live ElectrumX/Namecoin integration test was run; new traversal tests are deliberately
  hermetic. Existing focused backend tests passed.
- Per the brief, no broad multiplatform/Android build or full repository test run was attempted.
- Amethyst `NamecoinServices` integration is a separate deliverable and is not included here.
- Multiple `delegate` targets are not assigned an invented precedence; they are rejected as
  malformed, and this ambiguity decision is documented in API-SHAPE.md.
