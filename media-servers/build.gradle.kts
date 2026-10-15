plugins {
    id("quartz.kmp-library")
}

description = "Blossom and NIP-96 media servers"

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
