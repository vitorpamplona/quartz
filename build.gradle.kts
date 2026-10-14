import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.diffplugSpotless)
}

// Every Quartz artifact is released together under one version, read from
// gradle/libs.versions.toml (`quartz`).
val quartzVersion = libs.versions.quartz.get()

allprojects {
    group = "com.vitorpamplona.quartz"
    version = quartzVersion

    apply(plugin = "com.diffplug.spotless")

    if (project === rootProject) {
        configure<SpotlessExtensionPredeclare> {
            kotlin {
                ktlint("1.7.1")
            }
        }
        spotless {
            kotlinGradle {
                target("*.gradle.kts", "gradle/*.gradle.kts")
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

subprojects {
    afterEvaluate {
        // The Kotlin Multiplatform plugin never registers a plain `test` task: it creates one
        // task per target (jvmTest, linuxX64Test, ...) plus `allTests`. Alias `test` onto
        // jvmTest so `./gradlew test` runs the JVM suites instead of silently doing nothing.
        // The other targets (androidHostTest, linuxX64Test, the Apple ones) run in their own
        // CI jobs.
        if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") &&
            tasks.findByName("test") == null &&
            tasks.findByName("jvmTest") != null
        ) {
            tasks.register("test") {
                group = "verification"
                description = "Runs the JVM unit tests for this Kotlin Multiplatform module."
                dependsOn("jvmTest")
            }
        }
    }
}
