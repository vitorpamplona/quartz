plugins {
    id("quartz.kmp-library")
}

description = "NostrClient: relay pool, subscriptions, fetch/publish helpers, NIP-42 auth, NIP-45 counts, NIP-77 sync, OkHttp websockets"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":store"))
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
                implementation(project(":relay-server")) // test-only
                implementation(project(":social")) // test-only
                implementation(project(":store-sqlite")) // test-only
            }
        }
    }
}
