pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // kmp-negentropy is published on JitPack only.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.vitorpamplona.negentropy")
            }
        }
    }
}

rootProject.name = "quartz"
include(":quartz")

// crypto-kmp (vitorpamplona/crypto-kmp) and mls-kmp (vitorpamplona/mls-kmp) come from Maven
// Central. To change one alongside quartz, point Gradle at a local checkout and it builds that
// checkout instead of the published artifact:
//   ./gradlew -PcryptoKmpDir=../crypto-kmp :quartz:jvmTest
// When quartz is itself an included build (Amethyst's -PquartzDir), the same -P flags reach this
// file too; the outer build includes those checkouts once for the whole composite, so skip them.
val isIncludedBuild = gradle.parent != null

providers.gradleProperty("cryptoKmpDir").orNull?.takeUnless { isIncludedBuild }?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("com.vitorpamplona.crypto:crypto")).using(project(":crypto"))
        }
    }
}

//   ./gradlew -PmlsKmpDir=../mls-kmp :quartz:jvmTest
providers.gradleProperty("mlsKmpDir").orNull?.takeUnless { isIncludedBuild }?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("com.vitorpamplona.mls:mls")).using(project(":mls"))
        }
    }
}
