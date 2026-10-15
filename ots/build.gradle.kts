plugins {
    id("quartz.kmp-library")
}

description = "NIP-03 OpenTimestamps attestations"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
            }
        }
        getByName("jvmAndroid") {
            dependencies {
                implementation(libs.okhttp)
                implementation(libs.okhttpCoroutines)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
