plugins {
    id("quartz.kmp-library")
}

description = "Event store interfaces, observable projections and the NIP-77 negentropy index"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                api(libs.negentropy.kmp)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":social")) // test-only
                implementation(project(":store-sqlite")) // test-only
            }
        }
    }
}
