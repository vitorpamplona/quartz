plugins {
    id("quartz.kmp-library")
}

description = "NIP-29 relay-based groups"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":social"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":buzz")) // test-only
            }
        }
    }
}
