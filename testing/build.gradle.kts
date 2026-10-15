plugins {
    id("quartz.kmp-library")
}

description = "Test fixtures shared by the Quartz modules (not published)"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                api(project(":relay-client"))
                api(project(":store-sqlite"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmAndroid") {
            dependencies {
                api(project(":relay-server"))
                api(libs.junit)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }
    }
}

// Test support for the other modules: never published.
tasks.matching { it.name.startsWith("publish") }.configureEach { enabled = false }
