plugins {
    id("quartz.kmp-library")
}

description = "ContextVM: MCP over Nostr"

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
