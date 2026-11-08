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
    }
}

rootProject.name = "quartz"
include(":quartz")

// crypto-kmp (vitorpamplona/crypto-kmp) comes from Maven Central. To change it alongside quartz,
// point Gradle at a local checkout and it builds that checkout instead of the published artifact:
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

// The same for libschnorr256k1-kotlin (vitorpamplona/libschnorr256k1-kotlin):
//   ./gradlew -Pschnorr256k1KotlinDir=../libschnorr256k1-kotlin :quartz:jvmTest
providers.gradleProperty("schnorr256k1KotlinDir").orNull?.takeUnless { isIncludedBuild }?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("com.vitorpamplona.schnorr256k1:schnorr256k1-kotlin")).using(project(":schnorr256k1-kotlin"))
            // the JitPack coordinates, while the catalog uses them
            substitute(module("com.github.vitorpamplona.libschnorr256k1-kotlin:schnorr256k1-kotlin")).using(project(":schnorr256k1-kotlin"))
        }
    }
}
