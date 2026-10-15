plugins {
    id("quartz.kmp-library")
}

description = "Concord group chats"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":dms"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
