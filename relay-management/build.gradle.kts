plugins {
    id("quartz.kmp-library")
}

description = "NIP-86 relay management and NIP-43 relay membership events and client"

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
