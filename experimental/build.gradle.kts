plugins {
    id("quartz.kmp-library")
}

description = "Draft and app-specific event kinds that are not NIPs yet"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":git"))
                api(project(":marketplace"))
                api(project(":mls"))
                api(project(":trusted-assertions"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":chess")) // test-only
                implementation(project(":store-sqlite")) // test-only
                implementation(project(":dms"))
                implementation(project(":nip46-remote-signer"))
                implementation(project(":payments"))
            }
        }
    }
}
