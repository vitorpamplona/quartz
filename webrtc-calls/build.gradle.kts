plugins {
    id("quartz.kmp-library")
}

description = "NIP-AC WebRTC calls"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
