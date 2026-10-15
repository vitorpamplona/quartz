plugins {
    id("quartz.kmp-library")
}

description = "NIP-85 trusted assertions and trust-network sync"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":relay-client"))
                api(project(":social"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":relay-server")) // test-only
            }
        }
    }
}
