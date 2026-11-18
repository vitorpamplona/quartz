import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.diffplugSpotless)
}

allprojects {
    apply(plugin = "com.diffplug.spotless")

    spotless {
        kotlin {
            target("src/**/*.kt")

            // Same ktlint and header as Amethyst, crypto-kmp and quic-kmp, so a file moved
            // between the repos formats identically on every side.
            ktlint("1.7.1")
            licenseHeaderFile(
                rootProject.file("spotless/copyright.kt"),
                "@file:|package|import|class|object|sealed|open|interface|abstract ",
            )
        }
    }
}

subprojects {
    afterEvaluate {
        // Kotlin Multiplatform registers no plain `test` task, so `./gradlew test` would skip
        // :quartz silently. Alias it onto jvmTest.
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

tasks.register<Copy>("installGitHook") {
    from("git-hooks/pre-commit")
    from("git-hooks/pre-push")
    into(".git/hooks")
    filePermissions { unix("rwxrwxrwx") }
}

// Kotlin/Native and Kotlin/JS klibs record the path of every source file. Recording it relative to
// this repository instead of the checkout's absolute path keeps a klib rebuilt in any folder
// byte-identical to the released one.
subprojects {
    val klibPathBase = "-Xklib-relative-path-base=${rootDir.absolutePath}"
    tasks.withType<KotlinNativeCompile>().configureEach { compilerOptions.freeCompilerArgs.add(klibPathBase) }
    tasks.withType<Kotlin2JsCompile>().configureEach { compilerOptions.freeCompilerArgs.add(klibPathBase) }
}
