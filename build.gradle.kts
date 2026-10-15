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

// Installs .git-hooks/ (spotlessCheck before a commit, the JVM tests before a
// push) into this clone. Runs with the first JVM compile, so a fresh clone gets
// the hooks on its first build.
val installGitHook =
    tasks.register<Copy>("installGitHook") {
        val dotGit = File(rootProject.rootDir, ".git")
        val hooksDir: File =
            if (dotGit.isFile) {
                // Git worktree: .git is a file with "gitdir: <path>"
                File(File(dotGit.readText().trim().removePrefix("gitdir: ")), "hooks")
            } else {
                File(dotGit, "hooks")
            }
        from(File(rootProject.rootDir, ".git-hooks/pre-commit"))
        from(File(rootProject.rootDir, ".git-hooks/pre-push"))
        into(hooksDir)
        filePermissions { unix("0777") }
    }

subprojects {
    tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(installGitHook) }
}
