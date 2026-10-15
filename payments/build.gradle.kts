plugins {
    id("quartz.kmp-library")
}

description = "Wallets and payments: NIP-47 Wallet Connect, NIP-60/61 Cashu and nutzaps, NIP-87 mints, BOLT12 and on-chain zaps"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":social"))
                implementation(libs.kotlinx.serialization.cbor)
            }
        }
        getByName("jvmAndroid") {
            dependencies {
                implementation(libs.okhttp)
                implementation(libs.okhttpCoroutines)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":nip46-remote-signer")) // test-only
            }
        }
    }
}
