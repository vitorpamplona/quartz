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
        // libschnorr256k1-kotlin from JitPack until 1.0.1 is on Maven Central (the catalog says why).
        // That group resolves only here (no requests to Central for it), and nothing else does.
        // Remove when the catalog moves to the Maven Central coordinates.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.vitorpamplona.libschnorr256k1-kotlin") }
        }
    }
    // JitPack rewrites every Gradle module file it serves, and its rewrite drops the classifier
    // from the file names: an Apple target's metadata variant lists <module>-<version>.jar, which
    // doesn't exist, instead of the <module>-<version>-metadata.jar beside it, and the common and
    // Apple metadata compiles fail to resolve it. Point those variants back at the real file.
    // Remove with the JitPack repository above.
    components {
        listOf("iosArm64", "iosSimulatorArm64", "macosArm64").forEach { target ->
            val module = "schnorr256k1-kotlin-${target.lowercase()}"
            withModule("com.github.vitorpamplona.libschnorr256k1-kotlin:$module") {
                val file = "$module-${id.version}-metadata.jar"
                withVariant("${target}MetadataElements-published") {
                    withFiles {
                        removeAllFiles()
                        addFile(file)
                    }
                }
            }
        }
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
