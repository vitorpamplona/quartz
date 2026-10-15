plugins {
    id("quartz.kmp-library")
}

description = "SQLite (all targets) and filesystem (JVM) event stores"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":store"))
                api(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":experimental")) // test-only
                implementation(project(":git")) // test-only
                implementation(project(":social")) // test-only
                implementation(project(":trusted-assertions")) // test-only
            }
        }
    }
}
