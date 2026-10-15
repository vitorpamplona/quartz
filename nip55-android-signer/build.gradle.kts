plugins {
    id("quartz.kmp-library")
}

description = "NIP-55 Android signer (Amber) integration"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.core.ktx)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
