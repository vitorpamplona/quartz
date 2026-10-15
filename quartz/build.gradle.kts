plugins {
    id("quartz.kmp-library")
}

description = "Every Quartz module in one dependency, plus KindNames"

kotlin {
    // The iOS framework an Xcode project links: every module, all of it visible to Swift.
    val xcfName = "quartz-kmpKit"
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = xcfName
            isStatic = true
            binaryOption("bundleId", "com.vitorpamplona.quartz")
            export(project(":core"))
            export(project(":store"))
            export(project(":store-sqlite"))
            export(project(":relay-client"))
            export(project(":relay-server"))
            export(project(":relay-management"))
            export(project(":nip46-remote-signer"))
            export(project(":social"))
            export(project(":dms"))
            export(project(":payments"))
            export(project(":trusted-assertions"))
            export(project(":git"))
            export(project(":chess"))
            export(project(":ots"))
            export(project(":relay-groups"))
            export(project(":dvms"))
            export(project(":podcasts"))
            export(project(":marketplace"))
            export(project(":media-servers"))
            export(project(":nsites"))
            export(project(":webrtc-calls"))
            export(project(":ble"))
            export(project(":push-notifications"))
            export(project(":relay-monitor"))
            export(project(":mls"))
            export(project(":marmot"))
            export(project(":cordn"))
            export(project(":contextvm"))
            export(project(":concord"))
            export(project(":buzz"))
            export(project(":cyberspace"))
            export(project(":experimental"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                api(project(":store"))
                api(project(":store-sqlite"))
                api(project(":relay-client"))
                api(project(":relay-server"))
                api(project(":relay-management"))
                api(project(":nip46-remote-signer"))
                api(project(":social"))
                api(project(":dms"))
                api(project(":payments"))
                api(project(":trusted-assertions"))
                api(project(":git"))
                api(project(":chess"))
                api(project(":ots"))
                api(project(":relay-groups"))
                api(project(":dvms"))
                api(project(":podcasts"))
                api(project(":marketplace"))
                api(project(":media-servers"))
                api(project(":nsites"))
                api(project(":webrtc-calls"))
                api(project(":ble"))
                api(project(":push-notifications"))
                api(project(":relay-monitor"))
                api(project(":mls"))
                api(project(":marmot"))
                api(project(":cordn"))
                api(project(":contextvm"))
                api(project(":concord"))
                api(project(":buzz"))
                api(project(":cyberspace"))
                api(project(":experimental"))
            }
        }
        androidMain {
            dependencies {
                // NIP-55 only exists on Android.
                api(project(":nip55-android-signer"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
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
                // Custom C secp256k1 (libschnorr256k1) for cross-validation + 3-way benchmark
                implementation(libs.schnorr256k1.kmp)
            }
        }
    }
}
