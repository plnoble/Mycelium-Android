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
    }
}

rootProject.name = "Mycelium-Android"
include(":app")
include(":contract")

// The pure-Kotlin contract consumer lives in contract-lib/. The directory name
// contract/ is reserved for the byte-for-byte copy of the Windows contract.
project(":contract").projectDir = file("contract-lib")
include(":model-api", ":llm", ":hearth")
