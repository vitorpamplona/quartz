plugins {
    id("quartz.kmp-library")
}

description = "NIP-17 private messages, NIP-04 DMs and private note storage"

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
            }
        }
    }
}
