# Quartz

Quartz is the Nostr protocol library behind [Amethyst](https://github.com/vitorpamplona/amethyst),
a Kotlin Multiplatform library for JVM, Android, iOS, macOS and Linux, published to Maven Central
as `com.vitorpamplona.quartz:quartz`. It holds implementations of Nostr specifications and the
utilities to build on them: NIPs under `nipXX` packages, and whole non-NIP protocol families beside
them: `marmot/` (MLS over Nostr, `mipXX`: event classes, tags and content codecs only),
`contextvm/` (MCP over Nostr, `cepXX`), `concord/` (`cordXX`), `openagents/` (OpenAgents
NIP-CAP/NIP-EXT, `nipXxx`), `buzz/`. A new protocol over Nostr belongs here as a package. Every
event class stays here, so any client or relay parses and stores every kind typed; a protocol's
heavy engine does not. The MLS bindings' engines and clients are their own libraries,
[marmot-kmp](https://github.com/vitorpamplona/marmot-kmp) (Marmot) and
[cordn-kmp](https://github.com/vitorpamplona/cordn-kmp) (cordn, which has no event kinds), so
Quartz carries no MLS engine: nothing here may import `com.vitorpamplona.mls`. Both depend on
Quartz; Quartz must never depend on them.

The code lived in the Amethyst repo until it moved here, the same way the crypto primitives moved
to [crypto-kmp](https://github.com/vitorpamplona/crypto-kmp). Amethyst, its desktop app, the `amy`
CLI and the `geode` relay depend on the published artifact.

Canonical NIP specs live at <https://github.com/nostr-protocol/nips>; fetch `<NN>.md` from there
for a specific one (the `nostr-expert` skill shows how to map it to Quartz).

## Layout

- `quartz/`: the library module. `quartz/src/<sourceSet>/kotlin/com/vitorpamplona/quartz/…`.
  - Source sets: `commonMain`; `jvmAndroid` (OkHttp; shared by `jvmMain` and
    `androidMain`); `nativeMain` above `appleMain` (`iosMain`, `macosMain`) and `linuxMain`.
    Tests mirror them (`commonTest`, `jvmAndroidTest`, `jvmTest`, `androidHostTest`,
    `androidDeviceTest`, `nativeTest`, `appleTest`, `iosTest`, `linuxTest`).
  - `quartz/README.md` (client architecture), `quartz/CLIENT.md` (API guide),
    `quartz/RELAY.md` (running a relay on quartz's server code).
  - The root `README.md`'s Kotlin blocks are compiled: `ReadmeSnippetsTest` (in
    `jvmAndroidTest/.../quartz/docs/`) fails unless each one appears in `ReadmeSamples.kt` beside
    it or, for Android-only code, `androidHostTest/.../docs/ReadmeAndroidSamples.kt`. Change the
    sample first, run `spotlessApply`, then copy it into the README. Mark a Gradle script block
    ```` ```kotlin build.gradle.kts ```` so the test skips it.
  - `quartz/agent-skill/quartz/`: a skill for *other* projects that depend on Quartz (they copy
    it into their own skills folder; it isn't loaded here). Update it, and `quartz/CLIENT.md`,
    when the public API changes, and compile any code you add to either.
  - `quartz/plans/`: design docs, `YYYY-MM-DD-<slug>.md`; finished ones go to `plans/archive/`.
  - `quartz/benchmarks/`: native benchmark scripts.
- `quartz/src/jvmAndroidTest/kotlin/com/vitorpamplona/quartz/testing/`: the in-process test
  relay (`TestRelay`, `InProcessRelays`, `RelayClientTest`, `SyntheticEvents`, `preload`,
  `collectUntilEose`). Relay-client tests run against it, with no socket and no network.
  Tests that need a real socket relay (Ktor) live in Amethyst's `geode` module.
- `gradle/kmp-purity.gradle.kts`: the `verifyKmpPurity` task (JVM-only APIs in Apple-targeted
  source sets). Amethyst keeps the same file for `commons`/`commonsUI`: change both.

Crypto primitives (SHA, HMAC, HKDF, AES, ChaCha20, X25519, Ed25519, secure random) come from
crypto-kmp (`libs.crypto`, `com.vitorpamplona.crypto.*`); secp256k1 stays here. To change it
alongside quartz, build a local checkout: `./gradlew -PcryptoKmpDir=../crypto-kmp :quartz:jvmTest`.

## Rules

- **Protocol only.** No UI, no Compose UI, no app state. `@Immutable`/`@Stable` annotations are
  the only Compose dependency.
- **Reuse before writing.** Relay operations (fetch a set, fetch one, page past the relay cap,
  publish-and-confirm, NIP-45 count, NIP-77 sync) are `INostrClient` extension functions in
  `quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip01Core/relay/client/accessories/`
  (its `README.md` catalogs them). Because they're extensions they don't show up as members:
  grep that package before writing a new subscribe/collect loop.
- `commonMain` and `commonTest` stay free of JVM-only APIs: the Linux and Apple targets compile
  them as common code. `verifyKmpPurity` and the metadata compiles catch most of it on Linux; CI's
  `test-apple` job is the authoritative check. For `@Volatile` import `kotlin.concurrent.Volatile`;
  in tests use `assertTrue`, not `assert`.
- JVM target is 17: a dependency can't need a newer JVM than its users.
- Releases follow semantic versioning: a breaking change to the public API bumps the major version.
  Amethyst pins a version, so a breaking change needs a matching change there.
- Commits: conventional commits (`feat:`, `fix:`, …). Never `--no-verify`.

## Verify, don't guess

Don't assert a diagnosis you haven't reproduced. If a claim is checkable in under a minute, check
it before stating it: write the failing case first, watch it fail, then explain.

## Build and test

```bash
./gradlew installGitHook                        # pre-commit (spotless) and pre-push (checks + tests)
./gradlew spotlessApply
./gradlew :quartz:jvmTest                       # also `./gradlew test`
./gradlew :quartz:jvmTest --tests "com.vitorpamplona.quartz.nip52Calendar.*"
./gradlew :quartz:verifyKmpPurity :quartz:compileCommonMainKotlinMetadata :quartz:compileAppleMainKotlinMetadata
./gradlew :quartz:linuxX64Test                  # the whole suite on Linux Native (slow)
./gradlew :quartz:iosSimulatorArm64Test         # macOS only
```

`:quartz:testAndroidHostTest` (~5.8k tests, its own `androidHostTest` source set) is not run by
`test`, the pre-push hook or CI. Run it explicitly when touching relay-server or store code.

Before pushing, the pre-push hook runs `spotlessCheck`, `verifyKmpPurity`, the common and Apple
metadata compiles, `compileAndroidHostTest` (it holds the Android README samples) and `jvmTest`,
the same list as CI's build job.

## Dependency licensing

Before adding a third-party dependency, read its actual `LICENSE`/`COPYING` file or published
POM, not your memory. Quartz is MIT and ships inside apps.

- Permissive (MIT, Apache-2.0, BSD, ISC, MPL-2.0, zlib, …): OK.
- LGPL, or GPL/EPL with a linking or Classpath exception: WARN. Acceptable, but say so in your
  summary, and confirm the exception exists in the license text.
- Anything stricter (GPL/AGPL without a linking exception, SSPL, proprietary): STOP. Don't add it;
  surface it and require an explicit call-out in the PR description for a maintainer to decide.

## Kotlin style

- **Log through Quartz's `Log`** (`quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/utils/Log.kt`),
  never `android.util.Log`. Interpolated messages use the lambda overload
  (`Log.d(TAG) { "…$x" }`) so they cost nothing below `Log.minLevel`; in a catch block pass the
  throwable (`Log.w(TAG, "…", e)`) instead of interpolating `${e.message}`.
- **Hot paths use raw arrays and the `fast*` operators.** Events carry `TagArray =
  Array<Array<String>>`, and the inline `fastForEach`, `fastAny`, `fastFirstOrNull`,
  `fastFirstNotNullOfOrNull`, … in `nip01Core/core/TagArray.kt` allocate no iterator,
  intermediate list or lambda object. Don't "modernize" them into stdlib collection calls; match
  the surrounding style.
- **Act on the `Boolean` from `File.delete()`, `mkdirs()`, `renameTo()`**: they fail by returning
  `false`. Throw if the step is required, else log.
- **Never write fully-qualified class names inline in function bodies**: import the class (use
  `import … as Alias` for a real collision). Comments, KDoc and string literals are exempt.
- **Never put raw invisible or bidirectional Unicode characters in source files**: write them as
  `\uXXXX` escapes (U+202A–U+202E, U+2066–U+2069, U+200E/U+200F, U+061C, U+200B–U+200D, U+FEFF,
  U+2060), even when the character is intentional test data. The one exception is U+200D inside a
  real emoji ZWJ sequence. The Edit tool may turn a typed `\uXXXX` back into the raw character;
  then replace it at byte level (`perl -CSD -pe 's/\x{202E}/\\u202E/g'`).

## Agent configuration (Claude Code + Codex)

Both agents read the same rules, skills and checks, laid out like Amethyst's:

- **This file is the single source of rules.** Codex reads `AGENTS.md`; Claude Code reads
  `.claude/CLAUDE.md`, which imports it with `@../AGENTS.md` and adds only Claude-specific notes.
  Never write a rule in a `CLAUDE.md`.
- **Skills are written once**, in `.claude/skills/<name>/`, and symlinked from
  `.agents/skills/<name>`. Never copy a skill into `.agents/`. Here: `nostr-expert` (event
  classes, tags, NIP-19, NIP-44, mapping a NIP to Quartz) and `event-store-semantics` (the
  `IEventStore` contract and NIP-50 search).
- **A skill's `description` is how both agents decide to load it.** Keep its concrete class
  names, paths and commands; 120–300 characters, strongest triggers first.
- **Shared checks live in `tools/agent-hooks/`.** `.claude/settings.json` and `.codex/hooks.json`
  run the same pre-push scripts (spotless, agent config), and CI runs `agent_config_check.py`,
  which also checks that every path a skill cites exists and that the distributed skill's GitHub
  links point at real files.

## Releasing

Merge a PR that sets `VERSION_NAME=X.Y.Z` in `gradle.properties`: the push to `main` starts the
`Release` workflow (`release.yml`), which releases `X.Y.Z` when `VERSION_NAME` changed and `vX.Y.Z`
isn't tagged yet. It can also be run from the Actions tab on `main`, with the version. Never push
the tag by hand: Swift Package Manager reads `Package.swift` at the tag, so it must already name
that release's `QuartzKit.xcframework.zip` and checksum. The workflow runs the checks and the Apple
tests, builds the XCFramework on macOS, commits `Package.swift` (and `VERSION_NAME`) with
`tools/release/prepare-release.sh` on the tested commit, tags it `v<version>`, opens the GitHub
release with the zip, merges that commit into `main` (which may have moved meanwhile) and
publishes to Maven Central, reusing the check job's build cache. GitHub Actions must be allowed
to push to `main`. If only the `maven` job fails, re-run it: it publishes from the tag. It needs
`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_PRIVATE_KEY` and `SIGNING_PASSWORD`, the same
secrets as crypto-kmp and quic-kmp.
