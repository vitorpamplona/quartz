plugins {
    id("quartz.kmp-library")
}

description = "The social NIPs: follows, notes, threads, reposts, reactions, comments, long-form, lists, media, badges, communities, live activities, calendar, polls, wikis and more"

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
                implementation(project(":experimental")) // test-only
            }
        }
    }
}
