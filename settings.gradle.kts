pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("Honeywell_RFID_SDK_v0.5.0/libs") }
    }
}

rootProject.name = "RFIDHunter"
include(":app")
