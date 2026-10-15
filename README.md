# Quartz

Nostr for Kotlin Multiplatform: JVM, Android, iOS, macOS and Linux. Quartz is the protocol
library behind [Amethyst](https://github.com/vitorpamplona/amethyst): event classes for 100+
NIPs, signing and encryption, a relay client, a relay server and SQLite/filesystem event stores.

Quartz ships as a set of small libraries. Depend on the ones you use, or on `quartz` to get all
of them.

```kotlin
dependencies {
    // Everything:
    implementation("com.vitorpamplona.quartz:quartz:1.20.0")

    // Or only what you need, e.g. a client that posts notes and reads relays:
    implementation("com.vitorpamplona.quartz:quartz-social:1.20.0")
    implementation("com.vitorpamplona.quartz:quartz-relay-client:1.20.0")
}
```

Package names do not depend on the artifact: `com.vitorpamplona.quartz.nip10Notes.TextNoteEvent`
is the same class whether it comes from `quartz` or from `quartz-social`, so moving from the
umbrella to individual artifacts is a dependency change, not a code change.

## Modules

Each artifact pulls in the ones it is built on (the "Builds on" column) through `api`
dependencies.

### Foundation

| Artifact | Builds on | What it is | Third-party deps |
|---|---|---|---|
| `quartz-core` | | Events, tags, keys and signing, NIP-19/21 entities, NIP-04/44 encryption, NIP-57 zaps, JSON codecs, NIP-01 filters and relay messages, and the event-kind registry | secp256k1-kmp, kotlinx-coroutines, kotlinx-serialization-json, kotlinx-collections-immutable, androidx.collection; Jackson (JVM/Android) |
| `quartz-store` | core | Event store interfaces, observable projections, the NIP-77 negentropy index | negentropy-kmp |
| `quartz-store-sqlite` | store | SQLite event store (all targets) and filesystem store (JVM) | androidx.sqlite |
| `quartz-relay-client` | store | `NostrClient`: relay pool, subscriptions, fetch/publish helpers, NIP-42 auth, NIP-45 counts, NIP-77 sync; OkHttp websockets on JVM/Android | OkHttp (JVM/Android) |
| `quartz-relay-server` | relay-client, relay-management | `NostrServer`: relay engine, policies, NIP-86 admin, NIP-FE relay over HTTP | OkHttp (JVM/Android) |
| `quartz-relay-management` | core | NIP-86 relay management and NIP-43 relay membership events and client | |

### Signers

| Artifact | Builds on | What it is |
|---|---|---|
| `quartz-nip46-remote-signer` | relay-client | NIP-46 remote signer (bunker) client and server |
| `quartz-nip55-android-signer` | core | NIP-55 Android signer (Amber) integration, Android only |

Local key signing (`NostrSignerInternal`) is in `quartz-core`.

### NIPs

| Artifact | Builds on | What it is |
|---|---|---|
| `quartz-social` | core | The social NIPs: follows, notes, threads, reposts, reactions, comments, long-form, lists, media (pictures, video, voice), badges, communities, live activities, calendar, polls, wikis, labels, reports, highlights, app handlers, custom emoji |
| `quartz-dms` | social | NIP-17 private messages, NIP-04 DMs, private note storage |
| `quartz-payments` | social | NIP-47 Wallet Connect, NIP-60/61 Cashu and nutzaps, NIP-87 mints, BOLT12 and on-chain zaps, NIP-75 goals |
| `quartz-trusted-assertions` | relay-client, social | NIP-85 trusted assertions and trust-network sync |
| `quartz-relay-groups` | social | NIP-29 relay-based groups |
| `quartz-git` | social | NIP-34 git: repositories, patches, pull requests, issues, CI |
| `quartz-marketplace` | social | NIP-15 marketplaces, NIP-99 classifieds, NIP-69 P2P orders, NIP-CC geocaching |
| `quartz-podcasts` | social | Podcasting 2.0 and NIP-F4 podcasts |
| `quartz-media-servers` | social | Blossom and NIP-96 media servers |
| `quartz-dvms` | core | NIP-90 data vending machines |
| `quartz-nsites` | core | NIP-5A static websites, NIP-5D napplets |
| `quartz-chess` | core | NIP-64 chess (kchesslib on JVM/Android) |
| `quartz-ots` | core | NIP-03 OpenTimestamps (OkHttp on JVM/Android) |
| `quartz-webrtc-calls` | core | NIP-AC WebRTC calls |
| `quartz-ble` | relay-client | NIP-BE Nostr over Bluetooth Low Energy |
| `quartz-push-notifications` | core | Push notification registration and preferences |
| `quartz-relay-monitor` | relay-client | NIP-66 relay discovery and monitoring |
| `quartz-experimental` | git, marketplace, mls, trusted-assertions | Draft and app-specific kinds that are not NIPs yet |

### Protocols over Nostr

| Artifact | Builds on | What it is |
|---|---|---|
| `quartz-mls` | core | RFC 9420 Messaging Layer Security engine |
| `quartz-marmot` | dms, mls, relay-client | Marmot: MLS group messaging over Nostr |
| `quartz-cordn` | contextvm, mls | Cordn: MLS over an MCP coordinator |
| `quartz-contextvm` | core | ContextVM: MCP over Nostr |
| `quartz-concord` | dms | Concord group chats |
| `quartz-buzz` | git, relay-groups, relay-server | Buzz workspaces, channels and agents |
| `quartz-cyberspace` | core | Cyberspace |

### Umbrella

`quartz` depends on every artifact above. It also holds `KindNames`, the display names of every
kind, and builds the `quartz-kmpKit` iOS framework.

## Typed events and the kind registry

Parsing JSON gives you the typed class for its kind (`TextNoteEvent`, `ZapRequestEvent`, ...)
for every artifact on the classpath; kinds from artifacts you did not add parse as a plain
`Event`. Each artifact registers its classes with `EventFactory` through an
`EventKindProvider` (`SocialEventKinds`, `PaymentEventKinds`, ...):

- **JVM and Android:** automatic. `EventFactory` finds the providers of the artifacts on the classpath.
- **Native (iOS, macOS, Linux):** automatic as well. Each artifact registers when the binary loads.

Your own kinds go through `EventFactory.factories[kind] = EventBuilder { ... }`, or your own
`EventKindProvider` passed to `EventFactory.register`.

## Guides

- [GUIDE.md](GUIDE.md): structuring a client app around Quartz.
- [CLIENT.md](CLIENT.md): the client API: transports, fetching, publishing.
- [RELAY.md](RELAY.md): running a relay with `NostrServer`.
- [agent-skill/](agent-skill/README.md): a skill for coding agents that build on Quartz.

## Building

```bash
./gradlew jvmTest                  # every module's JVM tests
./gradlew :social:jvmTest          # one module
./gradlew linuxX64Test             # Linux native
./gradlew iosSimulatorArm64Test    # iOS (macOS only)
./gradlew spotlessApply            # formatting
```

Android microbenchmarks (event parsing, hashing, signing, NIP-44, gift wraps, caches, the
SQLite store) live in the unpublished `benchmark` module and run on a device or emulator:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
./benchmarks/run_all.sh            # secp256k1: C, Kotlin/Native, JVM and Android side by side
```

Modules share their setup through the `quartz.kmp-library` convention plugin in
[`build-logic/`](build-logic/src/main/kotlin/quartz.kmp-library.gradle.kts). Shared test data
lives in `test-resources/`, and fixtures shared by several modules' tests (in-memory relays,
resource loaders) in the unpublished `testing` module.

## Trying a change in an app before releasing it

```bash
./gradlew publishToMavenLocal -PquartzVersion=1.20.0-local
```

then depend on `1.20.0-local` from a build that has `mavenLocal()` among its repositories.

## Releasing

Set `quartz` in `gradle/libs.versions.toml`, commit, and push a matching `vX.Y.Z` tag. The
Release workflow publishes every artifact to Maven Central.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), and [CONTRIBUTING-WITH-AI.md](CONTRIBUTING-WITH-AI.md) when
an AI assistant wrote much of the change. Report security issues privately, as
[SECURITY.md](SECURITY.md) describes.

## License

MIT. See [LICENSE](LICENSE).
