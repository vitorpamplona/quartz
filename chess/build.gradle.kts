plugins {
    id("quartz.kmp-library")
}

description = "NIP-64 chess games"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
            }
        }
        getByName("jvmAndroid") {
            dependencies {
                implementation(libs.kchesslib)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
