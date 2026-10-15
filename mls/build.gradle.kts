plugins {
    id("quartz.kmp-library")
}

description = "RFC 9420 Messaging Layer Security engine"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                implementation(libs.kotlinx.crypto.hmac)
                implementation(libs.kotlinx.crypto.sha2)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":marmot")) // test-only
            }
        }
    }
}
