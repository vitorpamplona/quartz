# Quartz

A Nostr library for Kotlin Multiplatform: JVM, Android, iOS, macOS and Linux. Quartz has the
event classes, tags and builders for the NIPs, signing (secp256k1) and NIP-04/NIP-44 encryption,
a relay client with subscriptions, paging, publish-and-confirm, NIP-45 counts and NIP-77 sync, a
SQLite event store with NIP-50 search, and the server side to run a relay on. It also carries
whole protocol families built on Nostr: Marmot's events (MLS over Nostr), ContextVM, Concord,
OpenAgents and Buzz. The MLS engines live in their own libraries on top of Quartz:
[marmot-kmp](https://github.com/vitorpamplona/marmot-kmp) (Marmot groups) and
[cordn-kmp](https://github.com/vitorpamplona/cordn-kmp) (cordn).

It is the protocol layer of [Amethyst](https://github.com/vitorpamplona/amethyst), its desktop
app, the `amy` CLI and the `geode` relay.

## Install

```kotlin build.gradle.kts
dependencies {
    implementation("com.vitorpamplona.quartz:quartz:<version>")
}
```

| Requirement | |
|---|---|
| JVM | Java 17 |
| Android | minSdk 26 |
| Apple | iosArm64, iosSimulatorArm64, macosArm64 (Kotlin), iOS 15 / macOS 12 (Swift) |
| Linux | linuxX64 |

### Swift Package Manager

Swift apps get Quartz as the `QuartzKit` framework. In Xcode, use File > Add Package
Dependencies and enter `https://github.com/vitorpamplona/quartz`, or add it to `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/vitorpamplona/quartz", from: "<version>"),
],
targets: [
    .target(name: "MyApp", dependencies: [.product(name: "QuartzKit", package: "quartz")]),
]
```

Kotlin reaches Swift through Objective-C, so a few names change: an extension on a Kotlin class
becomes a method (`keyPair.pubKey.toHexKey()`), top-level functions and extensions on `String`
or numbers sit on a class named after their file (`HexKeyKt.hexToByteArray("…")`), default
arguments must be passed explicitly, and `suspend` functions become `async`. The Swift package starts with the first
release after 2.3.0.

### Snapshots (JitPack)

Tagged releases go to Maven Central. To test an unreleased fix straight from `main` or a branch, use
[JitPack](https://jitpack.io/#vitorpamplona/quartz), which builds the library on demand from any git
ref:

```kotlin build.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // version can be a tag, a commit hash, or "<branch>-SNAPSHOT"
    implementation("com.github.vitorpamplona.quartz:quartz:main-SNAPSHOT")
}
```

Prefer a Maven Central release for anything shipping to production; JitPack snapshots are not
guaranteed stable.

## Guides

- [Client architecture](quartz/README.md): wiring the store, cache, client and signer into an app.
- [Client API](quartz/CLIENT.md): transports, fetching, subscribing and publishing.
- [Relay](quartz/RELAY.md): running a relay on Quartz's server code.
- [One-shot relay operations](quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip01Core/relay/client/accessories/README.md):
  `fetchAll`, `fetchFirst`, `fetchAllPages`, `publishAndConfirm`, `count`, `negentropyReconcile`.
- [Agent skill](quartz/agent-skill/README.md): install it in your project so a coding agent
  reaches for Quartz's helpers instead of re-implementing them.

## Usage

Manage logged in users with the `KeyPair` class

```kt
val random = KeyPair() // creates a random key
val fromHex = KeyPair("hex...".hexToByteArray())
val fromNsec = KeyPair("nsec1...".bechToBytes())
val fromMnemonic = KeyPair(Nip06().privateKeyFromMnemonic("<mnemonic>"))
val readOnlyFromHex = KeyPair(pubKey = "hex...".hexToByteArray())
val readOnlyFromNpub = KeyPair(pubKey = "npub1...".bechToBytes())
```

Create signers that can be Internal, when you have the private key or a read-only public key,
or External, when it is controlled by Amber in NIP-55 (Android only).

Use either the `NostrSignerInternal` or `NostrSignerExternal` class:

```kt
val signer = NostrSignerInternal(keyPair)
```

```kt
val amberSigner =
    NostrSignerExternal(
        pubKey = keyPair.pubKey.toHexKey(),
        packageName = signerPackageName, // Amber package name
        contentResolver = appContext.contentResolver,
    )
```

Create a single `NostrClient` for the entire application and control which relays it will access by
registering subscriptions and sending events. The pool will automatically change based on filters +
outbox events.

You will need a coroutine scope to process events and if you are using OKHttp, we offer a basic
wrapper to create the socket connections themselves.

```kt
val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
val rootClient = OkHttpClient.Builder().build()
val socketBuilder = BasicOkHttpWebSocket.Builder { url -> rootClient }

val client = NostrClient(socketBuilder, appScope)
```

To answer NIP-42 AUTH challenges, given a logged-in `signer`. The lambda receives the relay asking,
the template to sign and whether the user may be prompted (`interactive`):

```kt
val authCoordinator =
    RelayAuthenticator(client, appScope) { relay, authTemplate, interactive ->
        listOf(
            // for each signed-in user, return an event
            signer.sign(authTemplate),
        )
    }
```

To make a request subscription simply do:

```kt
val metadataSub =
    client.subscribe(
        relay = "wss://nos.lol",
        filter =
            Filter(
                kinds = listOf(MetadataEvent.KIND),
                authors = listOf(signer.pubKey),
            ),
    ) { event ->
        // consume event
    }
```

The client will add the relay to the pool, connect to it and start receiving events. The
`metadataSub` will be active until you call `metadataSub.close()`. If the client disconnects
and reconnects, the sub will be active again.

To manage subscriptions that change over time, pass a filters lambda instead. It returns the
filters to send to each relay, and is read again every time you refresh the subscription.

```kt
val metadataSub =
    client.subscribe(
        filters = {
            // Let's say you have a list of users that need to be rendered
            val users = pubkeysSeeingInTheScreen()
            // And a cache repository with their outbox relays
            val outboxRelays = outboxRelays(users)

            val filters =
                listOf(
                    Filter(
                        kinds = listOf(MetadataEvent.KIND),
                        authors = users,
                    ),
                )

            outboxRelays.associateWith { filters }
        },
    ) { event ->
        // consume event
    }
```

Call `metadataSub.refresh()` when the users or relays change: it runs the lambda again and updates
the subscription on every relay. Call `metadataSub.close()` to stop it.

For one-shot work (fetch a set of events and stop, page past a relay's limit, publish and wait for
the relay's `OK`, NIP-45 counts, NIP-77 sync), use the extensions listed in
[One-shot relay operations](quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/nip01Core/relay/client/accessories/README.md)
instead of writing a subscribe-and-collect loop.

When your app goes to the background, you can use NostrClient's `connect` and `disconnect`
methods to stop all communication to relays. Add the `connect` to your `onResume` and `disconnect`
to `onPause` methods.

## Dependencies

Crypto primitives come from [crypto-kmp](https://github.com/vitorpamplona/crypto-kmp), secp256k1 from
[secp256k1-kmp](https://github.com/ACINQ/secp256k1-kmp) and NIP-77 from
[negentropy-kmp](https://github.com/vitorpamplona/negentropy-kmp). On the JVM and Android, Jackson
parses events and OkHttp is the bundled WebSocket transport.

## Developer Setup

```bash
./gradlew installGitHook            # pre-commit (spotless) and pre-push (checks + tests) hooks
./gradlew spotlessApply
./gradlew :quartz:jvmTest
./gradlew :quartz:verifyKmpPurity :quartz:compileCommonMainKotlinMetadata :quartz:compileAppleMainKotlinMetadata
./gradlew :quartz:linuxX64Test      # the whole suite on Linux Native
./gradlew :quartz:iosSimulatorArm64Test   # macOS only
```

To work on crypto-kmp alongside Quartz, point Gradle at a local checkout:
`./gradlew -PcryptoKmpDir=../crypto-kmp :quartz:jvmTest`. To try a Quartz
change in Amethyst, build Amethyst with `-PquartzDir=../quartz`.

[AGENTS.md](AGENTS.md) has the project rules, for people and coding agents alike.

## Versioning

Releases follow [semantic versioning](https://semver.org/): a breaking change to the public API
bumps the major version. Each release is tagged `v<version>` and listed on the
[releases page](https://github.com/vitorpamplona/quartz/releases). Until 1.19.0 Quartz was
released from the Amethyst repo with the app's version number.

## License

MIT, see [LICENSE](LICENSE).
