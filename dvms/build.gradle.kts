plugins {
    id("quartz.kmp-library")
}

description = "NIP-90 data vending machines"

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
