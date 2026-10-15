pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "quartz-root"

include(":core")
include(":store")
include(":store-sqlite")
include(":relay-client")
include(":relay-server")
include(":relay-management")
include(":nip46-remote-signer")
include(":nip55-android-signer")
include(":social")
include(":dms")
include(":payments")
include(":trusted-assertions")
include(":git")
include(":chess")
include(":ots")
include(":relay-groups")
include(":dvms")
include(":podcasts")
include(":marketplace")
include(":media-servers")
include(":nsites")
include(":webrtc-calls")
include(":ble")
include(":push-notifications")
include(":relay-monitor")
include(":mls")
include(":marmot")
include(":cordn")
include(":contextvm")
include(":concord")
include(":buzz")
include(":cyberspace")
include(":experimental")
include(":quartz")
include(":testing")
