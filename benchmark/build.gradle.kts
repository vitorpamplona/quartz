import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android microbenchmarks for Quartz: event parsing, hashing, signing, NIP-44,
// gift wraps, caches and the SQLite store. Not published. They run on a device
// or emulator:
//
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest
plugins {
    // AGP's version comes from the root build, which loads it for every project.
    id("com.android.library")
    alias(libs.plugins.androidBenchmark)
}

android {
    namespace = "com.vitorpamplona.quartz.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Enable measuring on an emulator, or devices with low battery
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY"
    }

    testOptions {
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("androidTest") {
            resources.directories.add(rootProject.file("test-resources").path)
        }
    }

    testBuildType = "benchmark"
    buildTypes {
        getByName("debug") {
            // Since debuggable can't be modified by gradle for library modules,
            // it must be done in a manifest - see src/androidTest/AndroidManifest.xml
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "benchmark-proguard-rules.pro")
        }
        getByName("release") {
            isDefault = false
            isMinifyEnabled = true
        }
        create("benchmark") {
            isDefault = true
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("com.vitorpamplona.quartz.utils.InternalQuartzApi")
    }
}

dependencies {
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(project(":quartz"))

    // Custom C secp256k1 (libschnorr256k1) for the 3-way secp256k1 benchmark
    androidTestImplementation(libs.schnorr256k1.kmp)
}
