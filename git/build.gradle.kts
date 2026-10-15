plugins {
    id("quartz.kmp-library")
}

description = "NIP-34 git collaboration: repositories, patches, pull requests, issues, CI"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":social"))
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
