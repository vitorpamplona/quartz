plugins {
    id("quartz.kmp-library")
}

description = "NIP-BE Nostr over Bluetooth Low Energy"

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
            }
        }
    }
}
