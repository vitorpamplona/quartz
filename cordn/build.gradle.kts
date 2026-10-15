plugins {
    id("quartz.kmp-library")
}

description = "Cordn: MLS over an MCP coordinator"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":contextvm"))
                api(project(":mls"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
                implementation(project(":marmot")) // test-only
                implementation(project(":marmot"))
            }
        }
    }
}
