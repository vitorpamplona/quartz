import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.serialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.vitorpamplona.quartz"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }

        withHostTest {}

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // QuartzKit.xcframework is what Swift Package Manager users get: Package.swift at the repo root
    // points at the zip the release workflow attaches to each GitHub release. Dynamic, so the
    // framework carries every native library it links (secp256k1, SQLite) and a Swift app links
    // nothing else. `assembleQuartzKitReleaseXCFramework` builds it; macOS only.
    val quartzKit = XCFramework("QuartzKit")
    val appleVersion = project.findProperty("VERSION_NAME")?.toString() ?: "0.0.0"

    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "QuartzKit"
            isStatic = false
            binaryOption("bundleId", "com.vitorpamplona.quartz")
            binaryOption("bundleShortVersionString", appleVersion)
            binaryOption("bundleVersion", appleVersion)
            // Lets Swift call Quartz's suspend functions from any thread, not just the main one.
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
            quartzKit.add(this)
        }
    }

    linuxX64()

    // This makes sure that the resource file directory is visible for iOS tests.
    val rootDir = "${projectDir.path}/src/commonTest/resources"

    tasks.withType<Test>().configureEach {
        maxHeapSize = "4g"
        environment("TEST_RESOURCES_ROOT", rootDir)
        // Opt-in gate for ProductionReceiverBenchmark (opens sockets to live
        // relays). `-PprodRelayBench=1` reaches the daemon even when an env
        // var would not survive daemon reuse.
        (project.findProperty("prodRelayBench") as? String)?.let {
            environment("PROD_RELAY_BENCH", it)
        }
        // Forward the negentropy-benchmark corpus size to the test JVM.
        System.getProperty("negBenchN")?.let { systemProperty("negBenchN", it) }
        System.getProperty("followBenchScale")?.let { systemProperty("followBenchScale", it) }
        System.getProperty("tagBenchScale")?.let { systemProperty("tagBenchScale", it) }
        System.getProperty("fsBenchScale")?.let { systemProperty("fsBenchScale", it) }
        System.getProperty("powBenchRounds")?.let { systemProperty("powBenchRounds", it) }
        // Opt-in JFR profiling of a benchmark run (-PnegProfile=/tmp/neg.jfr).
        (project.findProperty("negProfile") as? String)?.let {
            jvmArgs("-XX:+FlightRecorder", "-XX:StartFlightRecording=filename=$it,settings=profile,dumponexit=true")
        }
    }

    tasks.withType<KotlinNativeTest>().configureEach {
        environment("TEST_RESOURCES_ROOT", rootDir)
        // This is necessary to have the variable propagated on iOS
        environment("SIMCTL_CHILD_TEST_RESOURCES_ROOT", rootDir)
    }

    // Enable LLVM optimizations for the linuxX64 test binary only: that is where
    // Secp256k1NativeBenchmark (src/linuxTest) runs, and a benchmark on an
    // unoptimized binary measures nothing (~20× slower code). Use the binary-level
    // API instead of the -opt freeCompilerArg to avoid the "Unsupported combination
    // of flags: -opt and -g" conflict in Kotlin/Native 2.1+.
    //
    // The Apple test binaries stay unoptimized on purpose. An optimized link is a
    // release-style LTO build with the Kotlin/Native dependency cache disabled: on
    // the 3-core GitHub macOS runner it took 12 minutes for the iOS simulator test
    // binary (2.5 min here), while the unoptimized link takes 45 s here and the
    // test suite minus the benchmark runs in ~90 s instead of ~25 s. Net win on
    // every machine, and it is what made the test-quartz-ios job hit its timeout.
    targets
        .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
        .matching { it.konanTarget.family == org.jetbrains.kotlin.konan.target.Family.LINUX }
        .configureEach {
            binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                optimized = true
                debuggable = false
            }
        }

    // Source set declarations.
    // Declaring a target automatically creates a source set with the same name. By default, the
    // Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
    // common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)

                // For LruCache
                implementation(libs.androidx.collection)

                // @Immutable and @Stable
                implementation(libs.androidx.compose.runtime.annotation)

                // Bitcoin secp256k1 bindings
                api(libs.secp256k1.kmp.common)

                // Kotlin serialization for the times where we need the Json tree and performance is not that important.
                implementation(libs.kotlinx.serialization.json)

                // CBOR serialization for the NUT-00 v4 (cashuB) token wire format.
                implementation(libs.kotlinx.serialization.cbor)

                // immutable collections to avoid recomposition
                implementation(libs.kotlinx.collections.immutable)

                // SQLite KMP driver for event store
                api(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)

                // Negentropy set reconciliation (NIP-77)
                api(libs.negentropy.kmp)

                // SHA, HMAC, HKDF, AES, ChaCha20, X25519, Ed25519 and secure random, from
                // vitorpamplona/crypto-kmp. api: Quartz's own API takes and returns its types.
                api(libs.crypto)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)

                // SQLite bundled driver for tests
                api(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)
            }
        }

        // Must be defined before androidMain and jvmMain
        val jvmAndroid =
            create("jvmAndroid") {
                dependsOn(commonMain.get())

                dependencies {
                    // Websockets API
                    implementation(libs.okhttp)
                    implementation(libs.okhttpCoroutines)
                }
            }

        // Must be defined before androidMain and jvmMain
        val jvmAndroidTest =
            create("jvmAndroidTest") {
                dependsOn(commonTest.get())
                dependencies {
                    implementation(libs.kotlin.test)
                    implementation(libs.kotlinx.coroutines.test)
                }
            }

        jvmMain {
            dependsOn(jvmAndroid)
            dependencies {
                // Bitcoin secp256k1 bindings
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
        }

        jvmTest {
            dependsOn(jvmAndroidTest)
            dependencies {
                // Bitcoin secp256k1 bindings
                implementation(libs.secp256k1.kmp.jni.jvm)

                // Custom C secp256k1 (libschnorr256k1) for cross-validation + 3-way benchmark
                implementation(libs.schnorr256k1.kmp)

                // SQLite bundled driver for JVM tests
                implementation(libs.androidx.sqlite.bundled.jvm)
            }
        }

        androidMain {
            dependsOn(jvmAndroid)
            dependencies {
                implementation(libs.androidx.core.ktx)

                // Bitcoin secp256k1 bindings to Android
                api(libs.secp256k1.kmp.jni.android)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)

                // Bitcoin secp256k1 bindings
                implementation(libs.secp256k1.kmp.jni.jvm)

                // SQLite bundled driver for Host tests
                implementation(libs.androidx.sqlite.bundled.jvm)
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.junit)
                implementation(libs.androidx.espresso.core)

                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)

                // Bitcoin secp256k1 bindings to Android
                api(libs.secp256k1.kmp.jni.android)

                // Custom C secp256k1 (libschnorr256k1) for cross-validation + 3-way benchmark
                implementation(libs.schnorr256k1.kmp)
            }
        }

        // Must be defined before appleMain, linuxMain, etc.
        val nativeMain =
            create("nativeMain") {
                dependsOn(commonMain.get())
            }

        val nativeTest =
            create("nativeTest") {
                dependsOn(commonTest.get())
            }

        // Must be defined before iosMain and macosMain
        val appleMain =
            create("appleMain") {
                dependsOn(nativeMain)
            }

        val appleTest =
            create("appleTest") {
                dependsOn(nativeTest)
            }

        iosMain {
            dependsOn(appleMain)
        }

        getByName("iosArm64Main") {
            dependsOn(iosMain.get())
        }

        getByName("iosSimulatorArm64Main") {
            dependsOn(iosMain.get())
        }

        iosTest {
            dependsOn(appleTest)
        }

        getByName("iosArm64Test") {
            dependsOn(iosTest.get())
        }

        getByName("iosSimulatorArm64Test") {
            dependsOn(iosTest.get())
        }

        val macosMain =
            create("macosMain") {
                dependsOn(appleMain)
            }

        val macosTest =
            create("macosTest") {
                dependsOn(appleTest)
            }

        getByName("macosArm64Main") {
            dependsOn(macosMain)
        }

        getByName("macosArm64Test") {
            dependsOn(macosTest)
        }

        val linuxMain =
            create("linuxMain") {
                dependsOn(nativeMain)
            }

        val linuxTest =
            create("linuxTest") {
                dependsOn(nativeTest)
            }

        getByName("linuxX64Main") {
            dependsOn(linuxMain)
        }

        getByName("linuxX64Test") {
            dependsOn(linuxTest)
        }
    }
}

// iOS purity gate. JVM-only libraries (OkHttp, and Jackson should it come back)
// must never appear
// in source sets that compile for iOS / native / common targets. The KMP
// build would eventually fail with an "unresolved reference" if these
// leaked in, but that requires a macOS runner. This task is a fast Linux
// linter that fails with a clear message before iOS CI ever spins up.
//
// Scope: source sets whose code is compiled for at least one non-JVM
// target. Excludes jvmAndroid, jvmMain, androidMain (and their tests),
// where JVM-only libraries such as OkHttp are legitimately used.
//
// See gradle/kmp-purity.gradle.kts for the pattern table and checked dirs.
apply(from = rootProject.file("gradle/kmp-purity.gradle.kts"))

mavenPublishing {
    // sources publishing is always enabled by the Kotlin Multiplatform plugin
    configure(
        KotlinMultiplatform(
            // whether to publish a sources jar
            sourcesJar = SourcesJar.Sources(),
        ),
    )

    // groupId, artifactId, and version are configured via gradle.properties
    // (GROUP, POM_ARTIFACT_ID, VERSION_NAME) so CI can override VERSION_NAME
    // via ORG_GRADLE_PROJECT_VERSION_NAME without conflicting with a hardcoded value.

    // Configure publishing to Maven Central
    publishToMavenCentral(automaticRelease = true)

    // Enable GPG signing for all publications
    signAllPublications()

    pom {
        name = "Quartz: Nostr Library for Kotlin Multiplatform"
        description = "Nostr protocol library for Kotlin Multiplatform: JVM, Android, iOS, macOS and Linux"
        inceptionYear = "2025"
        url = "https://github.com/vitorpamplona/quartz/"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/vitorpamplona/quartz/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "vitorpamplona"
                name = "Vitor Pamplona"
                url = "https://vitorpamplona.com"
                email = "vitor@vitorpamplona.com"
            }
        }
        scm {
            url = "https://github.com/vitorpamplona/quartz/"
            connection = "scm:git:https://github.com/vitorpamplona/quartz.git"
            developerConnection = "scm:git:ssh://git@github.com/vitorpamplona/quartz.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/vitorpamplona/quartz/issues"
        }
    }
}
