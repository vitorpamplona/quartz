plugins {
    id("quartz.kmp-library")
}

description = "NIP-46 remote signer (bunker) client and server"

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
                implementation(project(":relay-server")) // test-only
                implementation(project(":store-sqlite")) // test-only
            }
        }
    }
}
