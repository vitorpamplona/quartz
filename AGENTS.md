# Quartz

Quartz is a Kotlin Multiplatform Nostr library (JVM, Android, iOS, macOS, Linux), published to
Maven Central as `com.vitorpamplona.quartz:quartz-*`. [Amethyst](https://github.com/vitorpamplona/amethyst)
is its main consumer and pins released versions; nothing in this repository depends on Amethyst.

It holds the protocol only: NIPs under `nipXX` packages, plus whole non-NIP protocol families
beside them (`marmot/` MLS over Nostr, `cordn/`, `contextvm/`, `concord/`, `buzz/`, the RFC 9420
engine in `mls/`). No UI. Canonical NIP specs live at <https://github.com/nostr-protocol/nips>.

## Modules

One Gradle module per artifact (`:core` publishes `quartz-core`, ...), listed with what each
holds in [README.md](README.md#modules). The rules that keep them separable:

- **Dependencies only point down.** `core` depends on no other module; a NIP module depends on
  `core` and the modules it extends. A cycle is a build error, so a change that needs one is a
  design problem: invert it with an interface, a marker, or a registry (see below).
- **Packages are not modules.** A class keeps its package (`com.vitorpamplona.quartz.nip10Notes`)
  wherever it lives, so moving a file between modules never changes a consumer's imports. A few
  packages are split across modules on purpose (e.g. `nip68Picture.PictureMeta` in `core`, the rest
  of `nip68Picture` in `social`).
- **Heavy third-party libraries stay out of `core`**: SQLite in `store-sqlite`, OkHttp in
  `relay-client` and the modules that make HTTP calls, negentropy in `store`, kchesslib in `chess`,
  CBOR in `payments`.
- **New event kinds register in their module's `EventKindProvider`** (`SocialEventKinds`, ...),
  never in `EventFactory`. Kinds that unrelated apps share get `variant(...)` entries with a
  tag predicate, a default class, and a `probeAs` class (see `PaymentEventKinds` for 38000).
- **Search fields belong to the event class**: override `SearchableEvent.indexableFields()` with
  `SearchFieldExtractor.tiers(...)`, matching what `indexableContent()` joins.
- **Cross-module internals** are `public` and marked `@InternalQuartzApi` (`core/.../utils`).
  Every Quartz module opts in through the convention plugin; apps have to opt in explicitly.
- Kotlin does not smart-cast another module's public properties. Copy to a local `val`.

A new protocol over Nostr is a new module (or a package in an existing one), not a new
repository. `testing` is an unpublished module with fixtures shared by several modules' tests:
`InMemoryRelay`, `InProcessRelays`, `RelayClientTest`, `TestWebSocketRelay`, `TestResourceLoader`.

## Build

```bash
./gradlew jvmTest                       # every module's JVM tests (`test` is aliased to it)
./gradlew :social:jvmTest --tests "com.vitorpamplona.quartz.nip10Notes.*"
./gradlew linuxX64Test                  # Linux native
./gradlew :core:testAndroidHostTest     # Android host tests, per module
./gradlew spotlessApply                 # always, after changing Kotlin files
```

On a machine with less than ~24 GB of RAM, add `--max-workers=2` to whole-repo test runs: every
module forks its own 4 GB test JVM.

Each module's build file only lists dependencies; targets, the `jvmAndroid`/`nativeMain`/
`appleMain`/`linuxMain` source-set tree, test settings, the KMP purity gate and publishing come from
`build-logic/src/main/kotlin/quartz.kmp-library.gradle.kts`. Versions live in
`gradle/libs.versions.toml`; `quartz` there is the version of every artifact.

## Verify, don't guess

Don't assert a diagnosis you haven't reproduced. If a claim can be checked with a test in under a
minute, write the failing test first, watch it fail, then explain.

## Kotlin style

- Log through Quartz's `Log` (`core/.../utils/Log.kt`), never `android.util.Log`. Interpolated
  messages use the lambda overload (`Log.d(TAG) { "…$x" }`); in a catch block pass the throwable.
- Never write fully-qualified class names inline in function bodies: import them. KDoc and
  strings are exempt.
- Hot event/parse paths use raw arrays (`TagArray = Array<Array<String>>`) and the inline `fast*`
  operators in `nip01Core/core/TagArray.kt`. Don't "modernize" them into stdlib collection calls.
- Never put raw invisible or bidirectional Unicode characters in source files (U+202A–U+202E,
  U+2066–U+2069, U+200E/U+200F, U+061C, U+200B–U+200D, U+FEFF, U+2060): write `\uXXXX` escapes.
- JVM-only APIs (Jackson, OkHttp, `java.*`) only in `jvmAndroid`, `jvmMain` or `androidMain`.
  `./gradlew verifyKmpPurity` fails fast on Linux when one leaks into code that compiles for Apple.

## Dependency licensing

Before adding any third-party dependency, read its actual LICENSE or POM, not your memory.
Quartz is MIT.

- Permissive (MIT, Apache-2.0, BSD, ISC, MPL-2.0, zlib): fine.
- LGPL, or GPL/EPL with a linking or Classpath exception: acceptable, but call it out.
- Anything stricter (GPL/AGPL without an exception, SSPL, proprietary): stop, and require an
  explicit call-out in the PR so a maintainer decides.

## Git

- Conventional commits (`feat:`, `fix:`, `refactor:`, ...). Never `--no-verify`.
- Don't open a PR on your own; hand the branch over with a test plan.
- Design docs for larger changes go in `plans/YYYY-MM-DD-<slug>.md`.
- Public API changes update [CLIENT.md](CLIENT.md), [RELAY.md](RELAY.md) and
  `agent-skill/quartz/`, the skill other projects install to use Quartz.
