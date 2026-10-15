plugins {
    id("quartz.kmp-library")
}

description = "Buzz workspaces, channels and agents"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":git"))
                api(project(":relay-groups"))
                api(project(":relay-server"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":experimental")) // test-only
            }
        }
    }
}
