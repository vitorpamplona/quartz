plugins {
    id("quartz.kmp-library")
}

description = "Podcasting 2.0 and NIP-F4 podcasts"

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
