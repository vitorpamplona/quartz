plugins {
    id("quartz.kmp-library")
}

description = "NIP-5A static websites and NIP-5D napplets"

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
