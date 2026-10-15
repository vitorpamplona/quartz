import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare

// The library modules take their Kotlin, Android and publishing setup from the
// `quartz.kmp-library` convention plugin in build-logic/.
plugins {
    alias(libs.plugins.diffplugSpotless)
}

allprojects {
    apply(plugin = "com.diffplug.spotless")

    if (project === rootProject) {
        configure<SpotlessExtensionPredeclare> {
            kotlin {
                ktlint("1.7.1")
            }
        }
        spotless {
            kotlinGradle {
                target("*.gradle.kts", "gradle/*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
            }
        }
    } else {
        spotless {
            kotlin {
                target("src/**/*.kt")
                ktlint("1.7.1")
                licenseHeaderFile(
                    rootProject.file(".spotless/copyright.kt"),
                    "@file:|package|import|class|object|sealed|open|interface|abstract ",
                )
            }

            kotlinGradle {
                target("*.gradle.kts")
            }
        }
    }
}
