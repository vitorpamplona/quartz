plugins {
    id("quartz.kmp-library")
}

description = "NIP-66 relay discovery and monitoring"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":relay-client"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":store-sqlite")) // test-only
            }
        }
    }
}
