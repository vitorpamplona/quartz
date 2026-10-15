plugins {
    id("quartz.kmp-library")
}

description = "Marmot: MLS group messaging over Nostr"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":dms"))
                api(project(":mls"))
                api(project(":relay-client"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
