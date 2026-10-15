plugins {
    id("quartz.kmp-library")
}

description = "Events, tags, keys, signing, NIP-19/21 entities, NIP-04/44 encryption, NIP-57 zaps and the JSON codecs"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.androidx.collection)
                api(libs.androidx.compose.runtime.annotation)
                api(libs.secp256k1.kmp.common)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.crypto.hmac)
                implementation(libs.kotlinx.crypto.sha2)
            }
        }
        getByName("jvmAndroid") {
            dependencies {
                api(libs.jackson.databind)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.core.ktx)
                api(libs.secp256k1.kmp.jni.android)
            }
        }
        getByName("nativeMain") {
            dependencies {
                implementation(libs.dev.whyoleg.cryptography.provider.optimal)
            }
        }
        jvmTest {
            dependencies {
                // libschnorr256k1, for cross-validation and the three-way benchmark
                implementation(libs.schnorr256k1.kmp)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
