plugins {
    id("quartz.kmp-library")
}

description = "NIP-15 marketplaces, NIP-99 classifieds, NIP-69 P2P orders and NIP-CC geocaching"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":social"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":testing"))
            }
        }
    }
}
