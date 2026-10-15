import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.Family

/*
 * One Quartz library module: the targets every Quartz artifact ships for, the shared
 * source-set hierarchy, test settings and Maven Central publishing.
 *
 * A module's own build script only adds its dependencies, and its description:
 *
 *     plugins { id("quartz.kmp-library") }
 *     description = "NIP-17 private messages"
 *     kotlin { sourceSets { commonMain { dependencies { api(project(":core")) } } } }
 *
 * Source sets, beyond the default per-target ones:
 *   commonMain ─┬─ jvmAndroid ─┬─ jvmMain
 *               │              └─ androidMain
 *               └─ nativeMain ─┬─ appleMain ─┬─ iosMain (iosArm64, iosSimulatorArm64)
 *                              │             └─ macosMain (macosArm64)
 *                              └─ linuxMain (linuxX64)
 * and the same tree for tests.
 */

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.vanniktech.maven.publish")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun versionOf(name: String): Int =
    libs
        .findVersion(name)
        .get()
        .requiredVersion
        .toInt()

// :core -> quartz-core; the umbrella :quartz keeps its old coordinates.
val artifactName = if (project.name == "quartz") "quartz" else "quartz-${project.name}"

// Shared test data (relay dumps, interop vectors) lives once, at the repository root.
val sharedTestResources = rootProject.file("test-resources")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // Quartz's own modules may use each other's @InternalQuartzApi; apps have to opt in.
        optIn.add("com.vitorpamplona.quartz.utils.InternalQuartzApi")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.vitorpamplona.quartz." + project.name.replace("-", "")
        compileSdk = versionOf("android-compileSdk")
        minSdk = versionOf("android-minSdk")

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        val consumerRules = project.file("consumer-rules.pro")
        if (consumerRules.exists()) {
            optimization {
                consumerKeepRules.apply {
                    publish = true
                    file(consumerRules)
                }
            }
        }

        withHostTest {}

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    macosArm64()

    // Enable LLVM optimizations for core's linuxX64 test binary only: that is where the native
    // secp256k1 benchmark runs, and a benchmark on an unoptimized binary measures nothing. Every
    // other test binary stays unoptimized: an optimized link disables the Kotlin/Native
    // dependency cache and takes minutes per module.
    targets
        .withType<KotlinNativeTarget>()
        .matching { project.name == "core" && it.konanTarget.family == Family.LINUX }
        .configureEach {
            binaries.withType<TestExecutable>().configureEach {
                optimized = true
                debuggable = false
            }
        }

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }

        val jvmAndroid = create("jvmAndroid") { dependsOn(commonMain.get()) }
        val jvmAndroidTest =
            create("jvmAndroidTest") {
                dependsOn(commonTest.get())
                dependencies {
                    // Tests that open real sockets drive OkHttp directly.
                    implementation(libs.findLibrary("okhttp").get())
                }
            }

        jvmMain { dependsOn(jvmAndroid) }
        jvmTest { dependsOn(jvmAndroidTest) }
        androidMain { dependsOn(jvmAndroid) }

        val nativeMain = create("nativeMain") { dependsOn(commonMain.get()) }
        val nativeTest = create("nativeTest") { dependsOn(commonTest.get()) }

        val appleMain = create("appleMain") { dependsOn(nativeMain) }
        val appleTest = create("appleTest") { dependsOn(nativeTest) }

        iosMain { dependsOn(appleMain) }
        iosTest { dependsOn(appleTest) }
        getByName("iosArm64Main") { dependsOn(iosMain.get()) }
        getByName("iosSimulatorArm64Main") { dependsOn(iosMain.get()) }
        getByName("iosArm64Test") { dependsOn(iosTest.get()) }
        getByName("iosSimulatorArm64Test") { dependsOn(iosTest.get()) }

        val macosMain = create("macosMain") { dependsOn(appleMain) }
        val macosTest = create("macosTest") { dependsOn(appleTest) }
        getByName("macosArm64Main") { dependsOn(macosMain) }
        getByName("macosArm64Test") { dependsOn(macosTest) }

        val linuxMain = create("linuxMain") { dependsOn(nativeMain) }
        val linuxTest = create("linuxTest") { dependsOn(nativeTest) }
        getByName("linuxX64Main") { dependsOn(linuxMain) }
        getByName("linuxX64Test") { dependsOn(linuxTest) }
    }
}

tasks.withType<Test>().configureEach {
    // Every module forks its own test JVM; -PtestHeap=4g for the large benchmarks.
    maxHeapSize = (project.findProperty("testHeap") as? String) ?: "3g"
    // On the classpath by reference, so the 80 MB of shared data is not copied into every module.
    classpath += files(sharedTestResources)
    environment("TEST_RESOURCES_ROOT", sharedTestResources.path)
    // Opt-in gate for the benchmarks that open sockets to live relays.
    (project.findProperty("prodRelayBench") as? String)?.let { environment("PROD_RELAY_BENCH", it) }
    // Benchmark corpus sizes and JFR profiling, forwarded to the test JVM.
    listOf("negBenchN", "followBenchScale", "tagBenchScale", "fsBenchScale").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
    (project.findProperty("negProfile") as? String)?.let {
        jvmArgs("-XX:+FlightRecorder", "-XX:StartFlightRecording=filename=$it,settings=profile,dumponexit=true")
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    environment("TEST_RESOURCES_ROOT", sharedTestResources.path)
    // Propagates the variable into the iOS simulator.
    environment("SIMCTL_CHILD_TEST_RESOURCES_ROOT", sharedTestResources.path)
}

// The Kotlin Multiplatform plugin registers no plain `test` task. Alias it onto jvmTest so
// `./gradlew test` runs the JVM suites instead of silently doing nothing.
tasks.register("test") {
    group = "verification"
    description = "Runs the JVM unit tests for this Kotlin Multiplatform module."
    dependsOn("jvmTest")
}

// Fails fast, on Linux, when a source set that compiles for Apple imports a JVM-only API.
apply(from = rootProject.file("gradle/kmp-purity.gradle.kts"))

mavenPublishing {
    configure(KotlinMultiplatform(sourcesJar = SourcesJar.Sources()))

    coordinates(
        groupId = "com.vitorpamplona.quartz",
        artifactId = artifactName,
        // -PquartzVersion=1.20.0-local publishes a local build without touching the catalog.
        version = (project.findProperty("quartzVersion") as? String) ?: libs.findVersion("quartz").get().requiredVersion,
    )

    publishToMavenCentral(automaticRelease = true)
    // Release builds sign (the Release workflow provides the key); publishToMavenLocal on a
    // machine without one does not need to.
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set(provider { "Quartz: ${project.description ?: artifactName}" })
        description.set(provider { project.description ?: "Nostr library for Kotlin Multiplatform" })
        inceptionYear.set("2025")
        url.set("https://github.com/vitorpamplona/quartz/")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/vitorpamplona/quartz/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("vitorpamplona")
                name.set("Vitor Pamplona")
                url.set("http://vitorpamplona.com")
                email.set("vitor@vitorpamplona.com")
            }
        }
        scm {
            url.set("https://github.com/vitorpamplona/quartz/")
            connection.set("https://github.com/vitorpamplona/quartz.git")
        }
    }
}
