plugins {
    id("quartz.kmp-library")
}

description = "Relay engine (NostrServer), policies, NIP-86 admin and NIP-FE relay-over-HTTP"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":relay-client"))
                api(project(":relay-management"))
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
                implementation(project(":store-sqlite")) // test-only
                // Its tests store social kinds and Marmot key packages (30443), which need
                // their typed classes: the store only replaces an addressable event it parsed
                // as an AddressableEvent.
                implementation(project(":social")) // test-only
                implementation(project(":marmot")) // test-only
            }
        }
    }
}
